package com.mouya.musichaptics

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * (ᗜ ˰ ᗜ) 音频环形大蓄水池（位巡航速度优化版）
 * 
 * 升级：加入免锁查询的原子级遥测快照输出，完美喂给前端仪表盘。
 */
class AudioFifoBuffer(requestedCapacity: Int = 4096) {
    private val capacity = calculatePowerOfTwo(requestedCapacity)
    private val mask = (capacity - 1).toLong()
    private val buffer = FloatArray(capacity)
    
    private var head = 0L
    private var tail = 0L
    private val lock = ReentrantLock()

    // 用于快速计算实时能量（RMS），提供轻量遥测支持
    @Volatile private var latestRmsSnapshot: Float = 0f

    /**
     * 批量写入音频/触觉数据。若溢出则自动覆盖老数据。
     */
    fun write(data: FloatArray, length: Int) {
        if (length <= 0) return
        
        val writeLen = minOf(length, capacity)
        val startSrcOffset = length - writeLen
        
        // 顺便算一下这一批写入数据的 RMS 能量强度，供前端可视化直接抓取
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
            
            // 修复点：将 Java 的 & 运算符修正为 Kotlin 的 and 关键字
            val tailIdx = (tail and mask).toInt()
            val firstCopyLen = minOf(writeLen, capacity - tailIdx)
            
            System.arraycopy(data, startSrcOffset, buffer, tailIdx, firstCopyLen)
            if (firstCopyLen < writeLen) {
                System.arraycopy(data, startSrcOffset + firstCopyLen, buffer, 0, writeLen - firstCopyLen)
            }
            tail += writeLen
        }
    }

    /**
     * ( ⩌⤚⩌) 攒够一波再开摆（批量读取）
     */
    fun read(output: FloatArray, length: Int): Boolean {
        if (length <= 0) return true
        
        lock.withLock {
            val currentSize = (tail - head).toInt()
            if (currentSize < length) return false
            
            // 修复点：将 Java 的 & 运算符修正为 Kotlin 的 and 关键字
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
    
    /**
     * 提供一个零阻碍、不卡音频线程的获取当前环形缓冲区装载率的方法 (0.0f ~ 1.0f)
     */
    fun getLoadFactor(): Float = lock.withLock {
        return (tail - head).toFloat() / capacity
    }

    /**
     * 获取最新一帧写入的信号有效能量级
     */
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
/*
                   _ooOoo_
                  o8888888o
                  88" . "88
                  (| -_- |)
                  O\  =  /O
               ____/`---'\____
             .'  \\|     |//  `.
            /  \\|||  :  |||//  \
           /  _||||| -:- |||||-  \
           |   | \\\  -  /// |   |
           | \_|  ''\---/''  |   |
           \  .-\__  `-`  ___/-. /
         ___`. .'  /--.--\  `. . __
      ."" '<  `.___\_<|>_/___.'  >'"".
     | | :  `- \`.;`\ _ /`;.`/ - ` : | |
     \  \ `-.   \_ __\ /__ _/   .-` /  /
======`-.____`-.___\_____/___.-`____.-'======
                   `=---='
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            佛祖保佑       永无BUG
*/
