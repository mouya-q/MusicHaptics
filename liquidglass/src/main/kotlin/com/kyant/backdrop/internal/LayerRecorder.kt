package com.kyant.backdrop.internal

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize

/**
 * Records [block] into [layer], re-scoping the draw density to the density of
 * the owning [node].
 *
 * Upstream writes this with Kotlin context parameters
 * (`context(node: DelegatableNode)`), which needs the `context-parameters`
 * language feature (Kotlin 2.2+). This project is pinned to Kotlin 2.0.21 where
 * that syntax is a hard parse error, so the receiver is passed explicitly.
 */
internal fun DrawScope.recordLayer(
    node: DelegatableNode,
    layer: GraphicsLayer,
    size: IntSize = this.size.toIntSize(),
    block: DrawScope.() -> Unit
) {
    val density = node.requireDensity()
    layer.record(size) {
        val prevDensity = drawContext.density
        drawContext.density = density
        try {
            this.block()
        } finally {
            drawContext.density = prevDensity
        }
    }
}