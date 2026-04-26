package cop.api.abobaui.elements.impl.layout

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.Constraints
import cop.api.abobaui.elements.Layout

/**
 * todo fix it. currently it's
 *  element <gap> element <gap>
 *  <gap>
 *  element <gap> element <gap>
 *  .
 *  we don't want trailing gap at the end:
 *  element <gap> element
 *  <gap>
 *  element <gap> element
 */

class Grid(
    constraints: Constraints,
    gap: Constraint.Size? = null,
) : Layout(constraints, gap) {

    override fun prePosition() {
        val padding = gap?.calculateSize(this, horizontal = true) ?: 0f

        var currX = 0f
        var currY = 0f

        children?.forEach {
            if (it.constraints.x.undefined() && it.constraints.y.undefined() && it.enabled) {
                if (currX + it.width + padding > width) {
                    currX = 0f
                    currY += it.height + padding
                }
                it.internalX = currX
                it.internalY = currY
                currX += it.width + padding
            }
        }
    }
}