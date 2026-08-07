package com.mouya.musichaptics

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

class AudioFifoBuffer(requestedCapacity: Int = 4096) {
    private val capacity = calculatePowerOfTwo(requestedCapacity)
    private val mask = (capacity - 1).toLong()
    private val buffer = FloatArray(capacity)

    private var head = 0L
    private var tail = 0L
    private val lock = ReentrantLock()

    @Volatile private var latestRmsSnapshot: Float = 0f

    fun write(data: FloatArray, length: Int) {
        if (length <= 0) return

        val writeLen = minOf(length, capacity)
        val startSrcOffset = length - writeLen

        var sumOfSquares = 0f
        for (i in startSrcOffset until length) {
            sumOfSquares += data[i] * data[i]
        }
        latestRmsSnapshot = if (writeLen > 0) sqrt(sumOfSquares / writeLen) else 0f

        lock.withLock {
            val currentSize = (tail - head).toInt()
            val overflow = (currentSize + writeLen) - capacity
            if (overflow > 0) {
                head += overflow
            }

            val tailIdx = (tail and mask).toInt()
            val firstCopyLen = minOf(writeLen, capacity - tailIdx)

            System.arraycopy(data, startSrcOffset, buffer, tailIdx, firstCopyLen)
            if (firstCopyLen < writeLen) {
                System.arraycopy(data, startSrcOffset + firstCopyLen, buffer, 0, writeLen - firstCopyLen)
            }
            tail += writeLen
        }
    }

    fun read(output: FloatArray, length: Int): Boolean {
        if (length <= 0) return true

        lock.withLock {
            val currentSize = (tail - head).toInt()
            if (currentSize < length) return false

            val headIdx = (head and mask).toInt()
            val firstCopyLen = minOf(length, capacity - headIdx)

            System.arraycopy(buffer, headIdx, output, 0, firstCopyLen)
            if (firstCopyLen < length) {
                System.arraycopy(buffer, 0, output, firstCopyLen, length - firstCopyLen)
            }
            head += length
            return true
        }
    }

    fun available(): Int = lock.withLock { (tail - head).toInt() }

    fun getLoadFactor(): Float = lock.withLock {
        return (tail - head).toFloat() / capacity
    }

    fun getLatestRms(): Float = latestRmsSnapshot

    fun clear() = lock.withLock {
        head = 0L
        tail = 0L
        latestRmsSnapshot = 0f
    }

    private fun calculatePowerOfTwo(value: Int): Int {
        if (value <= 1) return 1
        return Integer.highestOneBit(value - 1) shl 1
    }
}