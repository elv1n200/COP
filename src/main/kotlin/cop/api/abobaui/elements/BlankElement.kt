package cop.api.abobaui.elements

import cop.api.abobaui.constraints.Constraints

abstract class BlankElement(constraints: Constraints) : Element(constraints) {
    final override fun draw() {  }
}