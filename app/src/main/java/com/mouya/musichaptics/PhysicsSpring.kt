package com.mouya.musichaptics

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * 弹簧动画预设
 *
 * 本质是 Compose spring() 的参数预设管理器。
 * 通过低阻尼比 (0.25-0.62) 和低刚度 (200-400) 产生明显的过冲与振荡，
 * 实现"QQ弹弹"的物理手感。
 *
 * 注意：底层使用 Compose 内置 SpringSpec，未自定义积分器。
 * spring() 的 dampingRatio 和 stiffness 参数会真实生效，
 * dampingRatio < 1 会产生可见的过冲振荡。
 */
object PhysicsSpring {

    // ════════════════════════════════════════════════════════════════
    //  预设弹簧规格 — 每种都有独特的物理手感
    // ════════════════════════════════════════════════════════════════

    /** QQ弹弹 — Toggle、滑块拇指，强烈过冲后优雅回弹 */
    fun bouncy(): SpringSpec<Float> = spring(
        dampingRatio = 0.32f,   // 低阻尼 → 2-3次过冲振荡
        stiffness = 280f        // 中低刚度 → ~600-800ms完成
    )

    /** QQ弹弹 (Dp版本) */
    fun bouncyDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.32f,
        stiffness = 280f
    )

    /** 弹性选择 — SegmentedControl 指示器滑动 */
    fun elasticSelect(): SpringSpec<Float> = spring(
        dampingRatio = 0.42f,   // 中低阻尼 → 1次轻微过冲
        stiffness = 320f        // 中刚度 → ~500ms
    )

    /** 弹性选择 (Dp版本) */
    fun elasticSelectDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.42f,
        stiffness = 320f
    )

    /** 柔和弹跳 — 按钮按下/释放，有弹性但不夸张 */
    fun softBounce(): SpringSpec<Float> = spring(
        dampingRatio = 0.48f,   // 接近临界阻尼但仍有轻微过冲
        stiffness = 350f
    )

    /** 优雅展开 — 面板展开/收起，温和的弹性 */
    fun elegantExpand(): SpringSpec<Float> = spring(
        dampingRatio = 0.62f,   // 临界阻尼附近，1次微弱过冲
        stiffness = 200f        // 低刚度 → 慢而优雅，~700ms
    )

    /** 优雅展开 (Dp版本) */
    fun elegantExpandDp(): SpringSpec<androidx.compose.ui.unit.Dp> = spring(
        dampingRatio = 0.62f,
        stiffness = 200f
    )

    /** 波形振幅 — 频谱显示的物理响应 */
    fun waveformAmp(): SpringSpec<Float> = spring(
        dampingRatio = 0.25f,   // 很低阻尼 → 活跃的振荡
        stiffness = 400f        // 中等刚度 → 响应灵敏
    )

    /** 颜色渐变 — 带轻微弹性 */
    fun colorBounce(): SpringSpec<androidx.compose.ui.graphics.Color> = spring(
        dampingRatio = 0.55f,
        stiffness = 300f
    )
}

/**
 * 多阶段弹性按压动画控制器
 *
 * 实现真实的弹簧按压手感：
 * 1. 按下 → 快速压缩到 ~0.92 (硬弹簧)
 * 2. 释放 → 过冲到 ~1.06 (QQ弹弹)
 * 3. 回弹 → 振荡衰减到 1.0 (2-3次弹跳)
 */
@Composable
fun rememberBouncyPress(): BouncyPressController {
    val scope = rememberCoroutineScope()
    return remember { BouncyPressController(scope) }
}

class BouncyPressController(private val scope: kotlinx.coroutines.CoroutineScope) {

    /**
     * 执行完整的弹跳按压序列
     * @param scale Animatable 实例，初始值应为 1f
     */
    fun pressAndRelease(scale: Animatable<Float, *>) {
        scope.launch {
            // Phase 1: 快速压缩 (硬弹簧，无过冲)
            scale.animateTo(
                targetValue = 0.92f,
                animationSpec = spring(
                    dampingRatio = 1f,      // 临界阻尼 — 无过冲的快速压缩
                    stiffness = Spring.StiffnessHigh
                )
            )
            // Phase 2: QQ弹弹回弹 — 低阻尼弹簧，明显过冲
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.28f,   // 很低阻尼 → 2-3次弹跳
                    stiffness = 260f        // 低刚度 → 慢而弹
                )
            )
            // Phase 3 由 spring 自动完成振荡衰减
        }
    }

    /**
     * 只执行释放弹跳 (用于已经处理按下逻辑的场景)
     */
    fun release(scale: Animatable<Float, *>) {
        scope.launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = 0.28f,
                    stiffness = 260f
                )
            )
        }
    }

    /**
     * 只执行按下压缩
     */
    fun press(scale: Animatable<Float, *>) {
        scope.launch {
            scale.animateTo(
                targetValue = 0.92f,
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = Spring.StiffnessHigh
                )
            )
        }
    }
}