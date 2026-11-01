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


class AudioIngress(
    private val nativeBridge: NativeBridge,
    private val onTelemetry: (FloatArray, Int) -> Unit
) {
    companion object {
        private const val TAG = "MusicHapticsX-Ingress"
        // A single AudioTrack.write call can carry far more than a few hundred
        // frames: the kugou trace shows result=376832 bytes, which is 94208
        // stereo frames (~2s at 48kHz). With the old 256-frame batches and 16
        // slots the queue held 4096 frames, so 96% of every write was dropped
        // and the DSP saw a shredded signal. Onset detection works on
        // frame-to-frame spectral flux, so a 96% dropout makes it structurally
        // unable to trigger -- which is exactly the "no vibration" symptom.
        // Sized so one large write fits without dropping:
        //   4096 frames/batch * 64 slots = 262144 frames (~5.4s @ 48kHz)
        private const val MAX_BATCH_FRAMES = 4096
        private const val QUEUE_CAPACITY = 64
        private const val TELEMETRY_SIZE = 32
    }

    private class Slot {
        val pcm = ByteBuffer
            .allocateDirect(MAX_BATCH_FRAMES * 4)
            .order(ByteOrder.nativeOrder())
        val mono: FloatBuffer = pcm.asFloatBuffer()
        
        
        
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

    /** Frames actually handed to the native DSP. Compared against the frames
     *  accepted in [reserveSequence] to detect a stalled worker. */
    private val processedFrameCount = AtomicLong(0L)
    private val acceptedFrameCount = AtomicLong(0L)

    fun droppedFrames(): Long = droppedFrameCount.get()

    fun acceptedFrames(): Long = acceptedFrameCount.get()

    fun processedFrames(): Long = processedFrameCount.get()

    init {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            .onSuccess { Log.i(TAG, "DSP worker pinned at THREAD_PRIORITY_AUDIO") }
            .onFailure { Log.w(TAG, "DSP worker priority pin failed: ${it.message}") }
        worker.priority = Thread.MAX_PRIORITY
        worker.start()
    }

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

    fun processPcmBytes(
        data: ByteArray,
        offsetBytes: Int,
        byteCount: Int,
        channels: Int,
        encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT
    ): Int {
        val safeOffset = offsetBytes.coerceIn(0, data.size)
        val available = min(byteCount.coerceAtLeast(0), data.size - safeOffset)
        val ch = channels.coerceAtLeast(1)
        if (!nativeBridge.isLoaded || available <= 0 || channels <= 0) return 0

        // Mirror of the ByteBuffer dispatch: honour the track's real encoding so
        // float PCM is not decoded as int16 (which produces a noise spectrum and
        // therefore never triggers onset detection).
        val bytesPerSample = when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> 2
        }
        val frames = (available / bytesPerSample) / ch
        if (frames <= 0) return 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val scratch = slot.scratch
            var p = safeOffset + frameOffset * ch * bytesPerSample
            when (bytesPerSample) {
                4 -> {
                    if (ch == 1) {
                        for (i in 0 until batch) {
                            scratch[i] = readLeFloat(data, p).coerceIn(-1f, 1f); p += 4
                        }
                    } else {
                        for (i in 0 until batch) {
                            var sum = 0f
                            repeat(ch) { sum += readLeFloat(data, p); p += 4 }
                            scratch[i] = (sum / ch).coerceIn(-1f, 1f)
                        }
                    }
                }
                1 -> {
                    if (ch == 1) {
                        for (i in 0 until batch) {
                            scratch[i] = ((data[p++].toInt() and 0xFF) - 128) / 128f
                        }
                    } else {
                        for (i in 0 until batch) {
                            var sum = 0
                            repeat(ch) { sum += (data[p++].toInt() and 0xFF) - 128 }
                            scratch[i] = sum / (ch * 128f)
                        }
                    }
                }
                else -> {
                    if (ch == 1) {
                        for (i in 0 until batch) {
                            scratch[i] = readLeShort(data, p) / 32768f; p += 2
                        }
                    } else {
                        for (i in 0 until batch) {
                            var sum = 0
                            repeat(ch) { sum += readLeShort(data, p).toInt(); p += 2 }
                            scratch[i] = sum / (ch * 32768f)
                        }
                    }
                }
            }
            slot.mono.clear()
            slot.mono.put(scratch, 0, batch)
            publish(sequence, slot, batch)
            frameOffset += batch
        }
        return frameOffset
    }

    fun processPcmBuffer(
        buffer: ByteBuffer,
        startPosition: Int,
        byteCount: Int,
        channels: Int,
        encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT
    ): Int {
        val ch = channels.coerceAtLeast(1)
        val start = startPosition.coerceIn(0, buffer.limit())
        val end = min(buffer.limit(), start + byteCount.coerceAtLeast(0))
        val totalBytes = end - start
        if (!nativeBridge.isLoaded || totalBytes <= 0 || channels <= 0) return 0

        // Dispatch on the track's real encoding. Assuming 16-bit whenever a
        // player used float PCM turned samples into noise, so onset detection
        // never fired while the pipeline looked perfectly healthy.
        return when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT ->
                processFloatBytes(buffer, start, totalBytes, ch)
            8 -> // ENCODING_PCM_8BIT is 3; the literal keeps the branch readable
                process8BitBytes(buffer, start, totalBytes, ch)
            else ->
                process16BitBytes(buffer, start, totalBytes, ch)
        }
    }

    private fun process16BitBytes(
        buffer: ByteBuffer, start: Int, totalBytes: Int, ch: Int
    ): Int {
        val frames = (totalBytes / 2) / ch
        if (frames <= 0) return 0
        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val scratch = slot.scratch
            var pos = start + frameOffset * ch * 2
            if (ch == 1) {
                for (i in 0 until batch) {
                    scratch[i] = readLeShort(buffer, pos) / 32768f
                    pos += 2
                }
            } else {
                for (i in 0 until batch) {
                    var sum = 0
                    repeat(ch) {
                        sum += readLeShort(buffer, pos).toInt()
                        pos += 2
                    }
                    scratch[i] = sum / (ch * 32768f)
                }
            }
            slot.mono.clear()
            slot.mono.put(scratch, 0, batch)
            publish(sequence, slot, batch)
            frameOffset += batch
        }
        return frameOffset
    }

    private fun processFloatBytes(
        buffer: ByteBuffer, start: Int, totalBytes: Int, ch: Int
    ): Int {
        // ENCODING_PCM_FLOAT: 32-bit IEEE-754 little-endian, nominal range -1..1
        val frames = (totalBytes / 4) / ch
        if (frames <= 0) return 0
        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val scratch = slot.scratch
            var pos = start + frameOffset * ch * 4
            if (ch == 1) {
                for (i in 0 until batch) {
                    scratch[i] = readLeFloat(buffer, pos).coerceIn(-1f, 1f)
                    pos += 4
                }
            } else {
                for (i in 0 until batch) {
                    var sum = 0f
                    repeat(ch) {
                        sum += readLeFloat(buffer, pos)
                        pos += 4
                    }
                    scratch[i] = (sum / ch).coerceIn(-1f, 1f)
                }
            }
            slot.mono.clear()
            slot.mono.put(scratch, 0, batch)
            publish(sequence, slot, batch)
            frameOffset += batch
        }
        return frameOffset
    }

    private fun process8BitBytes(
        buffer: ByteBuffer, start: Int, totalBytes: Int, ch: Int
    ): Int {
        // ENCODING_PCM_8BIT: unsigned 0..255 centred on 128
        val frames = totalBytes / ch
        if (frames <= 0) return 0
        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val scratch = slot.scratch
            var pos = start + frameOffset * ch
            if (ch == 1) {
                for (i in 0 until batch) {
                    scratch[i] = ((buffer.get(pos++).toInt() and 0xFF) - 128) / 128f
                }
            } else {
                for (i in 0 until batch) {
                    var sum = 0
                    repeat(ch) {
                        sum += (buffer.get(pos++).toInt() and 0xFF) - 128
                    }
                    scratch[i] = sum / (ch * 128f)
                }
            }
            slot.mono.clear()
            slot.mono.put(scratch, 0, batch)
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
                acceptedFrameCount.addAndGet(batchFrames.toLong())
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
                    processedFrameCount.addAndGet(slot.frames.toLong())
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

    /** Little-endian IEEE-754 single precision, assembled by hand so the
     *  caller's buffer byte order does not have to be trusted. */
    private fun readLeFloat(data: ByteArray, index: Int): Float {
        val bits = (data[index].toInt() and 0xFF) or
            ((data[index + 1].toInt() and 0xFF) shl 8) or
            ((data[index + 2].toInt() and 0xFF) shl 16) or
            ((data[index + 3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun readLeFloat(buffer: ByteBuffer, index: Int): Float {
        val bits = (buffer.get(index).toInt() and 0xFF) or
            ((buffer.get(index + 1).toInt() and 0xFF) shl 8) or
            ((buffer.get(index + 2).toInt() and 0xFF) shl 16) or
            ((buffer.get(index + 3).toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }
}