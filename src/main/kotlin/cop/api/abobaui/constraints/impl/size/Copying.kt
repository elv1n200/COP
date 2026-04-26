package cop.api.abobaui.constraints.impl.size

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.elements.Element

// copies parent's size
object Copying : Constraint.Size {

    override fun calculateSize(element: Element, horizontal: Boolean): Float {
        return (element.parent?.getSize(horizontal) ?: 0f)
    }

    override fun reliesOnParent() = true
}