package com.mouya.musichaptics

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

object PhysicsSpring {

    //  v3.14 iOS-grade 令牌 — 统一弹簧语言

    fun uiStandard(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,  // 临界阻尼 — 无过冲
        stiffness = 400f  // ~250ms 完成
    )

    fun uiFast(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,
        stiffness = 800f  // ~150ms
    )

    fun uiFastDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 1.0f,
        stiffness = 800f
    )

    fun uiSlow(): SpringSpec<Float> = spring(
        dampingRatio = 0.9f,  // 接近临界，1次极微过冲
        stiffness = 250f  // ~400ms
    )


    fun bouncy(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,  // 临界阻尼 — 无过冲
        stiffness = 500f  // ~200ms
    )

    fun bouncyDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 1.0f,
        stiffness = 500f
    )

    fun elasticSelect(): SpringSpec<Float> = spring(
        dampingRatio = 0.85f,  // 近临界 → 1次极微过冲
        stiffness = 400f  // ~300ms
    )

    fun elasticSelectDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.85f,
        stiffness = 400f
    )

    fun softBounce(): SpringSpec<Float> = spring(
        dampingRatio = 1.0f,  // 临界阻尼 — 无过冲
        stiffness = 450f  // ~220ms
    )

    fun elegantExpand(): SpringSpec<Float> = spring(
        dampingRatio = 0.9f,  // 近临界，1次微弱过冲
        stiffness = 250f  // ~400ms
    )

    fun elegantExpandDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.9f,
        stiffness = 250f
    )

    fun waveformAmp(): SpringSpec<Float> = spring(
        dampingRatio = 0.55f,  // 中低阻尼 → 活跃响应（频谱显示需要灵敏度）
        stiffness = 500f
    )

    fun colorBounce(): SpringSpec<androidx.compose.ui.graphics.Color> = spring(
        dampingRatio = 0.9f,  // 近临界
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
                targetValue = 0.97f,  // v3.14: subtle press, was 0.92
                animationSpec = spring(
                    dampingRatio = 1f,  // 临界阻尼 — 无过冲
                    stiffness = Spring.StiffnessHigh
                )
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 1f,  // 临界阻尼 — 干净回弹
                    stiffness = 600f  // ~180ms
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
                targetValue = 0.97f,  // v3.14: subtle press
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
        "uiFast"        -> HapticFeedbackEngine.HapticStyle.IMPACT  // 150ms press
        "uiStandard"    -> HapticFeedbackEngine.HapticStyle.CONTINUOUS_HUM  // 300ms toggle/slider
        "elasticSelect" -> HapticFeedbackEngine.HapticStyle.SELECTION  // segmented commit
        "bouncy"        -> HapticFeedbackEngine.HapticStyle.IMPACT  // toggle commit
        "softBounce"    -> HapticFeedbackEngine.HapticStyle.IMPACT  // button press
        "release"       -> HapticFeedbackEngine.HapticStyle.KICK  // button release
        else            -> HapticFeedbackEngine.HapticStyle.LIGHT_TICK
    }

    fun hapticForReducedMotion(original: HapticFeedbackEngine.HapticStyle): HapticFeedbackEngine.HapticStyle = when (original) {
        HapticFeedbackEngine.HapticStyle.KICK,
        HapticFeedbackEngine.HapticStyle.IMPACT -> HapticFeedbackEngine.HapticStyle.SOFT_TAP  // functional → soft
        HapticFeedbackEngine.HapticStyle.SELECTION,
        HapticFeedbackEngine.HapticStyle.LIGHT_TICK -> HapticFeedbackEngine.HapticStyle.NONE  // decorative → none
        HapticFeedbackEngine.HapticStyle.CONTINUOUS_HUM -> HapticFeedbackEngine.HapticStyle.NONE  // decorative → none
        else -> HapticFeedbackEngine.HapticStyle.NONE
    }
}