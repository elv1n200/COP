package cop.utils.ui.elements

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.Positions
import cop.api.abobaui.constraints.Sizes
import cop.api.abobaui.constraints.impl.size.Bounding
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.abobaui.elements.impl.Scrollable.Companion.scroll
import cop.api.abobaui.elements.impl.popup
import cop.api.colour.Colour
import cop.api.input.CursorShape
import cop.utils.ThemeManager.theme
import cop.utils.ui.cursor

inline fun <T> ElementScope<*>.selector(
    entries: List<T>,
    selected: Int? = null,
    displayString: (T) -> String = { it.toString() },
    colour: Colour = theme.surfaceContainerHighest,
    outline: Colour = theme.outline,
    thickness: Constraint.Measurement = 2.px,
    pos: Positions = at(),
    size: Sizes = size(130.px, 30.px),
    crossinline onSelect: (T) -> Unit,
) = popup(copies(), smooth = false) {

    onClick {
        closePopup()
    }

    block(
        constrain(
            pos.x, pos.y,
            w = Bounding + thickness - 0.5.px,
            h = Bounding + thickness - 0.5.px
        ),
        colour = colour,
        5.radius()
    ) {
        outline(outline, thickness)
        cursor(CursorShape.HAND)
        onClick { true }

        val height = size.height.calculateSize(element, false)

        val h = (height * entries.size.coerceAtMost(5) + if (entries.size > 5) height * 0.4f else 0f).px

        val scrollable = scrollable(size(w = size.width, h = h)) {
            column {
                entries.forEachIndexed { i, comp ->
                    val col = if (selected == i) theme.primaryContainer else colour
                    block(
                        size,
                        colour = col,
                        3.5.radius()
                    ) {
//                        hoverEffect(1.1f)
                        tonalHover()

                        text(
                            string = displayString(comp),
                            colour = if (selected == i) theme.onPrimaryContainer else theme.onSurface
                        )

                        onClick {
                            onSelect(comp)
                            closePopup()
                            true
                        }
                    }
                }
            }
        }

        onScroll { (amount) ->
            scrollable.scroll(amount * -(height * 2f))
        }
    }
}