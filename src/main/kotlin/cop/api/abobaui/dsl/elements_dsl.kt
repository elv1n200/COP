package cop.api.abobaui.dsl

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.Constraints
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.impl.Block
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.colour.Colour
import cop.utils.ui.data.Radii

inline fun ElementScope<*>.outlineBlock(
    constraints: Constraints,
    colour: Colour,
    thickness: Constraint.Measurement,
    radius: Radii? = null,
    block: ElementScope<Block>.() -> Unit = {}
) = block(constraints, Colour.TRANSPARENT, radius, block).outline(colour, thickness)