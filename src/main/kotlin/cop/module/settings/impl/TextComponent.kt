package cop.module.settings.impl

import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.at
import cop.api.abobaui.dsl.constrain
import cop.api.abobaui.dsl.minus
import cop.api.abobaui.dsl.px
import cop.api.abobaui.dsl.size
import cop.api.abobaui.elements.ElementScope
import cop.module.settings.UIComponent
import cop.utils.ThemeManager.theme
import cop.utils.ui.rendering.NVGRenderer
import cop.utils.ui.rendering.NVGRenderer.defaultFont

class TextComponent(
    override val default: String,
    desc: String = "",
) : UIComponent<String>(default, desc) {

    override var value: String = default

    override fun ElementScope<*>.draw(asSub: Boolean): ElementScope<*> = group(size(w = Copying)) {

        if (children.isEmpty()) {
            val lines = NVGRenderer.wrapText(name, this@draw.element.width, theme.textSize.pixels, defaultFont)

            column(constrain(x = 0.px, y = Centre, w = Copying)) {
                lines.forEach {
                    text(
                        string = it,
                        size = theme.textSize,
                        colour = theme.onSurfaceVariant,
                    )
                }
            }
            return@group
        }

        text(
            string = name,
            size = theme.textSize,
            colour = theme.onSurfaceVariant,
            pos = at(x = 0.px, y = Centre)
        )
    }
}