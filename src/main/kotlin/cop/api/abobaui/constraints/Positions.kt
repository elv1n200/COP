package cop.api.abobaui.constraints

import cop.api.abobaui.constraints.impl.measurements.Undefined

class Positions(
    x: Constraint.Position,
    y: Constraint.Position
) : Constraints(x, y, Undefined, Undefined)