package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.internal.recordLayer

fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier =
    this then LayerBackdropElement(backdrop)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false

        return true
    }

    override fun hashCode(): Int {
        return backdrop.hashCode()
    }
}

private class LayerBackdropNode(
    var backdrop: LayerBackdrop
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    /**
     * Guards against re-entrant recording.
     *
     * If a node carrying [Modifier.layerBackdrop] also contains the glass
     * children that consume this very backdrop, then recording the layer draws
     * those children, and each of them calls back into
     * [LayerBackdrop.drawBackdrop] to read the layer that is still being
     * recorded. The cycle makes the RenderNode reference itself, and
     * RenderNode::prepareTreeImpl recurses into
     * SkiaDisplayList::prepareListAndChildren and back with no base case,
     * overflowing the RenderThread stack (SIGSEGV, "stack pointer is not in a
     * rw map").
     *
     * Detecting the recursion and skipping the nested record degrades to
     * "no extra backdrop content" instead of a native crash.
     */
    private var isRecording = false

    override fun ContentDrawScope.draw() {
        if (isRecording) return

        drawContent()
        isRecording = true
        try {
            recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) { backdrop.onDraw(this@draw) }
        } finally {
            isRecording = false
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            backdrop.layerCoordinates = coordinates
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
