package cop.api.customtriggers.conditions

import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.customtriggers.TriggerContext
import cop.api.input.CatKeyboard
import cop.api.input.CatKeys
import cop.config.TypeName
import cop.utils.ThemeManager.theme

@TypeName("key_pressed")
class KeyPressCondition(var key: Int = CatKeys.KEY_NONE) : TriggerCondition {
    override fun matches(ctx: TriggerContext): Boolean {
        return ctx is TriggerContext.Key && ctx.key == key
    }

    override fun displayString() = "Key [${CatKeyboard.getKeyName(key)}] pressed"

    override fun ElementScope<*>.draw() = row(size(w = Copying)) {
        text(
            string = "Key",
            size = theme.textSize,
            colour = theme.onSurfaceVariant
        )
    }
}