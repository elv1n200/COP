package cop.module.impl.render

import net.minecraft.client.gui.GuiGraphics
import cop.api.colour.Colour
import cop.api.events.GuiEvent
import cop.api.events.RenderEvent
import cop.module.Module

/**
 * Port of Athen `GameTint` (xyz.aerii.athen.modules.impl.render.GameTint).
 *
 * Renders a flat colour over the screen:
 *   - "Tint HUDs" covers the in-world HUD (Overlay pass, e.g. during normal play)
 *   - "Tint GUIs" covers open containers/inventories
 * Both independently switchable. Colour supports full alpha so you can do subtle
 * night-vision style tints or strong "red screen" alerts.
 */
object GameTint : Module(
    "Game Tint",
    desc = "Tints the HUD or any open GUI in a user-chosen colour."
) {
    private val colour by colourPicker("Tint colour", Colour.RGB(0x19000000), true)
    private val tintHud by switch("Tint HUDs", true)
    private val tintGui by switch("Tint GUIs", true)

    init {
        on<RenderEvent.Overlay> {
            if (!tintHud) return@on
            if (mc.screen != null) return@on
            ctx.fillScreen()
        }

        on<GuiEvent.Draw.Post> {
            if (!tintGui) return@on
            ctx.fillScreen()
        }
    }

    private fun GuiGraphics.fillScreen() {
        fill(0, 0, guiWidth(), guiHeight(), colour.rgb)
    }
}
