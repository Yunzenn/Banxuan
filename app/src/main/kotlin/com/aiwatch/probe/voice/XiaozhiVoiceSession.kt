package com.aiwatch.probe.voice

import android.os.SystemClock
import android.util.Log
import com.aiwatch.audio.AndroidAudioCaptureSource
import com.aiwatch.audio.AndroidPcmPlaybackSink
import com.aiwatch.audio.AudioCaptureSource
import com.aiwatch.audio.ConcentusOpusCodec
import com.aiwatch.audio.PcmFrameAccumulator
import com.aiwatch.audio.PcmPlaybackSink
import com.aiwatch.audio.PlaybackQueue
import com.aiwatch.probe.ProbeApplication
import com.aiwatch.probe.conversation.MessageAuthor
import com.aiwatch.probe.conversation.MessageItem
import com.aiwatch.protocol.BootstrapRepository
import com.aiwatch.protocol.BootstrapResult
import com.aiwatch.protocol.DeviceIdentity
import com.aiwatch.protocol.SessionTransport
import com.aiwatch.protocol.ConversationState as ProtocolConversation
import com.aiwatch.protocol.PlaybackAudioConfig
import com.aiwatch.protocol.ProtocolEvent
import com.aiwatch.protocol.SessionCoordinator
import com.aiwatch.protocol.SessionPhase
import com.aiwatch.protocol.WebSocketTransport
import com.aiwatch.probe.conversation.ConversationState as UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Latest caption line. [fromUser] true once STT has produced text for the current turn. */
data class SessionCaption(val text: String = "", val fromUser: Boolean = false)

/**
 * Timestamps for one push-to-talk turn, all from [SystemClock.elapsedRealtime].
 *
 * The number that matters for P0-2 is [releaseToFirstAudioMs]: PTT release -> the first sample the
 * playback sink actually accepted. The intermediate marks exist to attribute a slow turn to a stage
 * (server ASR, server TTS, first packet, decode/queue) instead of guessing.
 */
data class LatencyTrace(
    val releaseAt: Long? = null,
    val sttFinalAt: Long? = null,
    val ttsStartAt: Long? = null,
    val firstBinaryAudioAt: Long? = null,
    val firstPlaybackSinkWriteAt: Long? = null,
) {
    val releaseToFirstAudioMs: Long?
        get() = if (releaseAt != null && firstPlaybackSinkWriteAt != null) {
            firstPlaybackSinkWriteAt - releaseAt
        } else {
            null
        }

    fun summary(): String = buildString {
        append("release=").append(offset(releaseAt))
        append(" stt=").append(offset(sttFinalAt))
        append(" ttsStart=").append(offset(ttsStartAt))
        append(" firstPacket=").append(offset(firstBinaryAudioAt))
        append(" firstSinkWrite=").append(offset(firstPlaybackSinkWriteAt))
        append(" releaseToFirstAudioMs=").append(releaseToFirstAudioMs ?: -1)
    }

    private fun offset(mark: Long?): String =
        if (mark == null || releaseAt == null) "-" else (mark - releaseAt).toString()
}

/**
 * The protocol's conversation machine has five states; the companion UI shows four.
 *
 * The protocol's interrupt path is Speaking -> INTERRUPTING -> LISTENING, and by the time it reports
 * INTERRUPTING the companion has already stopped talking and the microphone is stopped too, so what the
 * user is looking at is "the companion is listening". Mapping it to LISTENING keeps the four-state UI
 * truthful instead of inventing a fifth visual state the product brief does not have.
 */
fun ProtocolConversation.toUiState(): UiState = when (this) {
    ProtocolConversation.IDLE -> UiState.IDLE
    ProtocolConversation.LISTENING -> UiState.LISTENING
    ProtocolConversation.THINKING -> UiState.THINKING
    ProtocolConversation.SPEAKING -> UiState.SPEAKING
    ProtocolConversation.INTERRUPTING -> UiState.LISTENING
}

/**
 * P0-2A: the real Xiaozhi voice session, extracted from `DebugAudioSession` without changing the
 * capture / protocol / playback logic it already had.
 *
 * Owns: microphone capture and 60 ms Opus framing, the [SessionCoordinator], downlink decode and the
 * [PlaybackQueue], the turn transcript, the UI-facing four states, and the latency trace.
 *
 * This is the production path. `DebugAudioSession` is now a thin delegate over it, so the debug screen,
 * the ten-minute emulator soak and the codec tests all keep exercising the same code.
 */
class XiaozhiVoiceSession(
    private val application: ProbeApplication,
    private val endpoint: String,
    client: OkHttpClient = OkHttpClient(),
    /**
     * v0.2 injection seam. Production always uses the real WebSocket transport; the contract harness
     * supplies a fake so the session's own state machine, transcript, latency marks and barge-in
     * behaviour can be exercised without a server. `SessionTransport` is already an interface, so this
     * needs no change to `core-protocol`.
     */
    private val transport: SessionTransport = WebSocketTransport(client),
    /** v0.2 injection seam for bootstrap, so the harness does not need a reachable HTTPS endpoint. */
    private val bootstrapRequest: suspend (DeviceIdentity) -> BootstrapResult = { identity ->
        BootstrapRepository(client).fetch(endpoint, identity, "p0-2a-companion")
    },
    /** v0.2 injection seam for capture, so the harness can drive synthetic PCM instead of a microphone. */
    private val captureSource: () -> AudioCaptureSource = { AndroidAudioCaptureSource(application) },
    /**
     * v0.2 injection seam for the hardware output boundary. The contract harness keeps the real Opus
     * decode and the real [PlaybackQueue] and only fakes the final sink write, so the glue under test is
     * ours while the test stays deterministic and independent of AudioTrack.
     */
    private val playbackSinkFactory: (PlaybackAudioConfig) -> PcmPlaybackSink = {
        AndroidPcmPlaybackSink(it)
    },
    /**
     * v0.2 injection seam for time, so a harness can assert exact latencies instead of merely asserting
     * that a timestamp is non-null.
     */
    private val monotonicClock: () -> Long = { SystemClock.elapsedRealtime() },
) : VoiceSessionAdapter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableCaption = MutableStateFlow(SessionCaption())
    val caption: StateFlow<SessionCaption> = mutableCaption.asStateFlow()

    private val mutableLatency = MutableStateFlow(LatencyTrace())
    val latency: StateFlow<LatencyTrace> = mutableLatency.asStateFlow()

    /** Companion greeting, so Home is never an empty panel on first launch. */
    private val mutableTranscript = MutableStateFlow(
        listOf(MessageItem(1L, MessageAuthor.COMPANION, GREETING, System.currentTimeMillis())),
    )
    val transcript: StateFlow<List<MessageItem>> = mutableTranscript.asStateFlow()

    private val mutableUiState = MutableStateFlow(UiState.IDLE)
    val uiState: StateFlow<UiState> = mutableUiState.asStateFlow()

    private val audioLock = Any()

    @Volatile
    private var playback: PlaybackQueue? = null

    @Volatile
    private var playbackConfig: PlaybackAudioConfig? = null

    @Volatile
    private var capture: AudioCaptureSource? = null

    @Volatile
    var audioError: String? = null
        private set

    private enum class CaptureState { RECORDING, FINISHING, CANCELLED }

    private val captureState = AtomicReference(CaptureState.CANCELLED)
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var playbackWake: Channel<Unit>? = null

    val txPackets = AtomicLong()
    val rxPackets = AtomicLong()
    val readChunks = AtomicLong()
    val discardedTailSamples = AtomicLong()
    val paddedFinalFrames = AtomicLong()
    val paddingSamples = AtomicLong()

    private val chunks = ConcurrentHashMap<Int, AtomicLong>()
    fun readChunkHistogram(): Map<Int, Long> = chunks.mapValues { it.value.get() }

    val playbackMetrics get() = playback?.metrics

    val coordinator = SessionCoordinator(
        scope,
        { application.identityStore.getOrCreate() },
        bootstrapRequest,
        transport,
        onEvent = ::onEvent,
        flushAudio = ::flush,
        onCaptureInvalidated = ::stopTalking,
    )

    val state get() = coordinator.state
    val output get() = playbackConfig
    val queueDepth get() = playback?.let { "${it.encodedDepth} encoded / ${it.pcmDepth} PCM" } ?: "0"

    private var nextMessageId = 2L
    private var currentUserMessageId: Long? = null
    private var currentCompanionMessageId: Long? = null

    fun connect() {
        if (endpoint.isBlank()) {
            Log.i(TAG, "no bootstrap endpoint configured; skipping connect")
            return
        }
        scope.launch { coordinator.start() }
    }

    // --- VoiceSessionAdapter -------------------------------------------------------------------

    override fun onCaptureStarted() = startTalking()

    override fun onCaptureReleased() = finishTalking()

    override fun cancel() = interrupt()

    // --- capture -------------------------------------------------------------------------------

    /** Called from the main thread. One capture job at a time; never starts the mic just from connect. */
    fun startTalking() {
        if (captureJob?.isActive == true || state.value.phase != SessionPhase.READY) return
        captureState.set(CaptureState.RECORDING)
        captureJob = scope.launch {
            val source = captureSource()
            var generation: Long? = null
            var pendingFinalTail = 0
            val accumulator = PcmFrameAccumulator(960)
            try {
                val config = playbackConfig ?: return@launch
                val encoder = ConcentusOpusCodec(config)
                capture = source
                source.start()
                if (captureState.get() != CaptureState.RECORDING) return@launch
                generation = coordinator.beginCapture() ?: return@launch
                val buffer = ShortArray(1024)
                while (isActive && captureState.get() == CaptureState.RECORDING) {
                    val count = source.read(buffer)
                    if (captureState.get() == CaptureState.CANCELLED) break
                    // stop() may unblock read with no samples. Positive in-flight reads are preserved.
                    if (count <= 0 && captureState.get() == CaptureState.FINISHING) break
                    check(count > 0) { "Capture read failed" }
                    readChunks.incrementAndGet()
                    chunks.getOrPut(count) { AtomicLong() }.incrementAndGet()
                    for (frame in accumulator.append(buffer, length = count)) {
                        if (captureState.get() == CaptureState.CANCELLED) break
                        check(coordinator.sendAudio(encoder.encodeUplink(frame), generation)) {
                            "Uplink rejected"
                        }
                        txPackets.incrementAndGet()
                    }
                }
                if (isActive && captureState.get() == CaptureState.FINISHING) {
                    val tail = accumulator.bufferedSamples
                    accumulator.finishUtterance()?.let { frame ->
                        pendingFinalTail = tail
                        // The coordinator also checks the generation: an intervening interrupt cannot
                        // replay this frame.
                        if (captureState.get() != CaptureState.CANCELLED) {
                            check(coordinator.sendAudio(encoder.encodeUplink(frame), generation)) {
                                "Final uplink rejected"
                            }
                            txPackets.incrementAndGet()
                            pendingFinalTail = 0
                            paddedFinalFrames.incrementAndGet()
                            paddingSamples.addAndGet((960 - tail).toLong())
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (captureState.get() != CaptureState.CANCELLED) {
                    audioError = "Capture/encode/send failed; inspect device support and microphone permission"
                    coordinator.stop()
                }
            } finally {
                // Only cancellation/error can discard pending PCM. A normal release finalized above.
                discardedTailSamples.addAndGet(accumulator.bufferedSamples.toLong() + pendingFinalTail)
                captureState.set(CaptureState.CANCELLED)
                runCatching { source.close() }
                capture = null
                generation?.let {
                    withContext(NonCancellable) { runCatching { coordinator.endCapture(it) } }
                }
            }
        }
    }

    fun finishTalking() {
        if (captureState.get() != CaptureState.RECORDING) return
        beginTurn()
        captureState.compareAndSet(CaptureState.RECORDING, CaptureState.FINISHING)
        runCatching { capture?.stop() }
    }

    fun stopTalking() {
        captureState.set(CaptureState.CANCELLED)
        runCatching { capture?.stop() }
    }

    /**
     * Barge-in. Ordering is not decided here: the coordinator's HardInterruptPolicy performs
     * invalidateAndFlushAudio() -> sendAbort() -> reconnect(), and the flush inside that path clears the
     * caption, bumps the playback epoch and calls sink.pauseAndFlush(). See PROTOCOL_CONTRACT.md.
     */
    fun interrupt() {
        stopTalking()
        scope.launch { coordinator.abort() }
    }

    /** Starts a new turn: clears the latency trace and the per-turn message anchors. */
    private fun beginTurn() {
        mutableLatency.value = LatencyTrace(releaseAt = now())
        currentUserMessageId = null
        currentCompanionMessageId = null
    }

    private fun flush() {
        mutableCaption.value = SessionCaption()
        // beginCapture also flushes previous playback: do not stop the newly starting capture here.
        synchronized(audioLock) { playback?.flush() }
    }

    // --- event handling ------------------------------------------------------------------------

    private fun onEvent(event: ProtocolEvent, generation: Long) {
        when (event) {
            is ProtocolEvent.Hello -> {
                stopTalking()
                val codec = ConcentusOpusCodec(event.audio)
                val queue = PlaybackQueue(
                    codec::decodeDownlink,
                    playbackSinkFactory(event.audio),
                    onFirstSinkWrite = ::onPlaybackSinkFirstWrite,
                )
                val wake = Channel<Unit>(Channel.CONFLATED)
                synchronized(audioLock) {
                    playbackJob?.cancel()
                    playbackWake?.close()
                    playback?.close()
                    playbackWake = wake
                    playbackConfig = event.audio
                    playback = queue
                }
                playbackJob = scope.launch {
                    try {
                        while (isActive) {
                            wake.receive()
                            while (!queue.idle) {
                                queue.pump()
                                delay(5)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        audioError = "Decode/playback failed"
                        coordinator.stop()
                    }
                }
            }

            is ProtocolEvent.Stt -> {
                mutableCaption.value = SessionCaption(event.text.take(2000), true)
                markSttFinal()
                upsertUserMessage(event.text)
            }

            is ProtocolEvent.Tts -> {
                event.text?.takeIf { it.isNotBlank() }?.let {
                    mutableCaption.value = SessionCaption(it.take(2000))
                    upsertCompanionMessage(it)
                }
                when (event.phase) {
                    ProtocolEvent.Tts.Phase.START -> {
                        markTtsStart()
                        playback?.begin(generation)
                    }

                    ProtocolEvent.Tts.Phase.STOP -> playback?.end(generation)
                    else -> Unit
                }
            }

            is ProtocolEvent.BinaryAudio -> {
                check(playback?.offer(event.bytes, generation) == true) {
                    "Playback backpressure or stale generation"
                }
                playbackWake?.trySend(Unit)
                rxPackets.incrementAndGet()
                markFirstBinaryAudio()
            }

            else -> Unit
        }
    }

    /** Called on foreground loss. No background recording or reconnect survives this session. */
    fun close(): Job {
        stopTalking()
        return scope.launch {
            try {
                coordinator.close()
                captureJob?.cancelAndJoin()
                playbackJob?.cancelAndJoin()
            } finally {
                synchronized(audioLock) {
                    playbackWake?.close()
                    playback?.close()
                    playback = null
                    playbackConfig = null
                }
                scope.cancel()
            }
        }
    }

    // --- latency marks -------------------------------------------------------------------------

    private fun markSttFinal() = updateLatency { if (it.sttFinalAt == null) it.copy(sttFinalAt = now()) else it }

    private fun markTtsStart() = updateLatency { if (it.ttsStartAt == null) it.copy(ttsStartAt = now()) else it }

    private fun markFirstBinaryAudio() =
        updateLatency { if (it.firstBinaryAudioAt == null) it.copy(firstBinaryAudioAt = now()) else it }

    /**
     * The playback queue outlives a single turn, so this fires on the first write after each release
     * rather than once per connection.
     */
    private fun onPlaybackSinkFirstWrite() = updateLatency {
        if (it.releaseAt != null && it.firstPlaybackSinkWriteAt == null) {
            it.copy(firstPlaybackSinkWriteAt = now())
        } else {
            it
        }
    }

    private fun updateLatency(transform: (LatencyTrace) -> LatencyTrace) {
        val next = transform(mutableLatency.value)
        if (next != mutableLatency.value) {
            mutableLatency.value = next
            Log.i(TAG, "latency ${next.summary()}")
        }
    }

    private fun now() = monotonicClock()

    // --- transcript ---------------------------------------------------------------------------

    /** STT can arrive more than once per turn; the turn keeps a single user bubble. */
    private fun upsertUserMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val existing = currentUserMessageId
        if (existing == null) {
            val item = MessageItem(nextMessageId++, MessageAuthor.USER, trimmed, System.currentTimeMillis())
            currentUserMessageId = item.id
            mutableTranscript.value = mutableTranscript.value + item
            return
        }
        mutableTranscript.value = mutableTranscript.value.map {
            if (it.id == existing) it.copy(text = trimmed) else it
        }
    }

    /**
     * TTS text arrives per sentence. All of it belongs to one companion turn, so it accumulates into a
     * single bubble rather than producing one bubble per sentence.
     */
    private fun upsertCompanionMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val existing = currentCompanionMessageId
        if (existing == null) {
            val item = MessageItem(nextMessageId++, MessageAuthor.COMPANION, trimmed, System.currentTimeMillis())
            currentCompanionMessageId = item.id
            mutableTranscript.value = mutableTranscript.value + item
            return
        }
        mutableTranscript.value = mutableTranscript.value.map {
            if (it.id == existing) it.copy(text = (it.text + trimmed)) else it
        }
    }

    private companion object {
        const val TAG = "VoiceSession"
        const val GREETING = "今天也辛苦啦。"
    }

    init {
        // Single source of truth for the UI states: the coordinator's conversation machine. Placed last
        // in the class body so every field it touches is already initialised.
        scope.launch {
            coordinator.state.collect { snapshot ->
                mutableUiState.value = snapshot.conversation.toUiState()
            }
        }
    }
}
