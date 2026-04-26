package cop.utils.ui.elements

import cop.api.abobaui.constraints.Constraint
import cop.api.abobaui.constraints.Positions
import cop.api.abobaui.constraints.impl.measurements.Animatable
import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.AspectRatio
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.animations.Animation
import cop.api.colour.Colour
import cop.api.input.CursorShape
import cop.utils.ThemeManager.theme
import cop.utils.ui.cursor
import cop.utils.ui.watch
import kotlin.getValue
import kotlin.reflect.KMutableProperty0
import kotlin.setValue

// https://m3.material.io/components/switch/specs
inline fun ElementScope<*>.switch(
    ref: KMutableProperty0<Boolean>,
    size: Constraint.Size = 16.px,
    colour: Colour = theme.primary,
    pos: Positions = at(),
    crossinline onToggle: () -> Unit = {},
): ElementScope<*> {

    var value by ref

    val trackCol = Colour.Animated(
        from = theme.surfaceContainerHighest,
        to = colour,
        swapIf = value
    )
    val outlineCol = Colour.Animated(
        from = theme.outline,
        to = colour,
        swapIf = value
    )
    val handleCol = Colour.Animated(
        from = theme.outline,
        to = theme.onPrimary,
        swapIf = value
    )
    val handlePos = Animatable(
        from = 13.5.percent ,
        to = 46.15.percent,
        swapIf = value
    )
    val handleSize = Animatable(
        from = 50.percent,
        to = 75.percent,
        swapIf = value
    )

    return block(
        constrain(x = pos.x, y = pos.y, w = AspectRatio(1.625f), h = size),
        colour = trackCol,
        (size.pixels / 2f).radius()
    ) {
        outline(outlineCol, thickness = (size * 0.0625.px).coerceAtLeast(2.px))
        tonalHover()
        cursor(CursorShape.HAND)

        block(
            constrain(
                x = handlePos, y = Centre,
                w = AspectRatio(1.0f), h = handleSize
            ),
            colour = handleCol,
            (size.pixels * 0.375f).radius()
        )

        watch(ref) {
            trackCol.animate(0.35.seconds, Animation.Style.EaseInOutQuint)
            outlineCol.animate(0.35.seconds, Animation.Style.EaseInOutQuint)
            handleCol.animate(0.35.seconds, Animation.Style.EaseInOutQuint)
            handlePos.animate(0.35.seconds, Animation.Style.EaseInOutQuint)
            handleSize.animate(0.35.seconds, Animation.Style.EaseInOutQuint)
            redraw()
        }

        onClick {
            onToggle()
            value = !value
            true
        }
    }
}