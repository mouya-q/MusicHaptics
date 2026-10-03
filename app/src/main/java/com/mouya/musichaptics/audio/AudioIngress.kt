package com.mouya.musichaptics.audio

import android.os.Process
import android.util.Log
import com.mouya.musichaptics.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Bounded MPSC PCM ingress for hooked AudioTrack writes.
 *
 * The hook thread only normalises PCM into a fixed direct-buffer slot and publishes it.
 * Native DSP always runs on the dedicated worker, never on the application's audio writer.
 */
class AudioIngress(
    private val nativeBridge: NativeBridge,
    private val onTelemetry: (FloatArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "MusicHapticsX-Ingress"
        private const val MAX_BATCH_FRAMES = 256
        private const val QUEUE_CAPACITY = 16
        private const val TELEMETRY_SIZE = 32
    }

    private class Slot {
        val pcm = ByteBuffer
            .allocateDirect(MAX_BATCH_FRAMES * 4)
            .order(ByteOrder.nativeOrder())
        val mono: FloatBuffer = pcm.asFloatBuffer()
        // Scratch for the downmix: writing a plain heap array per sample is far
        // cheaper than per-sample virtual put() calls on a direct FloatBuffer,
        // and the final bulk put() compiles down to an intrinsic copy.
        val scratch = FloatArray(MAX_BATCH_FRAMES)
        val telemetry = FloatArray(TELEMETRY_SIZE)
        val readySequence = AtomicLong(0L)
        var frames = 0
    }

    private val slots = Array(QUEUE_CAPACITY) { Slot() }
    private val writeSequence = AtomicLong(0L)
    private val readSequence = AtomicLong(0L)
    private val wakeSignal = Semaphore(0)
    private val running = AtomicBoolean(true)
    private val worker = Thread(::runWorker, "MusicHapticsX-DSP")

    private val droppedFrameCount = AtomicLong(0L)

    init {
        // Thread.priority is a *Java* scheduling hint and only accepts 1..10.
        // Process.THREAD_PRIORITY_AUDIO (-16) is a Linux nice value for
        // android.os.Process.setThreadPriority and throws
        // IllegalArgumentException("Priority out of range") when assigned to it.
        // The DSP loop feeds native code from the same AudioFlinger thread, so the
        // worker has to be pinned at the OS level instead.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            .onSuccess { Log.i(TAG, "DSP worker pinned at THREAD_PRIORITY_AUDIO") }
            .onFailure { Log.w(TAG, "DSP worker priority pin failed: ${it.message}") }
        worker.priority = Thread.MAX_PRIORITY
        worker.start()
    }

    fun droppedFrames(): Long = droppedFrameCount.get()

    fun processPcm16(
        data: ShortArray,
        offsetSamples: Int,
        sampleCount: Int,
        channels: Int
    ): Int {
        val safeOffset = offsetSamples.coerceIn(0, data.size)
        val safeCount = min(sampleCount.coerceAtLeast(0), data.size - safeOffset)
        val frames = safeCount / channels.coerceAtLeast(1)
        if (!nativeBridge.isLoaded || frames <= 0 || channels <= 0) return 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val mono = slot.mono
            val scratch = slot.scratch
            var src = safeOffset + frameOffset * channels
            when (channels) {
                1 -> for (i in 0 until batch) { scratch[i] = data[src++].toFloat() / 32768f }
                2 -> for (i in 0 until batch) {
                    val left = data[src++].toInt()
                    val right = data[src++].toInt()
                    scratch[i] = (left + right) * (1f / 65536f)
                }
                else -> for (i in 0 until batch) {
                    var sum = 0f
                    repeat(channels) { sum += data[src++].toFloat() }
                    scratch[i] = sum / (channels * 32768f)
                }
            }
            mono.clear()
            mono.put(scratch, 0, batch)
            publish(sequence, slot, batch)
            frameOffset += batch
        }
        return frameOffset
    }

    fun processFloatPcm(
        data: FloatArray,
        offsetFrames: Int,
        frameCount: Int,
        channels: Int
    ): Int {
        val start = offsetFrames.coerceAtLeast(0)
        val ch = channels.coerceAtLeast(1)
        val maxFrames = ((data.size - start * ch).coerceAtLeast(0)) / ch
        val frames = min(frameCount.coerceAtLeast(0), maxFrames)
        if (!nativeBridge.isLoaded || frames <= 0 || channels <= 0) return 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val mono = slot.mono
            val scratch = slot.scratch
            var src = (start + frameOffset) * ch
            if (ch == 1) {
                for (i in 0 until batch) { scratch[i] = data[src++].coerceIn(-1f, 1f) }
            } else {
                for (i in 0 until batch) {
                    var sum = 0f
                    repeat(ch) { sum += data[src++] }
                    scratch[i] = (sum / ch).coerceIn(-1f, 1f)
                }
            }
            mono.clear()
            mono.put(scratch, 0, batch)
            publish(sequence, slot, batch)
            frameOffset += batch
        }
        return frameOffset
    }

    fun processPcm16Bytes(
        data: ByteArray,
        offsetBytes: Int,
        byteCount: Int,
        channels: Int
    ): Int {
        val safeOffset = offsetBytes.coerceIn(0, data.size)
        val available = min(byteCount.coerceAtLeast(0), data.size - safeOffset)
        val ch = channels.coerceAtLeast(1)
        val frames = (available / 2) / ch
        if (!nativeBridge.isLoaded || frames <= 0 || channels <= 0) return 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val mono = slot.mono
            val scratch = slot.scratch
            var p = safeOffset + frameOffset * ch * 2
            if (ch == 1) {
                for (i in 0 until batch) {
                    scratch[i] = readLeShort(data, p) / 32768f
                    p += 2
                }
            } else {
                for (i in 0 until batch) {
                    var sum = 0
                    repeat(ch) {
                        sum += readLeShort(data, p).toInt()
                        p += 2
                    }
                    scratch[i] = sum / (ch * 32768f)
                }
            }
            mono.clear()
            mono.put(scratch, 0, batch)
            publish(sequence, slot, batch)
            frameOffset += batch
        }
        return frameOffset
    }

    fun processPcm16Buffer(
        buffer: ByteBuffer,
        startPosition: Int,
        byteCount: Int,
        channels: Int
    ): Int {
        val ch = channels.coerceAtLeast(1)
        val start = startPosition.coerceIn(0, buffer.limit())
        val end = min(buffer.limit(), start + byteCount.coerceAtLeast(0))
        val sampleCount = (end - start) / 2
        val frames = sampleCount / ch
        if (!nativeBridge.isLoaded || frames <= 0 || channels <= 0) return 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val mono = slot.mono
            val scratch = slot.scratch
            var pos = start + frameOffset * ch * 2
            if (ch == 1) {
                for (i in 0 until batch) {
                    scratch[i] = buffer.getShort(pos).toFloat() / 32768f
                    pos += 2
                }
            } else {
                for (i in 0 until batch) {
                    var sum = 0
                    repeat(ch) {
                        sum += buffer.getShort(pos).toInt()
                        pos += 2
                    }
                    scratch[i] = sum / (ch * 32768f)
                }
            }
            mono.clear()
            mono.put(scratch, 0, batch)
            publish(sequence, slot, batch)
            frameOffset += batch
        }
        return frameOffset
    }

    fun shutdown() {
        if (!running.compareAndSet(true, false)) return
        wakeSignal.release()
        worker.interrupt()
        runCatching { worker.join(250L) }
    }

    private fun reserveSequence(batchFrames: Int): Long {
        while (running.get()) {
            val read = readSequence.get()
            val write = writeSequence.get()
            if (write - read >= QUEUE_CAPACITY) {
                droppedFrameCount.addAndGet(batchFrames.toLong())
                return -1L
            }
            if (writeSequence.compareAndSet(write, write + 1L)) {
                return write
            }
        }
        return -1L
    }

    private fun publish(sequence: Long, slot: Slot, frames: Int) {
        slot.frames = frames
        slot.readySequence.lazySet(sequence + 1L)
        wakeSignal.release()
    }

    private fun runWorker() {
        while (running.get() || readSequence.get() < writeSequence.get()) {
            var didWork = false
            while (true) {
                val sequence = readSequence.get()
                if (sequence >= writeSequence.get()) break
                val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
                if (slot.readySequence.get() != sequence + 1L) break

                try {
                    nativeBridge.processAudioDirect(slot.pcm, slot.frames, slot.telemetry)
                    onTelemetry(slot.telemetry, slot.frames)
                } catch (t: Throwable) {
                    Log.w(TAG, "Native DSP worker failed: ${t.message}")
                } finally {
                    readSequence.lazySet(sequence + 1L)
                    didWork = true
                }
            }

            if (!didWork && running.get()) {
                runCatching { wakeSignal.acquire() }
            }
        }
    }

    private fun readLeShort(data: ByteArray, index: Int): Short {
        val lo = data[index].toInt() and 0xFF
        val hi = data[index + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }

    private fun readLeShort(buffer: ByteBuffer, index: Int): Short {
        val lo = buffer.get(index).toInt() and 0xFF
        val hi = buffer.get(index + 1).toInt()
        return ((hi shl 8) or lo).toShort()
    }
}