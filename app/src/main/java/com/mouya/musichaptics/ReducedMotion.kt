package com.mouya.musichaptics

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext


val LocalPrefersReducedMotion = staticCompositionLocalOf { false }

@Composable
fun rememberPrefersReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val resolver = context.contentResolver
            val scale = android.provider.Settings.Global.getFloat(
                resolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        } catch (e: Exception) {
            false
        }
    }
}

@Composable
fun ReducedMotionProvider(content: @Composable () -> Unit) {
    val reduced = rememberPrefersReducedMotion()
    CompositionLocalProvider(LocalPrefersReducedMotion provides reduced) {
        content()
    }
}

@Composable
fun <T> reducedMotionSpec(normalSpec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> {
    return if (LocalPrefersReducedMotion.current) {
        snap()
    } else {
        normalSpec
    }
}