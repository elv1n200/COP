package cop.api.abobaui.elements.impl.layout

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.Constraints
import cop.api.abobaui.elements.Layout

class Grid(
    constraints: Constraints,
    gap: Constraint.Size? = null,
) : Layout(constraints, gap) {

    override fun prePosition() {
        val padding = gap?.calculateSize(this, horizontal = true) ?: 0f

        var currX = 0f
        var currY = 0f
        var rowHeight = 0f

        children?.forEach {
            if (it.constraints.x.undefined() && it.constraints.y.undefined() && it.enabled) {
                // The gap belongs between cells, not after the last cell. Using
                // it in the fit check made an exactly fitting final column wrap.
                if (currX > 0f && currX + it.width > width) {
                    currX = 0f
                    currY += rowHeight + padding
                    rowHeight = 0f
                }
                it.internalX = currX
                it.internalY = currY
                currX += it.width + padding
                rowHeight = maxOf(rowHeight, it.height)
            }
        }
    }
}
