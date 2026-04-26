package cop.api.abobaui.constraints.impl.operational

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.elements.Element

class Multiplicative(
    private val first: Constraint,
    private val second: Constraint
) : Constraint.Measurement {

    override fun calculate(element: Element, type: Int) = first.calculate(element, type) * second.calculate(element, type)

    override fun reliesOnChildren() = first.reliesOnChildren() || second.reliesOnChildren()

    override fun reliesOnParent() = first.reliesOnParent() || second.reliesOnParent()
}
