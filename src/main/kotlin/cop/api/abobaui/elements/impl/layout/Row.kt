package cop.api.abobaui.elements.impl.layout

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.Constraints
import cop.api.abobaui.constraints.impl.size.Bounding
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.size
import cop.api.abobaui.elements.AbobaDSL
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.Layout

class Row(
    constraints: Constraints,
    gap: Constraint.Size? = null
) : Layout(constraints, gap) {

    override fun prePosition() {
        val gap = gap?.calculateSize(this, horizontal = true) ?: 0f
        var increment = 0f
        children?.forEach {
            if (it.constraints.x.undefined() && it.enabled) {
                it.internalX = increment
                increment += it.width + if (it is Divider) 0f else gap
            }
        }
    }

    companion object {
        /**
         * Creates a column, with a width being specified and height of [Copying].
         *
         * Acts as a section, to place elements in.
         */
        @AbobaDSL
        fun ElementScope<Column>.sectionColumn(
            size: Constraint.Size = Bounding,
            gap: Constraint.Size? = null,
            block: ElementScope<Column>.() -> Unit
        ) = Column(size(w = size, h = Copying), gap).scope(block)
    }
}