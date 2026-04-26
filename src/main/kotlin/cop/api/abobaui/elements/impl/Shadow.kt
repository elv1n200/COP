package cop.api.abobaui.elements.impl

import cop.api.abobaui.constraints.Constraints
import cop.api.abobaui.elements.Element
import cop.api.colour.Colour
import cop.utils.ui.rendering.NVGRenderer
import cop.utils.ui.data.Radii

class Shadow(
    constraints: Constraints,
    colour: Colour,
    private val blur: Float,
    private val spread: Float,
    private val offsetX: Float,
    private val offsetY: Float,
    radii: Radii?,
) : Element(constraints, colour) {

    private val radii = radii ?: Block.EMPTY_RADIUS

    override fun drawNvg() {
        NVGRenderer.dropShadow(x + offsetX, y + offsetY, width, height, blur, spread, radii, colour!!.rgb)
    }
}