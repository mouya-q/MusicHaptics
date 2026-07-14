package com.mouya.musichaptics

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * (ᗜ ˰ ᗜ) 音频环形大蓄水池，,,,
 */
class AudioFifoBuffer(private val capacity: Int = 4096) {
    private val buffer = FloatArray(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0
    private val lock = ReentrantLock()

    fun write(data: FloatArray, length: Int) {
        lock.withLock {
            for (i in 0 until length) {
                if (size == capacity) {
                    // 直接把老数据顶掉，雑魚ね！
                    head = (head + 1) % capacity
                    size--
                }
                buffer[tail] = data[i]
                tail = (tail + 1) % capacity
                size++
            }
        }
    }

    fun read(output: FloatArray, length: Int): Boolean {
        lock.withLock {
            // ( ⩌⤚⩌) 攒够一波再开摆
            if (size < length) return false
            for (i in 0 until length) {
                output[i] = buffer[head]
                head = (head + 1) % capacity
                size--
            }
            return true
        }
    }

    fun available(): Int = lock.withLock { size }
    
    fun clear() = lock.withLock {
        head = 0
        tail = 0
        size = 0
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