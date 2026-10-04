package com.mouya.musichaptics

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

object PhysicsSpring {

    

    fun uiStandard(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,  
        stiffness = 400f  
    )

    fun uiFast(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 800f  
    )

    fun uiFastDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 1.0f,
        stiffness = 800f
    )

    fun uiSlow(): SpringSpec<Float> = spring(
        dampingRatio = 0.9f,  
        stiffness = 250f  
    )


    fun bouncy(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,  
        stiffness = 500f  
    )

    fun bouncyDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 1.0f,
        stiffness = 500f
    )

    fun elasticSelect(): SpringSpec<Float> = spring(
        dampingRatio = 0.85f,  
        stiffness = 400f  
    )

    fun elasticSelectDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.85f,
        stiffness = 400f
    )

    fun softBounce(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,  
        stiffness = 450f  
    )

    fun elegantExpand(): SpringSpec<Float> = spring(
        dampingRatio = 0.9f,  
        stiffness = 250f  
    )

    fun elegantExpandDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.9f,
        stiffness = 250f
    )

    fun waveformAmp(): SpringSpec<Float> = spring(
        dampingRatio = 0.55f,  
        stiffness = 500f
    )

    fun colorBounce(): SpringSpec<androidx.compose.ui.graphics.Color> = spring(
        dampingRatio = 0.9f,  
        stiffness = 350f
    )
}

@Composable
fun rememberBouncyPress(): BouncyPressController {
    val scope = rememberCoroutineScope()
    return remember { BouncyPressController(scope) }
}

class BouncyPressController(private val scope: kotlinx.coroutines.CoroutineScope) {

    fun pressAndRelease(scale: Animatable<Float, *>) {
        scope.launch {
            scale.animateTo(
                targetValue = 0.97f,  
                animationSpec = spring(
                    dampingRatio = 1f,  
                    stiffness = Spring.StiffnessHigh
                )
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 1f,  
                    stiffness = 600f  
                )
            )
        }
    }

    fun release(scale: Animatable<Float, *>) {
        scope.launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = 600f
                )
            )
        }
    }

    fun press(scale: Animatable<Float, *>) {
        scope.launch {
            scale.animateTo(
                targetValue = 0.97f,  
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = Spring.StiffnessHigh
                )
            )
        }
    }
}

object HapticSpringMap {

    fun hapticForSpringName(name: String): HapticFeedbackEngine.HapticStyle = when (name) {
        "uiFast"        -> HapticFeedbackEngine.HapticStyle.IMPACT  
        "uiStandard"    -> HapticFeedbackEngine.HapticStyle.CONTINUOUS_HUM  
        "elasticSelect" -> HapticFeedbackEngine.HapticStyle.SELECTION  
        "bouncy"        -> HapticFeedbackEngine.HapticStyle.IMPACT  
        "softBounce"    -> HapticFeedbackEngine.HapticStyle.IMPACT  
        "release"       -> HapticFeedbackEngine.HapticStyle.KICK  
        else            -> HapticFeedbackEngine.HapticStyle.LIGHT_TICK
    }

    fun hapticForReducedMotion(original: HapticFeedbackEngine.HapticStyle): HapticFeedbackEngine.HapticStyle = when (original) {
        HapticFeedbackEngine.HapticStyle.KICK,
        HapticFeedbackEngine.HapticStyle.IMPACT -> HapticFeedbackEngine.HapticStyle.SOFT_TAP  
        HapticFeedbackEngine.HapticStyle.SELECTION,
        HapticFeedbackEngine.HapticStyle.LIGHT_TICK -> HapticFeedbackEngine.HapticStyle.NONE  
        HapticFeedbackEngine.HapticStyle.CONTINUOUS_HUM -> HapticFeedbackEngine.HapticStyle.NONE  
        else -> HapticFeedbackEngine.HapticStyle.NONE
    }
}