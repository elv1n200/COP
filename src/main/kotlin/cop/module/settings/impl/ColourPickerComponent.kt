package cop.module.settings.impl

import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.AspectRatio
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.abobaui.elements.impl.Popup
import cop.api.colour.*
import cop.api.input.CursorShape
import cop.module.settings.Saving
import cop.module.settings.UIComponent
import cop.utils.ThemeManager.theme
import cop.utils.ui.cursor
import cop.utils.ui.elements.colourPicker
import cop.utils.ui.popupX
import cop.utils.ui.popupY
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

class ColourPickerComponent(
    name: String,
    colour: Colour,
    private var allowAlpha: Boolean = false,
    desc: String = ""
) : UIComponent<Colour.HSB>(name, desc), Saving {

    override val default: Colour.HSB = colour.toHSB()

    override var value: Colour.HSB = default.copy()
        set(value) {
            if (!rainbow) {
                storedValue = value.copy()
                field = value
            } else {
                storedValue.alpha = value.alpha
                field = hsb { Colour.RAINBOW.withAlpha(storedValue.alpha).toHSB() }
            }
        }

    private var stupid = false
    private var storedValue: Colour.HSB = default.copy()
    var rainbow: Boolean = false
        private set(enabled) {
            if (field == enabled) return

            if (enabled) {
                if (!stupid) storedValue = value

                field = true
                value = hsb { Colour.RAINBOW.withAlpha(storedValue.alpha).toHSB() }
            } else {
                if (!stupid) storedValue.alpha = value.alpha

                field = false
                value = storedValue.copy()
            }
        }

    override fun reset() {
        storedValue = default.copy()
        rainbow = false
        value = storedValue.copy()
    }

    override fun write(): JsonElement {
        if (!rainbow) {
            storedValue = value
        }
        storedValue.alpha = value.alpha
        return if (!rainbow) JsonPrimitive(storedValue.toHexString(allowAlpha))
        else JsonObject().apply {
            addProperty("colour", storedValue.toHexString(allowAlpha))
            addProperty("rainbow", true)
        }
    }

    override fun read(element: JsonElement) {
        val colour = if (element.isJsonObject) {
            val obj = element.asJsonObject
            Colour.RGB(hexToRGBA(obj["colour"].asString)).toHSB()
        } else if (element.asString?.startsWith("#") == true) {
            Colour.RGB(hexToRGBA(element.asString)).toHSB()
        } else {
            Colour.RGB(element.asInt).toHSB()
        }

        val isRainbow = if (element.isJsonObject) {
            element.asJsonObject["rainbow"]?.asBoolean ?: false
        } else false

        stupid = true
        storedValue = colour
        rainbow = isRainbow
        value = if (isRainbow) hsb { Colour.RAINBOW.withAlpha(storedValue.alpha).toHSB() } else storedValue.copy()
        stupid = false
    }

    private var popup: Popup? = null

    override fun ElementScope<*>.draw(asSub: Boolean): ElementScope<*> = row(size(Copying), gap = 4.px) {
        fun label() = text(
            string = name,
            size = theme.textSize,
            colour = theme.onSurfaceVariant,
            pos = at(y = Centre)
        )

        val constraints =
            if (asSub) size(w = AspectRatio(1.5f), h = 15.px)
            else constrain(x = 0.px.alignOpposite, w = AspectRatio(1.5f), h = 20.px)

        fun box() = block(
            constraints = constraints,
            colour { value.rgb },
            5.radius()
        ) {
            outline(theme.primary, thickness = 2.px)
            cursor(CursorShape.HAND)

            onClick {
                popup?.closePopup()
                popup = colourPicker(
                    ::value,
                    allowAlpha = allowAlpha,
                    isRainbow = ::rainbow,
                    pos = at(popupX(gap = 0f), popupY(corner = true))
                )
                true
            }
        }

        if (asSub) {
            box()
            label()
        } else {
            label()
            box()
        }
    }
}
