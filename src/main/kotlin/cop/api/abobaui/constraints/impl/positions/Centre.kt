package cop.api.abobaui.constraints.impl.positions

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.elements.Element

// places the element in the centre of the parent element
object Centre : Constraint.Position {

    override fun calculatePos(element: Element, horizontal: Boolean): Float {
        val parentSize = element.parent?.getSize(horizontal) ?: 0f
        if (parentSize == 0f) return 0f
        return parentSize / 2 - element.getSize(horizontal) / 2
    }
}