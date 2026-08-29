package com.mouya.musichaptics

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free SPSC (Single Producer Single Consumer) ring buffer for PCM frames.
 * Producer: AudioTrack.write() hook (real-time audio thread)
 * Consumer: DspWorkerThread (dedicated high-priority thread)
 * 
 * Zero allocation on write path - writes directly into pre-allocated buffer.
 */
class PcmFifo(capacityFrames: Int = 16384) {  // ~340ms @ 48kHz mono
    private val capacity = calculatePowerOfTwo(capacityFrames)
    private val mask = (capacity - 1).toLong()
    private val buffer = FloatArray(capacity)

    // Atomic indices for lock-free SPSC
    private val writeIndex = AtomicLong(0)
    private val readIndex = AtomicLong(0)

    /** Write PCM frames (mono, normalized -1..1). Returns frames actually written. */
    fun write(frames: FloatArray, offset: Int, length: Int): Int {
        var writeLen = minOf(length, capacity) // Allow writing up to full capacity
        val available = availableToWrite()
        
        // If there is an overflow, advance the read pointer (Drop-oldest strategy)
        if (writeLen > available) {
            val overflow = writeLen - available
            readIndex.addAndGet(overflow.toLong())
        }

        var idx = (writeIndex.get() and mask).toInt()
        val firstCopy = minOf(writeLen, capacity - idx)
        
        // Direct copy into ring buffer - no allocation
        System.arraycopy(frames, offset + (length - writeLen), buffer, idx, firstCopy)
        if (firstCopy < writeLen) {
            System.arraycopy(frames, offset + (length - writeLen) + firstCopy, buffer, 0, writeLen - firstCopy)
        }
        
        writeIndex.addAndGet(writeLen.toLong())
        return writeLen
    }

    /** Read PCM frames into output array. Returns frames actually read. */
    fun read(output: FloatArray, length: Int): Int {
        val readLen = minOf(length, availableToRead())
        if (readLen <= 0) return 0

        var idx = (readIndex.get() and mask).toInt()
        val firstCopy = minOf(readLen, capacity - idx)
        
        System.arraycopy(buffer, idx, output, 0, firstCopy)
        if (firstCopy < readLen) {
            System.arraycopy(buffer, 0, output, firstCopy, readLen - firstCopy)
        }
        
        readIndex.addAndGet(readLen.toLong())
        return readLen
    }

    /** Frames available for reading */
    fun availableToRead(): Int = (writeIndex.get() - readIndex.get()).toInt()

    /** Frames available for writing (free space) */
    fun availableToWrite(): Int = capacity - availableToRead()

    /** Current fill level 0.0..1.0 */
    fun loadFactor(): Float = availableToRead().toFloat() / capacity

    fun clear() {
        writeIndex.set(0)
        readIndex.set(0)
    }

    private fun calculatePowerOfTwo(value: Int): Int {
        if (value <= 1) return 1
        return Integer.highestOneBit(value - 1) shl 1
    }
}