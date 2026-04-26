package cop.api.customtriggers.actions

import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.size
import cop.api.abobaui.elements.ElementScope
import cop.api.customtriggers.TriggerContext
import cop.config.TypeName
import cop.utils.ThemeManager.theme

@TypeName("hide_message")
class HideMessageAction : TriggerAction {
    override fun execute(ctx: TriggerContext) {
        if (ctx is TriggerContext.Chat) ctx.cancelled = true
    }

    override fun displayString() = "Hide message"

    override fun ElementScope<*>.draw() = column(size(w = Copying)) {
        text(
            string = "Hides message",
            size = theme.textSize,
            colour = theme.onSurfaceVariant,
        )
    }
}