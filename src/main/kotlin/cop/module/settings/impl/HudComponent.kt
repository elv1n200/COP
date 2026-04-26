package cop.module.settings.impl

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import cop.api.abobaui.constraints.impl.positions.Centre
import cop.api.abobaui.constraints.impl.size.AspectRatio
import cop.api.abobaui.constraints.impl.size.Copying
import cop.api.abobaui.dsl.*
import cop.api.abobaui.elements.ElementScope
import cop.api.abobaui.elements.Layout.Companion.divider
import cop.api.abobaui.elements.impl.Block.Companion.outline
import cop.api.abobaui.elements.impl.Popup
import cop.api.animations.Animation
import cop.api.colour.Colour
import cop.api.input.CursorShape
import cop.module.settings.Saving
import cop.module.settings.UIComponent
import cop.utils.ChatUtils.modMessage
import cop.utils.ThemeManager.theme
import cop.utils.ui.cursor
import cop.utils.ui.elements.switch
import cop.utils.ui.hud.Hud
import cop.utils.ui.hud.HudManager
import cop.utils.ui.hud.HudManager.settings
import cop.utils.ui.popupX
import cop.utils.ui.popupY
import cop.utils.ui.screens.UIScreen.Companion.open

class HudComponent<T : Hud>(
    name: String,
    hud: T,
    desc: String = ""
) : UIComponent<T>(name, desc), Saving {

    override val default: T = hud
    override var value: T = hud

    override fun write(): JsonElement = JsonObject().apply {
        if (value.toggleable) addProperty("enabled", value.enabled)
        value.settings.forEach {
            if (it !is Saving) return@forEach
            add(it.name, it.write())
        }
    }

    override fun read(element: JsonElement) {
        if (element !is JsonObject) return
        if (value.toggleable) value.enabled = element.get("enabled")?.asBoolean ?: value.enabled
        element.apply {
            entrySet().forEach {
                val setting = value.getSettingByName(it.key) as? Saving ?: return@forEach
                setting.read(it.value)
            }
        }
    }

    override fun reset() {
        value.settings.forEach { it.reset() }
    }

    var popup: Popup? = null

    override fun ElementScope<*>.draw(asSub: Boolean): ElementScope<*> = row(size(w = Copying)) {
        if (!value.toggleable) {
            column(size(w = Copying), gap = 7.px) {
                value.settings.forEach { setting ->
                    if (setting !is UIComponent) return@forEach
                    if (setting.parent != null && setting.parent as UIComponent in value.settings) return@forEach
                    val dummy = value.settings.first() as UIComponent<*>
                    val asSub = setting in dummy.children && !setting.children.isNotEmpty() && !setting.forceParent
                    setting.render(this, asSub).onEvent(setting.valueUpdated) {
                        HudManager.reinit()
                        true
                    }
                }
                row(size(w = Copying)) {
                    text(
                        string = "Edit location",
                        size = theme.textSize,
                        colour = theme.onSurfaceVariant,
                        pos = at(y = Centre)
                    )
                    image(
                        image = theme.moveImage, // looks kinda meh
                        colour = theme.onSurfaceVariant,
                        constraints = constrain(x = 0.px.alignOpposite,w = AspectRatio(1f), h = 20.px),
                    ) {
                        cursor(CursorShape.HAND)
                        onClick(nonSpecific = true) {
                            open(HudManager.editor())
                            true
                        }
                    }
                }
            }
            return@row
        }

        val col = Colour.Animated(
            from = theme.surfaceVariant,
            to = theme.primary,
            swapIf = value.enabled
        )

        val outlineCol = Colour.Animated(
            from = theme.outline,
            to = theme.primary,
            swapIf = value.enabled
        )

        fun ElementScope<*>.checkbox() = block(
            constraints =
                if (asSub) size(w = AspectRatio(1f), h = 15.px)
                else constrain(y = Centre, w = AspectRatio(1f), h = 20.px),
            colour = col,
            radius = 4.radius()
        ) {
            outline(outlineCol, 2.px)
//            hoverEffect(factor = 1.15f)
            tonalHover()

            onClick {
                col.animate(0.25.seconds, Animation.Style.EaseInOutQuint)
                outlineCol.animate(0.25.seconds, Animation.Style.EaseInOutQuint)
                value.enabled = !value.enabled
            }
        }

        if (asSub) {
            checkbox()
            divider(4.px)
        }

        text(
            string = name,
            size = theme.textSize,
            colour = theme.onSurfaceVariant,
            pos = at(y = Centre)
        )

        row(at(x = 0.px.alignOpposite), gap = 5.px) {
            image(
                image = theme.moveImage, // looks kinda meh
                colour = theme.onSurfaceVariant,
                constraints = size(w = AspectRatio(1f), h = if (asSub) 16.px else 20.px),
            ) {
                cursor(CursorShape.HAND)
                onClick(nonSpecific = true) {
                    open(HudManager.editor())
                    true
                }
            }
            image(
                image = theme.gearImage, // looks kinda meh
                colour = theme.onSurfaceVariant,
                constraints = size(w = AspectRatio(1f), h = if (asSub) 16.px else 20.px),
            ) {
                cursor(CursorShape.HAND)
                onClick(nonSpecific = true) {
                    popup?.closePopup()
                    popup = settings(at(popupX(gap = if (asSub) 10f else 35f), popupY()), value, { popup = null }) { HudManager.reinit() }
                    true
                }
            }

            if (!asSub) switch(value::enabled, size = 20.px, pos = at(y = Centre))
        }

        onClick {
            popup?.closePopup()
            popup = null
        }
    }
}