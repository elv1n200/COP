package cop.api.abobaui.constraints.impl.measurements

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.elements.Element

object Undefined : Constraint.Measurement {
    override fun calculate(element: Element, type: Int): Float {
        return when (type) {
            0 -> element.internalX
            1 -> element.internalY
            2 -> element.width
            else -> element.height
        }
    }

}