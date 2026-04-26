package cop.api.customtriggers

import cop.api.abobaui.elements.ElementScope

interface Abobable {
    fun displayString(): String = "Placeholder"
    fun ElementScope<*>.draw(): ElementScope<*>
}