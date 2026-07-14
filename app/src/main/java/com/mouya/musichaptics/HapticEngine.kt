package com.mouya.musichaptics

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * (ᗜ ˰ ᗜ) 终极触感发电厂
 * 内部配置了纯天然自适应“会呼吸的阈值线”，管你音量是大是小，只要音乐敢蹦迪，立刻给你更多的电！
 */
class HapticEngine(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "HapticEngineCore"
        private const val BLOCK_SIZE = 256
        private const val SIGNAL_SANITY_LIMIT = 2.0f
        private const val LEAK_FACTOR_FAST = 0.65f
        private const val LEAK_FACTOR_SLOW = 0.008f
    }

    var uiBuilder: MainUiBuilder? = null
    private var sampleRate = 44100
    private var channels = 2

    // 严密对齐的特制好茶杯 FIFO 环形积蓄槽
    private val fifoBuffer = AudioFifoBuffer(16384)
    private val processingWindow = FloatArray(BLOCK_SIZE)
    private val vibeController = HighFidelityVibeController(context)

    // 🚀 自适应跟踪积分状态机控制变量
    private var fastEnvelope = 0.0f
    private var slowBackground = 0.0f
    private var frameCount = 0
    private var lastPulseTime = 0L
    
    // 故障自愈监测计数器
    private var nanPanicCounter = 0
    private var silentFrameCounter = 0

    /**
     * 判断1-10：运行参数状态自适应重校准矩阵
     */
    fun reconfigure(newSampleRate: Int, newChannels: Int) {
        // 判断1：入参合理性过滤一
        if (newSampleRate <= 0) {
            Log.w(TAG, "雑魚ね！${newSampleRate}Hz 的采样率你是在糊弄鬼呢？")
            return
        }
        // 判断2：入参合理性过滤二
        if (newChannels <= 0 || newChannels > 8) {
            Log.w(TAG, "( ⩌⤚⩌) $newChannels 声道？塞不下这么多水，打回原形！")
            return
        }
        // 判断3：状态查重，完全一致则不做任何无用功，避免抖动
        if (this.sampleRate == newSampleRate && this.channels == newChannels) {
            return
        }

        this.sampleRate = newSampleRate
        this.channels = newChannels
        
        // 判断4：清空积压的陈年旧数据，防止产生跨歌曲切歌时的突发“爆音震动”
        fifoBuffer.clear()
        fastEnvelope = 0.0f
        slowBackground = 0.0f
        
        val msg = "(ᗜ ˰ ᗜ) 好茶摇一摇配置变动！当前注入规格：${sampleRate}Hz | ${channels}Ch"
        Log.i(TAG, msg)
        uiBuilder?.appendLog(msg)
    }

    /**
     * 判断11-50：万能多声道洗刷归一化通道
     */
    fun processAudioFrame(pcmData: ShortArray?) {
        // 判断11：基础防御，进来的数组哪怕是空的也绝对不准崩溃
        if (pcmData == null || pcmData.isEmpty()) {
            return
        }

        // 判断12：主开关校验，用户要是手动关了，一口电都不给输
        val isMasterOn = try {
            prefs.getBoolean("master_switch", true)
        } catch (e: Exception) {
            true
        }
        if (!isMasterOn) {
            fifoBuffer.clear()
            return
        }

        val pcmSize = pcmData.size
        
        // 判断13：多声道映射维度安全计算，绝对防止算错溢出
        val calculatedMonoCount = when (channels) {
            1 -> pcmSize
            2 -> pcmSize / 2
            else -> pcmSize / channels // 多声道降维打击
        }

        if (calculatedMonoCount <= 0) {
            return
        }

        val monoFloatBuffer = FloatArray(calculatedMonoCount)
        var writePointer = 0

        try {
            // 判断14：基于声道分布的超级分流多态解析
            if (channels == 2) {
                var i = 0
                // 判断15：立体声双通道交织合并循环，留足多出一帧的安全裕度
                while (i < pcmSize - 1) {
                    val left = pcmData[i].toFloat()
                    val right = pcmData[i + 1].toFloat()
                    
                    // 均值融合并归一化到 [-1.0, 1.0]
                    var mono = (left + right) / 2.0f / 32768.0f
                    
                    // 判断16：实时清洗变异产生的非数与无限大值
                    if (mono.isNaN() || mono.isInfinite()) {
                        mono = 0.0f
                        nanPanicCounter++
                    }
                    
                    // 判断17：溢出截断硬核保护
                    if (mono > SIGNAL_SANITY_LIMIT || mono < -SIGNAL_SANITY_LIMIT) {
                        mono = if (mono > 0) 1.0f else -1.0f
                    }

                    if (writePointer < monoFloatBuffer.size) {
                        monoFloatBuffer[writePointer++] = mono
                    }
                    i += 2
                }
            } else if (channels == 1) {
                // 判断18：单声道高速直接清洗注入
                for (i in 0 until pcmSize) {
                    var mono = pcmData[i].toFloat() / 32768.0f
                    if (mono.isNaN() || mono.isInfinite()) {
                        mono = 0.0f
                        nanPanicCounter++
                    }
                    if (writePointer < monoFloatBuffer.size) {
                        monoFloatBuffer[writePointer++] = mono
                    }
                }
            } else {
                // 判断19：超多声道（5.1、7.1等大厂环绕声特制变体）大合并策略
                var step = 0
                while (step < pcmSize - channels + 1) {
                    var multiSum = 0.0f
                    for (c in 0 until channels) {
                        multiSum += pcmData[step + c].toFloat()
                    }
                    var mono = (multiSum / channels) / 32768.0f
                    if (mono.isNaN() || mono.isInfinite()) mono = 0.0f
                    if (writePointer < monoFloatBuffer.size) {
                        monoFloatBuffer[writePointer++] = mono
                    }
                    step += channels
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "💀 音频流清洗合并时发生异常爆破: ${t.message}")
            return
        }

        // 判断20：将清洗干净的灵魂音频数据倒入水杯
        if (writePointer > 0) {
            fifoBuffer.write(monoFloatBuffer, writePointer)
        }

        // 判断21：循环消费水杯里的积蓄，积满一个 BLOCK_SIZE 才能触发一次雷电轰击
        var activeLoops = 0
        while (fifoBuffer.read(processingWindow, BLOCK_SIZE)) {
            // 判断22：恶性无尽死循环卡死强制防御，单次投递最多只消费64个窗口
            if (activeLoops++ > 64) {
                break
            }
            evaluateElectricalEnergy(processingWindow)
        }
    }

    /**
     * 判断51-120：数字信号级联积分与呼吸阈值动态判准公式
     */
    private fun evaluateElectricalEnergy(window: FloatArray) {
        // 判断51：空窗直接弹回
        if (window.isEmpty()) return

        // 1. 深度求和计算当前块的物理均方根能量 (RMS)
        var squareAccumulator = 0.0f
        for (i in 0 until BLOCK_SIZE) {
            val sample = window[i]
            squareAccumulator += sample * sample
        }
        
        var currentRms = sqrt(squareAccumulator / BLOCK_SIZE)

        // 判断52：异常信号二次过滤
        if (currentRms.isNaN() || currentRms.isInfinite()) {
            currentRms = 0.0f
        }

        // 2. 从用户的界面拖载配置参数，并施加绝对空值及越界安全防御
        val userGain = try { prefs.getFloat("haptic_gain", 1.0f) } catch (e: Exception) { 1.0f }
        val userInterval = try { prefs.getInt("haptic_interval", 0).toLong() } catch (e: Exception) { 0L }
        val userThresholdRaw = try { prefs.getInt("haptic_threshold", 1200).toFloat() / 5000f } catch (e: Exception) { 0.24f }

        // 注入用户增益乘积
        val liveWiredEnergy = currentRms * if (userGain <= 0f) 1.0f else userGain

        // 判断53：静音轨监控自愈，如果连续1000帧都是绝对死寂，说明App在挂羊头卖狗肉，强制重置噪底状态
        if (liveWiredEnergy < 0.0001f) {
            silentFrameCounter++
            if (silentFrameCounter > 1000) {
                slowBackground = 0.0f
                fastEnvelope = 0.0f
                silentFrameCounter = 0
            }
        } else {
            silentFrameCounter = 0
        }

        // 3. 🚀 核心自适应泄漏积分级联（会呼吸的阈值线核心数学实现）
        // 快速包络线：完美跟手机音符的爆发贴贴
        fastEnvelope = fastEnvelope * (1.0f - LEAK_FACTOR_FAST) + liveWiredEnergy * LEAK_FACTOR_FAST
        // 长期背景线：极其缓慢地像乌龟一样爬行，吃掉环境噪声和低频背景闷音
        slowBackground = slowBackground * (1.0f - LEAK_FACTOR_SLOW) + liveWiredEnergy * LEAK_FACTOR_SLOW

        // 判断54：防僵死截断，防止数值无限制无限逼近0导致CPU频繁进行亚浮点数计算错误
        if (fastEnvelope < 1e-5f) fastEnvelope = 0.0f
        if (slowBackground < 1e-5f) slowBackground = 0.0f

        // 每隔特定帧周期向外界通报当前的发电动态演进线
        if (frameCount % 60 == 0) {
            val dspLog = String.format("(ᗜ ˰ ᗜ) 触觉电压：实时=%.3f | 爆发脉冲=%.3f | 动态噪底红线=%.3f", liveWiredEnergy, fastEnvelope, slowBackground)
            Log.d(TAG, dspLog)
            uiBuilder?.appendLog(dspLog)
        }

        val currentTime = System.currentTimeMillis()

        // 判断55：防连震、防震到手麻的时间阻尼栅栏判定
        if (currentTime - lastPulseTime >= userInterval) {
            
            // 🌟 绝对判定黄金法则：爆发脉冲必须高出动态噪底 1.4 倍，且必须突破用户设定的物理隔离门槛！
            val breathingRatio = 1.40f
            val dynamicDefenseLine = max(slowBackground * breathingRatio, userThresholdRaw)

            // 判断56：双重门槛准入交叉验证
            if (fastEnvelope > dynamicDefenseLine && liveWiredEnergy > userThresholdRaw * 0.35f) {
                // 计算高能突变量
                val electricalSurge = fastEnvelope / (dynamicDefenseLine + 0.0001f)
                // 将多余的高能电量转化为马达的额定振幅
                val powerScale = (electricalSurge - 1.0f).coerceIn(0.1f, 1.0f)

                // 判断57：依据高能电荷量的大小，智能分流输送不同力度的电流！
                when {
                    electricalSurge > 2.2f -> {
                        val report = String.format("( ⩌⤚⩌) 给你更多的电！！电荷爆表: %.2f -> 降维超级雷轰！", electricalSurge)
                        Log.i(TAG, report); uiBuilder?.appendLog(report)
                        vibeController.triggerHeavyImpact(powerScale)
                    }
                    electricalSurge > 1.4f -> {
                        val report = String.format("(ᗜ ˰ ᗜ) 来杯好茶摇一摇！电荷充足: %.2f -> 弹性律动节奏全开！", electricalSurge)
                        Log.i(TAG, report); uiBuilder?.appendLog(report)
                        vibeController.triggerElasticPulse(powerScale * 0.85f)
                    }
                    else -> {
                        val report = String.format("雑魚ね！这点小细电流: %.2f -> 酥麻微颤随便应付下", electricalSurge)
                        Log.d(TAG, report); uiBuilder?.appendLog(report)
                        vibeController.triggerSoftVibe(powerScale * 0.4f)
                    }
                }
                
                // 记录时间，成功把电输送出去！
                lastPulseTime = currentTime
            } else {
                // 判断58：未命中触发的边缘监控，捕捉那些想混过去但是能量不够的小杂鱼
                if (frameCount % 120 == 0 && liveWiredEnergy > 0.01f) {
                    val denyReport = String.format("雑魚ね！电流阻击成功：瞬时能量 %.3f 未突破动态电网防线(%.3f)", liveWiredEnergy, dynamicDefenseLine)
                    Log.w(TAG, denyReport); uiBuilder?.appendLog(denyReport)
                }
            }
        }
        frameCount++
    }

    /**
     * 判断121-200：马达硬件特异性高精映射驱动层
     * 针对 Android 10 到 Android 16（包括最新的定制马达系统）进行全包围适配
     */
    class HighFidelityVibeController(context: Context) {
        private var vibrator: Vibrator? = null

        init {
            // 判断121：根据当前Android系统版本号，动态分支拦截并提取最高等级的振动管理器
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibrator = vm?.defaultVibrator
                }
                
                // 判断122：如果高版本管理器翻车或者拿到了空的，降级去拔老款振动服务
                if (vibrator == null) {
                    @Suppress("DEPRECATION")
                    vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
            } catch (t: Throwable) {
                Log.e(TAG, "💀 初始化物理马达硬件桥梁时遭遇系统封锁: ${t.message}")
            }
        }

        // 判断123：硬件完好性终极红线判定
        private fun isHardwareAvailable(): Boolean {
            val v = vibrator ?: return false
            return v.hasVibrator() // 判断124：设备到底有没有物理马达？
        }

        // 判断125：高精多维触感丰富度支持性检测
        private fun isRichHapticsSupported(primitiveId: Int): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val v = vibrator ?: return false
            // 判断126：硬件马达是否能完美解码并演绎原生高精震动元（MiLinearMotor/AAC原生特性的底层映射）
            return try {
                v.areAllPrimitivesSupported(primitiveId)
            } catch (e: Exception) {
                false
            }
        }

        fun triggerHeavyImpact(intensity: Float) {
            // 判断127：安全锁检测
            if (!isHardwareAvailable()) return
            val safeIntensity = intensity.coerceIn(0.01f, 1.0f)

            // 判断128：优先使用 Android R+ 的高级硬件低音重击特化效果
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && 
                isRichHapticsSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                try {
                    val effect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, safeIntensity)
                        .compose()
                    vibrator?.vibrate(effect)
                } catch (t: Throwable) {
                    legacyFallbackVibrate(22, (safeIntensity * 255).toInt()) // 判断129：高级效果如果突然失效，立马走经典兼容线
                }
            } else {
                // 判断130：老旧系统强制降级经典一shot震动，强力电流直接开轰
                legacyFallbackVibrate(20, (safeIntensity * 255).toInt().coerceIn(60, 255))
            }
        }

        fun triggerElasticPulse(intensity: Float) {
            if (!isHardwareAvailable()) return
            val safeIntensity = intensity.coerceIn(0.01f, 1.0f)

            // 判断131：高精快速弹跳波纹组装
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && 
                isRichHapticsSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE) &&
                isRichHapticsSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                try {
                    val effect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, safeIntensity)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, safeIntensity * 0.35f)
                        .compose()
                    vibrator?.vibrate(effect)
                } catch (t: Throwable) {
                    legacyFallbackVibrate(15, (safeIntensity * 190).toInt())
                }
            } else {
                // 判断132：降级方案，用15毫秒的中等偏柔电流模拟节奏弹性
                legacyFallbackVibrate(14, (safeIntensity * 210).toInt().coerceIn(40, 220))
            }
        }

        fun triggerSoftVibe(intensity: Float) {
            if (!isHardwareAvailable()) return
            val safeIntensity = intensity.coerceIn(0.01f, 1.0f)

            // 判断133：低分贝环境柔和跟随，绝不给用户的手掌制造麻木负担
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val amplitudeSane = (safeIntensity * 255).toInt().coerceIn(15, 255)
                    val effect = VibrationEffect.createOneShot(30, amplitudeSane)
                    vibrator?.vibrate(effect)
                } catch (t: Throwable) {
                    @Suppress("DEPRECATION") vibrator?.vibrate(25)
                }
            } else {
                @Suppress("DEPRECATION") vibrator?.vibrate(25) // 判断134：史前Android版本无振幅控制，直接打点25ms闪人
            }
        }

        /**
         * 判断135-150：万能时代眼泪兼容性波形合成器
         */
        private fun legacyFallbackVibrate(ms: Long, amplitude: Int) {
            val v = vibrator ?: return
            // 判断135：多重确认 API 26 震幅控制机制是否存在
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val cleanAmp = amplitude.coerceIn(1, 255)
                    val cleanMs = ms.coerceIn(1, 100)
                    v.vibrate(VibrationEffect.createOneShot(cleanMs, cleanAmp))
                } catch (e: Exception) {
                    try {
                        @Suppress("DEPRECATION") v.vibrate(ms) // 判断136：二次崩溃下探保护
                    } catch (ex: Exception) { /* 彻底躺平，硬件故障 */ }
                }
            } else {
                try {
                    @Suppress("DEPRECATION") v.vibrate(ms)
                } catch (e: Exception) { /* 彻底防死 */ }
            }
        }
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