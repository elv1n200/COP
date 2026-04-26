package cop.module.settings.impl

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.at
import cop.api.abobaui.dsl.px
import cop.api.abobaui.dsl.size
import cop.api.abobaui.elements.ElementScope
import cop.module.settings.Saving
import cop.module.settings.UIComponent
import cop.utils.ThemeManager.theme
import cop.utils.ui.elements.lengthInput
import cop.utils.ui.elements.suggestionInput
import cop.utils.ui.elements.themedInput

class TextInputComponent(
    name: String,
    override val default: String = "",
    var length: Int = 20,
    desc: String = "",
    val placeholder: String = ""
) : UIComponent<String>(name, desc), Saving {

    override var value: String = default
        set(value) {
            field = if (length > 0 && value.length > length) {
                value.take(length)
            } else
                value
        }

    private var censors = false

    private var suggestions: () -> List<String> = { emptyList() }

    fun censors(): TextInputComponent { // todo
        censors = true
        return this
    }

    fun suggests(supplier: () -> Any): UIComponent<String> {
        suggestions = {
            when (val s = supplier()) {
                is Iterable<*> -> s.map { it.toString() }
                is Array<*> -> s.map { it.toString() }
                else -> listOf(s.toString())
            }
        }

        return this
    }

    fun suggests(values: List<*>) = suggests { values }
    fun suggests(vararg values: Any) = suggests { values.toList() }

    override fun write(): JsonElement {
        return JsonPrimitive(value)
    }

    override fun read(element: JsonElement) {
        element.asString?.let {
            value = it
        }
    }

    override fun ElementScope<*>.draw(asSub: Boolean): ElementScope<*> = column(size(w = Copying), gap = 3.px) {
        if (!asSub) text( // idk
            string = name,
            size = theme.textSize,
            colour = theme.onSurfaceVariant,
        )

        val placeholder = if (asSub && placeholder.isEmpty()) name else placeholder

        suggestionInput(suggestions = suggestions) {
            themedInput(size = size(w = Copying, h = 25.px)) {
                lengthInput(
                    ::value,
                    length = length,
                    placeholder = placeholder,
                )
            }
        }
    }
}