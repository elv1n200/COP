package cop.module.settings.impl

import cop.api.abobaui.constraints.impl.measurements.Undefined
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.impl.Text.Companion.string
import cop.module.settings.Saving
import cop.module.settings.UIComponent
import cop.utils.ThemeManager.theme
import cop.utils.round
import cop.utils.ui.elements.numberInput
import cop.utils.ui.elements.slider
import cop.utils.ui.watch
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import kotlin.math.floor
import kotlin.math.round

@Suppress("UNCHECKED_CAST")
class SliderComponent<E>(
    name: String,
    override val default: E = 1.0 as E,
    val min: E = -10000 as E,
    val max: E = 10000 as E,
    val increment: Number = 1,
    desc: String = "",
    val unit: String = "",
) : UIComponent<E>(name, desc), Saving where E : Number, E : Comparable<E> {

    override var value: E = default

    val incrementD = increment.toDouble()
    private val minD = min.toDouble()
    private val maxD = max.toDouble()
    private val valueDouble get() = value.toDouble()

    private val text: String
        get() {
            val number = if (valueDouble - floor(valueDouble) == 0.0) value.toInt() else valueDouble.round(2)
            return "$number$unit"
        }

    fun set(new: Number) {
        val n = (round((new.toDouble() / incrementD) + 1e-9) * incrementD).coerceIn(minD, maxD)

        value = when (default) {
            is Int -> n.toInt() as E
            is Float -> n.toFloat() as E
            is Long -> n.toLong() as E
            is Double -> n as E
            else -> throw Exception("no good number setting")
        }
    }

    override fun hide(): SliderComponent<E> {
        super.hide()
        return this
    }

    override fun write(): JsonElement {
        return JsonPrimitive(value)
    }

    override fun read(element: JsonElement) {
        set(element.asNumber)
    }

    override fun ElementScope<*>.draw(asSub: Boolean): ElementScope<*> = column(size(Copying), gap = 5.px) {

        row(size(w = Copying), gap = 3.px) {
            text(
                string = name + if (asSub) ":" else "",
                size = theme.textSize,
                colour = theme.onSurfaceVariant
            )

            val input = numberInput(
                ::value,
                min = min,
                max = max,
                unit = unit,
                size = theme.textSize,
                colour = theme.onSurfaceVariant,
                pos = at(if (asSub) Undefined else 0.px.alignOpposite, y = 0.px)
            )

            watch(::value) {
                set(it)
                input.string = text
            }
        }

        slider(
            ::value,
            min = min,
            max = max,
            increment = increment,
            size = size(Copying, 8.px - if (asSub) 2.px else 0.px),
        )
    }
}
