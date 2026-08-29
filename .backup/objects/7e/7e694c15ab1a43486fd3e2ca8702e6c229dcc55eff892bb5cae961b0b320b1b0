package com.mouya.musichaptics

import android.os.Build
import android.util.Log
import java.lang.reflect.Method

/**
 * v4.13: RichTap 硬件抽象层
 * 
 * 为小米/Redmi设备提供直接 RichTap API 访问，绕过 Android 标准 Vibrator 延迟。
 * 参考 14PRO_MusicHaptic 模块的 DynamicEffect/HapticPlayer 实现。
 * 
 * 支持厂商：
 * - Xiaomi/Redmi: HyperOS RichTap (DynamicEffect + HapticPlayer)
 * - OnePlus/OPPO: ColorOS haptic (待实现)
 * - vivo: OriginOS haptic (待实现)
 */
class RichTapAdapter {
    companion object {
        private const val TAG = "RichTapAdapter"
        
        // 动态加载 MIUI/HyperOS 私有类
        private var sDynamicEffectClass: Class<*>? = null
        private var sHapticPlayerClass: Class<*>? = null
        private var sCreateMethod: Method? = null
        private var sCreateContinuousMethod: Method? = null
        private var sCreateParameterMethod: Method? = null
        private var sAddPrimitiveMethod: Method? = null
        private var sHapticPlayerConstructor: java.lang.reflect.Constructor<*>? = null
        private var sStartMethod: Method? = null
        
        @Volatile private var sAvailable: Boolean? = null
        
        /**
         * 检测设备是否支持 RichTap API
         */
        fun isAvailable(): Boolean {
            sAvailable?.let { return it }
            
            // 仅小米/Redmi设备尝试加载
            val mfr = Build.MANUFACTURER.lowercase()
            if (mfr != "xiaomi" && mfr != "redmi") {
                sAvailable = false
                return false
            }
            
            return try {
                sDynamicEffectClass = Class.forName("android.os.DynamicEffect")
                sHapticPlayerClass = Class.forName("android.os.HapticPlayer")
                
                sCreateMethod = sDynamicEffectClass?.getDeclaredMethod("create")
                sCreateContinuousMethod = sDynamicEffectClass?.getDeclaredMethod(
                    "createContinuous", Float::class.java, Float::class.java, Float::class.java
                )
                sCreateParameterMethod = sDynamicEffectClass?.getDeclaredMethod(
                    "createParameter", Int::class.java, FloatArray::class.java, FloatArray::class.java
                )
                sAddPrimitiveMethod = sDynamicEffectClass?.getDeclaredMethod(
                    "addPrimitive", Float::class.java, 
                    Class.forName("android.os.DynamicEffect\$PrimitiveEffect")
                )
                sHapticPlayerConstructor = sHapticPlayerClass?.getDeclaredConstructor(
                    sDynamicEffectClass
                )
                sStartMethod = sHapticPlayerClass?.getDeclaredMethod("start")
                
                sAvailable = true
                Log.i(TAG, "RichTap API available on ${Build.MODEL}")
                true
            } catch (e: Exception) {
                sAvailable = false
                Log.w(TAG, "RichTap API not available: ${e.message}")
                false
            }
        }
        
        /**
         * 触发 RichTap 振动
         * 
         * @param amplitude 振幅 0.0-1.0
         * @param sharpness 锐度 0.0-1.0 (高=短促冲击, 低=柔和持续)
         * @param duration 持续时间 ms
         * @param attack 起音时间 s (0.001-0.01)
         */
        fun trigger(amplitude: Float, sharpness: Float, duration: Float, attack: Float): Boolean {
            if (!isAvailable()) return false
            
            return try {
                // DynamicEffect.create()
                val effect = sCreateMethod?.invoke(null)
                
                // DynamicEffect.createContinuous(amplitude, sharpness, duration)
                val primitive = sCreateContinuousMethod?.invoke(
                    null, amplitude, sharpness, duration
                )
                
                // createParameter(0, times[], amplitudes[])
                // 包络曲线: attack → decay
                val times = floatArrayOf(0f, attack, attack + duration * 0.7f, duration)
                val amplitudes = floatArrayOf(0f, amplitude, amplitude * 0.3f, 0f)
                val parameter = sCreateParameterMethod?.invoke(
                    null, 0, times, amplitudes
                )
                
                // primitive.addParameter(parameter)
                primitive?.javaClass?.getDeclaredMethod("addParameter", parameter?.javaClass)
                    ?.invoke(primitive, parameter)
                
                // effect.addPrimitive(0f, primitive)
                sAddPrimitiveMethod?.invoke(effect, 0f, primitive)
                
                // new HapticPlayer(effect).start()
                val player = sHapticPlayerConstructor?.newInstance(effect)
                sStartMethod?.invoke(player)
                
                true
            } catch (e: Exception) {
                Log.w(TAG, "RichTap trigger failed: ${e.message}")
                false
            }
        }
        
        /**
         * 获取最小间隔（ms）- 基于模式
         */
        fun getMinIntervalMs(mode: String): Long = when (mode) {
            "pure" -> 260L
            "crisp" -> 98L
            "immersive" -> 108L
            "bass" -> 128L
            "soft" -> 142L
            else -> 118L  // balanced
        }
    }
}