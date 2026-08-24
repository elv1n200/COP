package cop.api.customtriggers.conditions

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
import cop.utils.ThemeManager.theme
import cop.utils.UserRegex
import cop.utils.ui.elements.switch
import cop.utils.ui.elements.themedInput

@TypeName("message_sent")
class MessageCondition(var pattern: String = "", var isRegex: Boolean = false) : TriggerCondition {

    @Transient private var cachedPattern: String? = null
    @Transient private var cachedRegex: Regex? = null

    override fun matches(ctx: TriggerContext): Boolean {
        if (ctx !is TriggerContext.Chat) return false
        if (!isRegex) return ctx.message.contains(pattern, ignoreCase = true)

        val match = compiledRegex()?.let { UserRegex.find(it, ctx.message) } ?: return false

        match.groups.forEachIndexed { index, group ->
            if (group != null) ctx.data["%$index%"] = group.value
        }

        NAMED_GROUP.findAll(pattern).forEach { res ->
            val name = res.groupValues[1]
            runCatching { match.groups[name] }.getOrNull()
                ?.let { ctx.data["%$name%"] = it.value }
        }

        return true
    }

    private fun compiledRegex(): Regex? {
        if (cachedPattern != pattern) {
            cachedPattern = pattern
            cachedRegex = UserRegex.compile(pattern)
        }
        return cachedRegex
    }

    override fun displayString(): String {
        val msg = if (pattern.length > 25) pattern.take(25) + "..." else pattern
        val regex = if (isRegex) " with regex" else ""
        return "Message \"$msg\"$regex sent"
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
                    string = pattern,
                    pos = at(x = 3.percent),
                    size = theme.textSize,
                    colour = theme.onSurfaceVariant,
                    caretColour = theme.primary
                ) {
                    maxWidth(Fill - 3.percent)
                    onTextChanged { (string) ->
                        pattern = string
                    }
                }
            }
        }

        row(gap = 7.px) {
            divider(2.px)
            switch(::isRegex, size = 16.px)
            text(
                string = "Regex",
                size = theme.textSize,
                colour = theme.onSurfaceVariant,
                pos = at(y = Centre)
            )
        }
    }

    private companion object {
        val NAMED_GROUP = Regex("""\(\?<(\w+)>""")
    }
}
