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
        // frames. Its return value is API-dependent (FloatArray/ShortArray
        // return samples; byte-oriented overloads return bytes), so never infer
        // frame count from the raw return value without its overload metadata.
        // The queue is sized to retain a large write while the DSP worker drains
        // it, rather than silently truncating the tail:
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
        @Volatile var sampleRateHz: Int = 48000
        /** When frame 0 of this batch becomes audible (CLOCK_MONOTONIC ns), 0 = unknown. */
        @Volatile var audibleNs: Long = 0L
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
        channels: Int,
        sampleRateHz: Int = 48000,
        audibleNs: Long = 0L
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
            publish(sequence, slot, batch, sampleRateHz, batchAudibleNs(audibleNs, frameOffset, sampleRateHz))
            frameOffset += batch
        }
        return frameOffset
    }

    fun processFloatPcm(
        data: FloatArray,
        offsetFrames: Int,
        frameCount: Int,
        channels: Int,
        sampleRateHz: Int = 48000,
        audibleNs: Long = 0L
    ): Int {
        val start = offsetFrames.coerceAtLeast(0)
        val ch = channels.coerceAtLeast(1)
        val sampleStart = start.toLong() * ch.toLong()
        if (sampleStart >= data.size.toLong()) return 0
        val maxFrames = ((data.size.toLong() - sampleStart) / ch.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
                for (i in 0 until batch) {
                    val sample = data[src++]
                    scratch[i] = if (sample.isFinite()) sample.coerceIn(-1f, 1f) else 0f
                }
            } else {
                for (i in 0 until batch) {
                    var sum = 0f
                    repeat(ch) {
                        val sample = data[src++]
                        if (sample.isFinite()) sum += sample.coerceIn(-1f, 1f)
                    }
                    scratch[i] = (sum / ch).coerceIn(-1f, 1f)
                }
            }
            mono.clear()
            mono.put(scratch, 0, batch)
            publish(sequence, slot, batch, sampleRateHz, batchAudibleNs(audibleNs, frameOffset, sampleRateHz))
            frameOffset += batch
        }
        return frameOffset
    }

    fun processPcmBytes(
        data: ByteArray,
        offsetBytes: Int,
        byteCount: Int,
        channels: Int,
        encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT,
        sampleRateHz: Int = 48000,
        audibleNs: Long = 0L
    ): Int {
        val safeOffset = offsetBytes.coerceIn(0, data.size)
        val available = min(byteCount.coerceAtLeast(0), data.size - safeOffset)
        val ch = channels.coerceAtLeast(1)
        if (!nativeBridge.isLoaded || available <= 0 || channels <= 0) return 0

        val bytesPerSample = bytesPerSample(encoding) ?: run {
            logUnsupportedEncoding(encoding)
            return 0
        }
        val frames = available / bytesPerSample / ch
        if (frames <= 0) return 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val scratch = slot.scratch
            var p = safeOffset + frameOffset * ch * bytesPerSample
            for (i in 0 until batch) {
                var sum = 0f
                repeat(ch) {
                    sum += decodeSample(data, p, encoding)
                    p += bytesPerSample
                }
                scratch[i] = (sum / ch).coerceIn(-1f, 1f)
            }
            slot.mono.clear()
            slot.mono.put(scratch, 0, batch)
            publish(sequence, slot, batch, sampleRateHz, batchAudibleNs(audibleNs, frameOffset, sampleRateHz))
            frameOffset += batch
        }
        return frameOffset
    }

    fun processPcmBuffer(
        buffer: ByteBuffer,
        startPosition: Int,
        byteCount: Int,
        channels: Int,
        encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT,
        sampleRateHz: Int = 48000,
        audibleNs: Long = 0L
    ): Int {
        val ch = channels.coerceAtLeast(1)
        val start = startPosition.coerceIn(0, buffer.limit())
        val safeCount = byteCount.coerceAtLeast(0).coerceAtMost(buffer.limit() - start)
        if (!nativeBridge.isLoaded || safeCount <= 0 || channels <= 0) return 0

        val bytesPerSample = bytesPerSample(encoding) ?: run {
            logUnsupportedEncoding(encoding)
            return 0
        }
        val frames = safeCount / bytesPerSample / ch
        if (frames <= 0) return 0

        var frameOffset = 0
        while (frameOffset < frames) {
            val batch = min(MAX_BATCH_FRAMES, frames - frameOffset)
            val sequence = reserveSequence(batch)
            if (sequence < 0L) break
            val slot = slots[(sequence.toInt() and (QUEUE_CAPACITY - 1))]
            val scratch = slot.scratch
            var p = start + frameOffset * ch * bytesPerSample
            for (i in 0 until batch) {
                var sum = 0f
                repeat(ch) {
                    sum += decodeSample(buffer, p, encoding)
                    p += bytesPerSample
                }
                scratch[i] = (sum / ch).coerceIn(-1f, 1f)
            }
            slot.mono.clear()
            slot.mono.put(scratch, 0, batch)
            publish(sequence, slot, batch, sampleRateHz, batchAudibleNs(audibleNs, frameOffset, sampleRateHz))
            frameOffset += batch
        }
        return frameOffset
    }

    private fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        android.media.AudioFormat.ENCODING_PCM_32BIT,
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> null
    }

    @Volatile private var lastUnsupportedEncoding = Int.MIN_VALUE

    private fun logUnsupportedEncoding(encoding: Int) {
        if (lastUnsupportedEncoding != encoding) {
            lastUnsupportedEncoding = encoding
            Log.w(TAG, "Unsupported PCM encoding=$encoding; dropping input instead of decoding it as PCM16")
        }
    }

    private fun decodeSample(data: ByteArray, p: Int, encoding: Int): Float = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT ->
            ((data[p].toInt() and 0xFF) - 128) / 128f
        android.media.AudioFormat.ENCODING_PCM_16BIT ->
            readLeShort(data, p) / 32768f
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val raw = (data[p].toInt() and 0xFF) or
                ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16)
            val signed = if ((raw and 0x800000) != 0) raw or -0x1000000 else raw
            signed / 8388608f
        }
        android.media.AudioFormat.ENCODING_PCM_32BIT ->
            readLeInt(data, p) / 2147483648.0f
        android.media.AudioFormat.ENCODING_PCM_FLOAT ->
            readLeFloat(data, p).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
        else -> 0f
    }

    private fun decodeSample(buffer: ByteBuffer, p: Int, encoding: Int): Float = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT ->
            ((buffer.get(p).toInt() and 0xFF) - 128) / 128f
        android.media.AudioFormat.ENCODING_PCM_16BIT ->
            readLeShort(buffer, p) / 32768f
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val raw = (buffer.get(p).toInt() and 0xFF) or
                ((buffer.get(p + 1).toInt() and 0xFF) shl 8) or
                ((buffer.get(p + 2).toInt() and 0xFF) shl 16)
            val signed = if ((raw and 0x800000) != 0) raw or -0x1000000 else raw
            signed / 8388608f
        }
        android.media.AudioFormat.ENCODING_PCM_32BIT ->
            readLeInt(buffer, p) / 2147483648.0f
        android.media.AudioFormat.ENCODING_PCM_FLOAT ->
            readLeFloat(buffer, p).let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
        else -> 0f
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

    /** A write is split into batches; each starts later in the stream by its frame offset. */
    private fun batchAudibleNs(writeAudibleNs: Long, frameOffset: Int, sampleRateHz: Int): Long {
        if (writeAudibleNs <= 0L || sampleRateHz <= 0) return 0L
        return writeAudibleNs + frameOffset.toLong() * 1_000_000_000L / sampleRateHz
    }

    private fun publish(
        sequence: Long, slot: Slot, frames: Int, sampleRateHz: Int, audibleNs: Long
    ) {
        slot.frames = frames
        slot.audibleNs = audibleNs
        slot.sampleRateHz = sampleRateHz.coerceIn(8000, 384000)
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
                    nativeBridge.processAudioDirect(
                        slot.pcm, slot.frames, slot.telemetry, slot.sampleRateHz, slot.audibleNs
                    )
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

    private fun readLeInt(data: ByteArray, index: Int): Int =
        (data[index].toInt() and 0xFF) or
            ((data[index + 1].toInt() and 0xFF) shl 8) or
            ((data[index + 2].toInt() and 0xFF) shl 16) or
            (data[index + 3].toInt() shl 24)

    private fun readLeInt(buffer: ByteBuffer, index: Int): Int =
        (buffer.get(index).toInt() and 0xFF) or
            ((buffer.get(index + 1).toInt() and 0xFF) shl 8) or
            ((buffer.get(index + 2).toInt() and 0xFF) shl 16) or
            (buffer.get(index + 3).toInt() shl 24)

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