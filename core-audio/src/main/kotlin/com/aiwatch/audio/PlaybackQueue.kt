package com.aiwatch.audio

import java.io.Closeable
import java.util.ArrayDeque

/** write must be nonblocking; implementations return the number of interleaved PCM samples written. */
interface PcmPlaybackSink : Closeable {
    val underrunCount: Int get() = 0
    fun write(pcm: ShortArray, offset: Int, length: Int): Int
    fun pauseAndFlush()
}

data class PlaybackMetrics(val maxEncodedDepth: Int, val maxPcmDepth: Int,
    val partialWrites: Long, val overloads: Long, val underruns: Int, val writtenSamples: Long)

/**
 * One worker calls pump(). Producers may offer/end/flush concurrently.
 * Sink write and pause/flush share a lock: when flush returns no old write can follow it.
 * Decode stays outside this lock; its generation is rechecked before accepting decoded PCM.
 */
class PlaybackQueue(
    private val decode: (ByteArray) -> ShortArray,
    private val sink: PcmPlaybackSink,
    private val maxEncodedPackets: Int = 8,
    private val maxPcmFrames: Int = 2,
    /**
     * Latency instrumentation only: fired once, on the first sink write that actually accepted samples.
     * This is the earliest moment audio can become audible, so it is what `t_release -> first audible
     * sample` is measured against. Invoked outside the queue lock.
     */
    private val onFirstSinkWrite: (() -> Unit)? = null,
) : Closeable {
    init { require(maxEncodedPackets > 0 && maxPcmFrames > 0) }
    private data class Pcm(val data: ShortArray, var offset: Int = 0)
    private val lock = Any()
    private val encoded = ArrayDeque<ByteArray>()
    private val pcm = ArrayDeque<Pcm>()
    private var epoch = 0L
    private var generation = -1L
    private var accepting = false
    private var closed = false
    private var decoding = false
    private var encodedPeak = 0
    private var pcmPeak = 0
    private var partialWrites = 0L
    private var overloads = 0L
    private var writtenSamples = 0L
    private var firstWriteReported = false
    val metrics: PlaybackMetrics get() = synchronized(lock) {
        PlaybackMetrics(encodedPeak, pcmPeak, partialWrites, overloads, if (closed) 0 else sink.underrunCount, writtenSamples)
    }
    val encodedDepth: Int get() = synchronized(lock) { encoded.size }
    val pcmDepth: Int get() = synchronized(lock) { pcm.size }
    val idle: Boolean get() = synchronized(lock) { !decoding && encoded.isEmpty() && pcm.isEmpty() }

    fun begin(generation: Long) = synchronized(lock) {
        check(!closed)
        flushLocked()
        // begin() is the new playback-turn boundary, so per-turn first-write instrumentation restarts
        // here. Deliberately NOT inside flushLocked(): a flush also happens on interrupt and reconnect,
        // and those are not new turns - which is why the interrupt path can pauseAndFlush several times.
        firstWriteReported = false
        this.generation = generation
        accepting = true
    }

    /** False indicates closed gate, stale generation or backpressure; caller must not silently drop. */
    fun offer(packet: ByteArray, generation: Long): Boolean = synchronized(lock) {
        if (closed || !accepting || generation != this.generation ||
            packet.isEmpty() || packet.size > 65536) return@synchronized false
        if (encoded.size >= maxEncodedPackets) { overloads++; return@synchronized false }
        encoded.addLast(packet.copyOf())
        encodedPeak = maxOf(encodedPeak, encoded.size)
        true
    }

    /** TTS stop closes network acceptance but drains already accepted audio. */
    fun end(generation: Long) = synchronized(lock) {
        if (generation == this.generation) accepting = false
    }

    fun flush() = synchronized(lock) { if (!closed) flushLocked() }
    private fun flushLocked() {
        epoch++; accepting = false; encoded.clear(); pcm.clear()
        sink.pauseAndFlush()
    }

    /** Returns promptly even if the device buffer is full. Never call concurrently from two workers. */
    fun pump() {
        val job = synchronized(lock) {
            if (closed) return
            check(!decoding) { "PlaybackQueue requires one decode worker" }
            if (pcm.size < maxPcmFrames && encoded.isNotEmpty()) {
                decoding = true
                epoch to encoded.removeFirst()
            } else null
        }
        if (job != null) {
            try {
                val decoded = decode(job.second)
                synchronized(lock) {
                    if (!closed && job.first == epoch) {
                        pcm.addLast(Pcm(decoded)); pcmPeak = maxOf(pcmPeak, pcm.size)
                    }
                }
            } finally { synchronized(lock) { decoding = false } }
        }
        var reportFirstWrite = false
        synchronized(lock) {
            if (closed || pcm.isEmpty()) return
            val head = pcm.first
            val remaining = head.data.size - head.offset
            val written = sink.write(head.data, head.offset, remaining)
            check(written in 0..remaining) { "Playback write failed" }
            if (written < remaining) partialWrites++
            writtenSamples += written
            head.offset += written
            if (head.offset == head.data.size) pcm.removeFirst()
            if (written > 0 && !firstWriteReported) {
                firstWriteReported = true
                reportFirstWrite = true
            }
        }
        // Outside the lock: the callback only records a timestamp, but it must not be able to re-enter.
        if (reportFirstWrite) onFirstSinkWrite?.invoke()
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            try { flushLocked() } finally { closed = true; sink.close() }
        }
    }
}
