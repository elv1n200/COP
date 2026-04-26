package cop.api.customtriggers.actions

import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.constraints.impl.size.Fill
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.Layout.Companion.divider
import cop.api.abobaui.elements.impl.TextInput.Companion.maxWidth
import cop.api.abobaui.elements.impl.TextInput.Companion.onTextChanged
import cop.api.customtriggers.TriggerContext
import cop.config.TypeName
import cop.utils.ChatUtils
import cop.utils.ThemeManager.theme
import cop.utils.ui.elements.switch
import cop.utils.ui.elements.themedInput

@TypeName("send_message")
class SendMessageAction(var message: String = "", var client: Boolean = true) : TriggerAction {
    override fun execute(ctx: TriggerContext) {
        var msg = message

        ctx.data.forEach { (key, value) ->
            msg = msg.replace(key, value)
        }

        if (client) ChatUtils.modMessage(msg, prefix = "")
        else ChatUtils.say(msg)
    }

    override fun displayString(): String {
        val msg = if (message.length > 25) message.take(25) + "..." else message
        val side = if (client) "client" else "server"
        return "Send \"$msg\" $side side"
    }

    override fun ElementScope<*>.draw() = column(size(w = Copying), gap = 10.px) {
        column(size(w = Copying)) {
            text(
                string = "Message",
                size = theme.textSize,
                colour = theme.onSurfaceVariant,
            )
            divider(3.px)

            themedInput {
                textInput(
                    string = message,
                    pos = at(x = 3.percent),
                    size = theme.textSize,
                    colour = theme.onSurfaceVariant,
                    caretColour = theme.primary
                ) {
                    maxWidth(Fill - 3.percent)
                    onTextChanged { (string) ->
                        message = string
                    }
                }
            }
        }

        row(gap = 7.px) {
            divider(2.px)
            switch(::client, size = 16.px)
            text(
                string = "Client-side",
                size = theme.textSize,
                colour = theme.onSurfaceVariant,
                pos = at(y = Centre)
            )
        }
    }
}