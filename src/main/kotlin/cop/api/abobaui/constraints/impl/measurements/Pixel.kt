package cop.api.abobaui.constraints.impl.measurements

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.elements.Element

class Pixel(var pixels: Float) : Constraint.Measurement {

    override fun calculate(element: Element, type: Int): Float {
        return pixels
    }
}