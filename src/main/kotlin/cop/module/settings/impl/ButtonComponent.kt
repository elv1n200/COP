package cop.module.settings.impl

import cop.api.abobaui.constraints.impl.measurements.Animatable
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.animations.Animation
import cop.api.colour.Colour
import cop.api.input.CursorShape
import cop.utils.ThemeManager.theme
import cop.module.settings.UIComponent
import cop.utils.Scheduler.scheduleTask
import cop.utils.ui.cursor


class ButtonComponent(
    name: String,
    desc: String = "",
    override val default: () -> Unit = {}
) : UIComponent<() -> Unit>(name, desc) {

    override var value: () -> Unit = default

    var action: () -> Unit by this::value

    override fun ElementScope<*>.draw(asSub: Boolean): ElementScope<*> =
        block(
            size(w = Copying, h = if (asSub) 22.px else 25.px),
            colour = theme.surfaceContainerHighest,
            5.radius()
        ) {
            val thickness = Animatable(from = 2.px, to = 3.px)
            val outlineCol = Colour.Animated(from = theme.outline, to = theme.primary)
            outline(outlineCol, thickness = thickness)
//            hoverEffect(factor = 1.15f)
//            tonalHover()

            onMouseEnterExit {
                outlineCol.animate(0.25.seconds, Animation.Style.EaseInOutQuint)
            }

            onClick {
                thickness.animate(0.25.seconds, style = Animation.Style.EaseInOutQuint)?.onFinish {
                    scheduleTask {
                        thickness.animate(0.25.seconds, Animation.Style.EaseInOutQuint)
                    }
                }
                action.invoke()
                true
            }
            text(
                string = name,
                size = theme.textSize,
                colour = theme.onSurfaceVariant
            )

            cursor(CursorShape.HAND)
        }
}