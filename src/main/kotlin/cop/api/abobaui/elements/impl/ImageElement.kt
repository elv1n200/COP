package cop.api.abobaui.elements.impl

import cop.api.abobaui.constraints.Constraints
import cop.api.abobaui.elements.Element
import cop.api.colour.Colour
import cop.utils.ui.data.Radii
import cop.utils.ui.rendering.Image
import cop.utils.ui.rendering.NVGRenderer

class ImageElement(
    private val image: Image,
    constraints: Constraints,
    colour: Colour?,
    radius: Radii?,
) : Element(constraints, colour) {

    private val radius: Radii = radius ?: Block.EMPTY_RADIUS

    init {
//        registerEvent(Lifetime.Initialised) {
//            NVGRenderer.createImage(image)
//            false
//        }
//        registerEvent(Lifetime.Uninitialised) {
//            NVGRenderer.deleteImage(image)
//            false
//        }
    }

    override fun drawNvg() {
        NVGRenderer.image(image, x, y, width, height, radius, colour?.rgb)
    }
}