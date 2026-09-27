package com.aiwatch.probe.voice

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.aiwatch.audio.AudioCaptureSource
import com.aiwatch.audio.ConcentusOpusCodec
import com.aiwatch.audio.PcmPlaybackSink
import com.aiwatch.probe.ProbeApplication
import com.aiwatch.probe.conversation.ConversationState
import com.aiwatch.probe.conversation.MessageAuthor
import com.aiwatch.protocol.BootstrapResult
import com.aiwatch.protocol.DeviceIdentity
import com.aiwatch.protocol.PlaybackAudioConfig
import com.aiwatch.protocol.ProtocolEvent
import com.aiwatch.protocol.SessionPhase
import com.aiwatch.protocol.SessionTransport
import com.aiwatch.protocol.SocketEvent
import com.aiwatch.protocol.WebSocketConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * v0.2 / P0-2B contract harness for [XiaozhiVoiceSession].
 *
 * What is real here: XiaozhiVoiceSession, SessionCoordinator, the conversation state machine,
 * PcmFrameAccumulator, the Concentus Opus codec and PlaybackQueue.
 *
 * What is faked, and only this: the network transport, the microphone and the final hardware playback
 * write, plus a controlled monotonic clock so latency can be asserted as exact numbers instead of
 * "not null".
 *
 * Nothing about the protocol state machine, the codec or the playback queue is reimplemented here. The
 * fakes implement the real interfaces from :core-protocol and :core-audio; they do not parse JSON and do
 * not carry a second protocol implementation.
 */
class XiaozhiVoiceSessionContractTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // ------------------------------------------------------------------ harness

    /**
     * Implements the real [SessionTransport] interface. Records call order so barge-in ordering can be
     * proven at runtime rather than by reading HardInterruptPolicy source.
     */
    private class FakeSessionTransport(private val order: CopyOnWriteArrayList<String>) : SessionTransport {
        private val ordering get() = order
        val uplinkPackets = CopyOnWriteArrayList<ByteArray>()
        val connectCalls = CopyOnWriteArrayList<WebSocketConfig>()

        private val events = Channel<SocketEvent>(Channel.UNLIMITED)
        private var connectionId = 0L

        override suspend fun nextEvent(): SocketEvent = events.receive()

        override fun connect(config: WebSocketConfig, identity: DeviceIdentity): Long {
            connectCalls += config
            connectionId += 1
            ordering += "connect#$connectionId"
            return connectionId
        }

        override fun listen(start: Boolean): Boolean {
            ordering += if (start) "listen(true)" else "listen(false)"
            return true
        }

        override fun abort(): Boolean {
            ordering += "abort"
            return true
        }

        override fun sendAudio(bytes: ByteArray): Boolean {
            uplinkPackets += bytes
            ordering += "sendAudio(${bytes.size})"
            return true
        }

        override fun disconnect() {
            ordering += "disconnect"
        }

        override fun close() {
            events.close()
        }

        /** Pushes an inbound event for the current connection. */
        fun emit(event: ProtocolEvent) {
            events.trySend(SocketEvent.Message(connectionId, event))
        }
    }

    /** Deterministic PCM. Default 1000 samples: one full 960-frame plus a 40-sample tail to pad. */
    private class FakeCaptureSource(
        private val totalSamples: Int = 1000,
        private val chunkSamples: Int = 500,
    ) : AudioCaptureSource {
        private var emitted = 0
        @Volatile private var stopped = false
        private val gate = Object()

        override fun start() = Unit

        override fun read(buffer: ShortArray): Int {
            while (!stopped && emitted >= totalSamples) {
                synchronized(gate) { gate.wait(20) }
            }
            if (stopped) return 0
            val count = minOf(chunkSamples, totalSamples - emitted, buffer.size)
            for (i in 0 until count) buffer[i] = ((emitted + i) % 128).toShort()
            emitted += count
            return count
        }

        override fun stop() {
            stopped = true
            synchronized(gate) { gate.notifyAll() }
        }

        override fun close() = stop()
    }

    /**
     * Fakes ONLY the final hardware write. The real Concentus decode and the real PlaybackQueue stay in
     * the path, so section F still exercises our own glue. pauseAndFlush writes to the same ordering log
     * as the transport, which is what lets the barge-in test prove the order at runtime.
     */
    private class FakePcmPlaybackSink(private val order: CopyOnWriteArrayList<String>) : PcmPlaybackSink {
        @Volatile var writtenSamples = 0L
            private set
        @Volatile var flushCount = 0
            private set
        @Volatile var blockFirstWrite = false

        /**
         * Returns 0 instead of accepting samples. Used by the barge-in test: it leaves decoded PCM
         * sitting inside the PlaybackQueue without growing the accepted count, so after an interrupt the
         * count must stay flat. Deliberately NOT the blocking mode: PlaybackQueue.pump() calls write()
         * while holding its lock, so a blocking write would also block the interrupt flush.
         */
        @Volatile var rejectWrites = false
        @Volatile var writeCalls = 0

        private val writeGate = Object()

        override fun write(pcm: ShortArray, offset: Int, length: Int): Int {
            // wait(10) returned on timeout and the write was accepted anyway, so the clock could be raced.
            // Hold the monitor across the whole wait and re-check the flag.
            synchronized(writeGate) {
                while (blockFirstWrite) {
                    writeGate.wait(20)
                }
            }
            writeCalls += 1
            if (rejectWrites) {
                order += "sink.write(0)"
                return 0
            }
            writtenSamples += length
            order += "sink.write($length)"
            return length
        }

        override fun pauseAndFlush() {
            flushCount += 1
            order += "pauseAndFlush"
        }

        fun releaseWrite() {
            synchronized(writeGate) {
                blockFirstWrite = false
                writeGate.notifyAll()
            }
        }

        override fun close() = Unit
    }

    private val order = CopyOnWriteArrayList<String>()
    private var playbackSink = FakePcmPlaybackSink(order)
    private var clock = 0L
    private var transport = FakeSessionTransport(order)
    private var session: XiaozhiVoiceSession? = null

    private val application: ProbeApplication
        get() = instrumentation.targetContext.applicationContext as ProbeApplication

    private fun startSession(
        endpoint: String = "https://harness.invalid/xiaozhi/ota/",
        bootstrapCalls: MutableList<Int>? = null,
    ): XiaozhiVoiceSession {
        transport = FakeSessionTransport(order)
        order.clear()
        playbackSink = FakePcmPlaybackSink(order)
        clock = 0L
        val created = XiaozhiVoiceSession(
            application = application,
            endpoint = endpoint,
            transport = transport,
            bootstrapRequest = { _: DeviceIdentity ->
                bootstrapCalls?.add(1)
                BootstrapResult.Ready(WebSocketConfig("wss://harness.invalid/xiaozhi/v1/", "harness-token"))
            },
            captureSource = { FakeCaptureSource() },
            playbackSinkFactory = { playbackSink },
            monotonicClock = { clock },
        )
        session = created
        return created
    }

    /** close() returns a Job; not joining it lets a test exit mid-teardown and invents races later. */
    private fun closeSession(s: XiaozhiVoiceSession) = runBlocking { s.close().join() }

    private fun waitUntil(what: String, timeoutMs: Long = 8_000, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(20)
        }
        println("CONTRACT timeout waiting for: $what")
        return false
    }

    private fun hello(): ProtocolEvent.Hello =
        ProtocolEvent.Hello("harness-session", PlaybackAudioConfig("opus", 24_000, 1, 60))

    // ------------------------------------------------------------------ A

    @Test
    fun a_connectBootstrapAndHelloReachReady() {
        val boots = mutableListOf<Int>()
        val s = startSession(bootstrapCalls = boots)
        try {
            s.connect()
            assertTrue("bootstrap was never requested", waitUntil("bootstrap") { boots.isNotEmpty() })
            assertTrue("transport never connected", waitUntil("connect") { transport.connectCalls.isNotEmpty() })

            transport.emit(hello())
            assertTrue(
                "session never reached READY",
                waitUntil("READY") { s.state.value.phase == SessionPhase.READY },
            )
            println("CONTRACT_A phase=${s.state.value.phase} boots=${boots.size} connects=${transport.connectCalls.size}")

            // The playback configuration must come from the Server Hello, not from a hard-coded value.
            assertEquals("sample rate must come from Hello", 24_000, s.output?.sampleRate)
            assertEquals("channels must come from Hello", 1, s.output?.channels)
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ B

    @Test
    fun b_captureFramesAreEncodedAndSentUplink() {
        val s = startSession()
        try {
            s.connect()
            waitUntil("connect") { transport.connectCalls.isNotEmpty() }
            transport.emit(hello())
            assertTrue("not READY", waitUntil("READY") { s.state.value.phase == SessionPhase.READY })

            s.onCaptureStarted()
            assertTrue("no uplink packet was sent", waitUntil("uplink") { transport.uplinkPackets.isNotEmpty() })
            // 1000 samples -> one full 960 frame while recording; the 40-sample tail is finished by
            // onCaptureReleased() via the real accumulator padding.
            assertTrue("first uplink packet is empty", transport.uplinkPackets.first().isNotEmpty())
            println("CONTRACT_B uplink=${transport.uplinkPackets.size} txPackets=${s.txPackets.get()}")
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ C

    /**
     * Expected RED until beginTurn() stops calling SystemClock directly: the injected monotonicClock is
     * the seam, so with clock == 1000 the release mark must be exactly 1000.
     */
    @Test
    fun c_releaseStampsTheInjectedClock() {
        val s = startSession()
        try {
            s.connect()
            waitUntil("connect") { transport.connectCalls.isNotEmpty() }
            transport.emit(hello())
            assertTrue("not READY", waitUntil("READY") { s.state.value.phase == SessionPhase.READY })

            s.onCaptureStarted()
            assertTrue("no uplink yet", waitUntil("uplink") { transport.uplinkPackets.isNotEmpty() })

            clock = 1000L
            s.onCaptureReleased()
            assertTrue("release mark never recorded", waitUntil("releaseAt") { s.latency.value.releaseAt != null })

            println("CONTRACT_C expected=1000 actual=${s.latency.value.releaseAt} " +
                "trace=${s.latency.value.summary()}")
            assertEquals("releaseAt must come from the injected clock", 1000L, s.latency.value.releaseAt)

            // The 40-sample tail must be padded into a second, real uplink frame.
            assertTrue("final padded frame missing", waitUntil("padded") { s.paddedFinalFrames.get() == 1L })
            assertEquals("txPackets", 2L, s.txPackets.get())
            assertEquals("paddedFinalFrames", 1L, s.paddedFinalFrames.get())
            assertEquals("paddingSamples", 920L, s.paddingSamples.get())
            assertEquals("discardedTailSamples", 0L, s.discardedTailSamples.get())
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ D

    @Test
    fun d_sttCreatesOneUserBubbleAndUpdatesItInPlace() {
        val s = startSession()
        try {
            s.connect()
            waitUntil("connect") { transport.connectCalls.isNotEmpty() }
            transport.emit(hello())
            assertTrue("not READY", waitUntil("READY") { s.state.value.phase == SessionPhase.READY })

            s.onCaptureStarted()
            waitUntil("uplink") { transport.uplinkPackets.isNotEmpty() }
            clock = 1000L
            s.onCaptureReleased()

            clock = 1120L
            transport.emit(ProtocolEvent.Stt("我下周三要去医院"))
            assertTrue("stt mark never recorded", waitUntil("sttFinalAt") { s.latency.value.sttFinalAt != null })
            println("CONTRACT_D sttFinalAt=${s.latency.value.sttFinalAt} trace=${s.latency.value.summary()}")
            assertEquals("sttFinalAt must come from the injected clock", 1120L, s.latency.value.sttFinalAt)

            assertTrue(
                "expected exactly one USER bubble",
                waitUntil("user bubble") { s.transcript.value.count { it.author == MessageAuthor.USER } == 1 },
            )

            // A second STT for the same turn must update that bubble, not add another.
            transport.emit(ProtocolEvent.Stt("我下周三下午三点要去医院，最近老忘"))
            assertTrue(
                "second STT did not update in place",
                waitUntil("updated user bubble") {
                    val users = s.transcript.value.filter { it.author == MessageAuthor.USER }
                    users.size == 1 && users.first().text.contains("下午三点")
                },
            )
            println("CONTRACT_D bubbles=${s.transcript.value.map { "${it.author}:${it.text}" }}")
            assertEquals("still exactly one USER bubble", 1, s.transcript.value.count { it.author == MessageAuthor.USER })
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun reachReady(s: XiaozhiVoiceSession) {
        s.connect()
        assertTrue("transport never connected", waitUntil("connect") { transport.connectCalls.isNotEmpty() })
        transport.emit(hello())
        assertTrue("never READY", waitUntil("READY") { s.state.value.phase == SessionPhase.READY })
    }

    /**
     * Drives one push-to-talk turn and waits for the turn to be genuinely under way.
     *
     * The baseline count matters: on a second turn `uplinkPackets.isNotEmpty()` is already true from turn
     * one, so waiting on it returns immediately, the test releases before the capture worker has reached
     * beginCapture(), and the worker then sees a non-RECORDING state and returns without ever starting the
     * turn. Waiting for growth relative to this turn is what makes the sequencing real.
     */
    private fun startTurn(s: XiaozhiVoiceSession, label: String = "turn") {
        val packetsBefore = transport.uplinkPackets.size
        s.onCaptureStarted()
        assertTrue(
            "$label never produced a new uplink packet",
            waitUntil("$label uplink") { transport.uplinkPackets.size > packetsBefore },
        )
        s.onCaptureReleased()
        // Release reaches THINKING only once the capture job's finally calls coordinator.endCapture(), and
        // ConversationStateMachine.ttsStarted() accepts the transition out of THINKING only.
        assertTrue(
            "$label never reached THINKING after release",
            waitUntil("$label THINKING") { s.uiState.value == ConversationState.THINKING },
        )
    }

    /**
     * A downlink packet that the production decoder accepts at this Hello's configuration.
     *
     * DELIBERATE DEVIATION, reported rather than hidden: :core-audio declares Concentus with
     * `implementation(files(concentusJar))`, so org.concentus is not on this module's compile classpath
     * and the harness cannot construct a 24 kHz OpusEncoder directly. It therefore encodes through the
     * module's own public codec. An Opus packet does not carry a sample rate, so a 60 ms packet decodes
     * correctly at the Hello rate - and the decode assertion below is what makes that a checked fact
     * instead of an assumption. Switching :core-audio to `api` would allow a native 24 kHz encode.
     */
    private fun downlinkPacket(config: PlaybackAudioConfig): ByteArray {
        val codec = ConcentusOpusCodec(config)
        val frame = ShortArray(960) { i -> (Math.sin(i / 8.0) * 6000.0).toInt().toShort() }
        val packet = codec.encodeUplink(frame)
        assertTrue("downlink packet is empty", packet.isNotEmpty())
        val decoded = codec.decodeDownlink(packet)
        assertTrue("packet does not decode at the Hello configuration", decoded.isNotEmpty())
        return packet
    }

    private fun companionBubbles(s: XiaozhiVoiceSession) =
        s.transcript.value.filter { it.author == MessageAuthor.COMPANION }

    // ------------------------------------------------------------------ E

    @Test
    fun e_ttsTextAccumulatesIntoOneCompanionBubble() {
        val s = startSession()
        try {
            reachReady(s)
            startTurn(s)
            assertTrue("never THINKING", waitUntil("THINKING") {
                s.uiState.value == ConversationState.THINKING
            })

            clock = 1250L
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START, "第一句"))
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.SENTENCE_START, "第二句"))
            assertTrue("companion turn text missing", waitUntil("turn bubble") {
                companionBubbles(s).any { it.text.contains("第一句") && it.text.contains("第二句") }
            })

            // The greeting is one companion bubble; the turn must be exactly one more, not one per sentence.
            println("CONTRACT_E companions=${companionBubbles(s).map { it.text }}")
            assertEquals("expected greeting + exactly one turn bubble", 2, companionBubbles(s).size)
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ F

    @Test
    fun f_firstTurnBinaryAudioProducesExactLatency() {
        val s = startSession()
        try {
            reachReady(s)
            clock = 1000L
            startTurn(s)
            clock = 1120L
            transport.emit(ProtocolEvent.Stt("我下周三要去医院"))
            waitUntil("stt") { s.latency.value.sttFinalAt != null }
            clock = 1250L
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START, "好，我记下了"))
            assertTrue("never SPEAKING", waitUntil("SPEAKING") { s.uiState.value == ConversationState.SPEAKING })

            // Block the first hardware write so the clock cannot be raced by the playback worker.
            playbackSink.blockFirstWrite = true
            clock = 1310L
            transport.emit(ProtocolEvent.BinaryAudio(downlinkPacket(hello().audio)))
            assertTrue("first binary audio not marked", waitUntil("firstBinaryAudioAt") {
                s.latency.value.firstBinaryAudioAt != null
            })
            assertEquals("firstBinaryAudioAt", 1310L, s.latency.value.firstBinaryAudioAt)

            clock = 1360L
            playbackSink.releaseWrite()
            assertTrue("first sink write never recorded", waitUntil("firstSinkWrite") {
                s.latency.value.firstPlaybackSinkWriteAt != null
            })

            val trace = s.latency.value
            println("CONTRACT_F ${trace.summary()}")
            assertEquals("firstPlaybackSinkWriteAt", 1360L, trace.firstPlaybackSinkWriteAt)
            assertEquals("releaseToFirstAudioMs", 360L, trace.releaseToFirstAudioMs)
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ G

    @Test
    fun g_ttsStopReturnsToIdle() {
        val s = startSession()
        try {
            reachReady(s)
            clock = 1000L
            startTurn(s)
            clock = 1250L
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START, "说完了"))
            assertTrue("never SPEAKING", waitUntil("SPEAKING") { s.uiState.value == ConversationState.SPEAKING })

            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.STOP))
            assertTrue("never returned to IDLE", waitUntil("IDLE") { s.uiState.value == ConversationState.IDLE })
            println("CONTRACT_G uiState=${s.uiState.value}")
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ H

    /**
     * Targets the second suspected production bug without depending on the first one.
     *
     * It asserts only that the SECOND turn records its own first sink write. It deliberately does NOT
     * assert releaseToFirstAudioMs here, because that would be red from bug #1 and would mask this one.
     */
    @Test
    fun h_secondTurnStillRecordsItsFirstSinkWrite() {
        val s = startSession()
        try {
            reachReady(s)

            // ---- turn 1
            clock = 1000L
            startTurn(s, "turn 1")
            clock = 1250L
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START, "第一轮"))
            waitUntil("SPEAKING 1") { s.uiState.value == ConversationState.SPEAKING }
            transport.emit(ProtocolEvent.BinaryAudio(downlinkPacket(hello().audio)))
            assertTrue("turn 1 never wrote to the sink", waitUntil("turn1 sink write") {
                playbackSink.writtenSamples > 0L
            })
            assertTrue("turn 1 first sink write not recorded", waitUntil("turn1 mark") {
                s.latency.value.firstPlaybackSinkWriteAt != null
            })
            val writesAfterTurn1 = playbackSink.writtenSamples
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.STOP))
            assertTrue("never IDLE after turn 1", waitUntil("IDLE 1") { s.uiState.value == ConversationState.IDLE })

            // ---- turn 2
            clock = 2000L
            startTurn(s, "turn 2")
            clock = 2250L
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START, "第二轮"))
            waitUntil("SPEAKING 2") { s.uiState.value == ConversationState.SPEAKING }
            transport.emit(ProtocolEvent.BinaryAudio(downlinkPacket(hello().audio)))

            assertTrue("turn 2 never wrote to the sink", waitUntil("turn2 sink write") {
                playbackSink.writtenSamples > writesAfterTurn1
            })
            println(
                "CONTRACT_H turn1Writes=$writesAfterTurn1 turn2Writes=${playbackSink.writtenSamples} " +
                    "secondMark=${s.latency.value.firstPlaybackSinkWriteAt} trace=${s.latency.value.summary()}",
            )
            // The sink really did accept samples in turn 2, so the mark must exist for turn 2 as well.
            assertNotNull(
                "second turn wrote to the sink but recorded no first-sink-write mark",
                s.latency.value.firstPlaybackSinkWriteAt,
            )
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ I

    @Test
    fun i_interruptFlushesLocallyBeforeAbortAndStopsWriting() {
        val s = startSession()
        try {
            reachReady(s)
            clock = 1000L
            startTurn(s)
            clock = 1250L
            transport.emit(ProtocolEvent.Tts(ProtocolEvent.Tts.Phase.START, "被打断"))
            assertTrue("never SPEAKING", waitUntil("SPEAKING") { s.uiState.value == ConversationState.SPEAKING })

            // Leave decoded PCM inside the queue without growing the accepted count.
            playbackSink.rejectWrites = true
            transport.emit(ProtocolEvent.BinaryAudio(downlinkPacket(hello().audio)))
            assertTrue("playback never attempted a write", waitUntil("write attempt") {
                playbackSink.writeCalls > 0
            })

            order.clear()
            s.interrupt()
            assertTrue("interrupt never reached the transport", waitUntil("abort") {
                order.any { it == "abort" }
            })
            waitUntil("connect#2") { order.any { it.startsWith("connect#2") } }

            val log = order.toList()
            val flushAt = log.indexOfFirst { it == "pauseAndFlush" }
            val abortAt = log.indexOfFirst { it == "abort" }
            val disconnectAt = log.indexOfFirst { it == "disconnect" }
            println("CONTRACT_I order=$log")
            println("CONTRACT_I flushAt=$flushAt abortAt=$abortAt disconnectAt=$disconnectAt written=${playbackSink.writtenSamples}")

            assertTrue("no pauseAndFlush was recorded", flushAt >= 0)
            assertTrue("local flush must happen BEFORE abort (flushAt=$flushAt abortAt=$abortAt)", flushAt < abortAt)
            assertTrue("abort must precede the reconnect disconnect (abortAt=$abortAt disconnectAt=$disconnectAt)", abortAt < disconnectAt)
            assertTrue("reconnect never produced a second connection", log.any { it.startsWith("connect#2") })

            // Old turn audio must not reach the sink after the interrupt.
            SystemClock.sleep(600)
            assertEquals("stale turn audio was written after interrupt", 0L, playbackSink.writtenSamples)
        } finally {
            closeSession(s)
        }
    }

    // ------------------------------------------------------------------ J

    @Test
    fun j_blankEndpointFailsClosedWithoutFabricatingATurn() {
        val boots = mutableListOf<Int>()
        val s = startSession(endpoint = "", bootstrapCalls = boots)
        try {
            s.connect()
            SystemClock.sleep(800)

            println("CONTRACT_J phase=${s.state.value.phase} ui=${s.uiState.value} boots=${boots.size} " +
                "connects=${transport.connectCalls.size} userBubbles=${s.transcript.value.count { it.author == MessageAuthor.USER }}")

            assertEquals("bootstrap must not be called without an endpoint", 0, boots.size)
            assertEquals("transport must not connect without an endpoint", 0, transport.connectCalls.size)
            assertTrue("phase must not be READY", s.state.value.phase != SessionPhase.READY)
            assertEquals("UI must stay IDLE", ConversationState.IDLE, s.uiState.value)
            assertEquals("no user turn may be fabricated", 0, s.transcript.value.count { it.author == MessageAuthor.USER })
        } finally {
            closeSession(s)
        }
    }
}
