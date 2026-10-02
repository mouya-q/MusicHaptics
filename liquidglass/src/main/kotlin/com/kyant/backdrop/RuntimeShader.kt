package com.kyant.backdrop

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.toArgb

/**
 * Platform-independent handle to an AGSL runtime shader.
 *
 * Upstream `com.kyant.backdrop` is a Compose Multiplatform module: this
 * interface is declared in `commonMain`, and only the factory functions are
 * `expect`/`actual`. This project vendors the Android side as a plain Android
 * library, so the interface must be declared here too.
 *
 * Note that AndroidX Compose has no `androidx.compose.ui.graphics.RuntimeShader`
 * type at all (verified against ui-graphics 1.7.8 and 1.8.2), which is why
 * importing one is a hard compile error.
 */
interface RuntimeShader {
    fun setFloatUniform(name: String, value: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float)
    fun setFloatUniform(name: String, values: FloatArray)
    fun setIntUniform(name: String, value: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int)
    fun setIntUniform(name: String, values: IntArray)
    fun setColorUniform(name: String, color: Color)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun RuntimeShader(shaderString: String): RuntimeShader {
    return AndroidRuntimeShader(android.graphics.RuntimeShader(shaderString))
}

fun RuntimeShader.asComposeShader(): Shader = asAndroidRuntimeShader()

fun RuntimeShader.asAndroidRuntimeShader(): android.graphics.RuntimeShader =
    (this as AndroidRuntimeShader).shader

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AndroidRuntimeShader(val shader: android.graphics.RuntimeShader) : RuntimeShader {
    override fun setFloatUniform(name: String, value: Float) = shader.setFloatUniform(name, value)
    override fun setFloatUniform(name: String, value1: Float, value2: Float) = shader.setFloatUniform(name, value1, value2)
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) = shader.setFloatUniform(name, value1, value2, value3)
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) = shader.setFloatUniform(name, value1, value2, value3, value4)
    override fun setFloatUniform(name: String, values: FloatArray) = shader.setFloatUniform(name, values)
    override fun setIntUniform(name: String, value: Int) = shader.setIntUniform(name, value)
    override fun setIntUniform(name: String, value1: Int, value2: Int) = shader.setIntUniform(name, value1, value2)
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) = shader.setIntUniform(name, value1, value2, value3)
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) = shader.setIntUniform(name, value1, value2, value3, value4)
    override fun setIntUniform(name: String, values: IntArray) = shader.setIntUniform(name, values)
    override fun setColorUniform(name: String, color: Color) = shader.setColorUniform(name, color.toArgb())
}