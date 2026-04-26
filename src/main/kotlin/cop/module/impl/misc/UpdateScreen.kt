package cop.module.impl.misc

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB

/**
 * Modal "update available" screen — same UX as Athen / Skyblocker / SkyHanni:
 * pops up after world load when a new release is detected, with three actions
 *   1. **Update Now** — start the libautoupdate download + exit-hook swap.
 *   2. **Remind Later** — close, will pop again on next launch.
 *   3. **Skip Version** — persist this version's tag so the popup doesn't fire
 *      again until the next *newer* version is released. Two-click confirm
 *      so it's not triggered accidentally.
 *
 * @author elvin
 */
class UpdateScreen(
    private val parent: Screen?,
    private val currentVersion: String,
    private val newVersion: String,
    private val onUpdate: () -> Unit,
    private val onRemind: () -> Unit,
    private val onSkip: () -> Unit,
) : Screen(Component.literal("COP Update")) {

    private var skipArmed = false
    private lateinit var skipButton: Button

    private val panelWidth = 360
    private val panelHeight = 175
    private val buttonWidth = 104
    private val buttonHeight = 22
    private val buttonGap = 8

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        super.init()
        val px = (width - panelWidth) / 2
        val py = (height - panelHeight) / 2
        val by = py + panelHeight - buttonHeight - 12

        addRenderableWidget(
            Button.builder(Component.literal("Update Now")) {
                onUpdate()
                Minecraft.getInstance().setScreen(parent)
            }.bounds(px + 16, by, buttonWidth, buttonHeight).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Remind Later")) {
                onRemind()
                Minecraft.getInstance().setScreen(parent)
            }.bounds(px + 16 + (buttonWidth + buttonGap), by, buttonWidth, buttonHeight).build()
        )
        skipButton = addRenderableWidget(
            Button.builder(Component.literal("Skip Version")) {
                if (skipArmed) {
                    onSkip()
                    Minecraft.getInstance().setScreen(parent)
                } else {
                    skipArmed = true
                    skipButton.message = Component.literal("Confirm?")
                }
            }.bounds(px + 16 + (buttonWidth + buttonGap) * 2, by, buttonWidth, buttonHeight).build()
        )
    }

    override fun render(g: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.render(g, mouseX, mouseY, partialTick)

        val px = (width - panelWidth) / 2
        val py = (height - panelHeight) / 2

        // Dim backdrop so the modal pops against the in-game render.
        g.fill(0, 0, width, height, ARGB.color(0xA0, 0, 0, 0))

        // Header bar + body
        g.fill(px, py, px + panelWidth, py + 28, COLOUR_HEADER)
        g.fill(px, py + 28, px + panelWidth, py + panelHeight, COLOUR_BODY)
        // 1-px outline
        outline(g, px, py, panelWidth, panelHeight, COLOUR_OUTLINE)
        // Header underline
        g.fill(px, py + 28, px + panelWidth, py + 29, COLOUR_OUTLINE)

        val font = font
        g.drawString(font, "Update available for COP", px + 16, py + 10, COLOUR_TITLE, false)

        val rowY = py + 40
        val lineH = font.lineHeight + 6

        g.drawString(font, "Current version:", px + 16, rowY, COLOUR_LABEL, false)
        g.drawString(
            font, currentVersion,
            px + panelWidth - 16 - font.width(currentVersion), rowY,
            COLOUR_TEXT, false
        )

        g.drawString(font, "New version:", px + 16, rowY + lineH, COLOUR_LABEL, false)
        g.drawString(
            font, newVersion,
            px + panelWidth - 16 - font.width(newVersion), rowY + lineH,
            COLOUR_NEW, false
        )

        // Divider above the button row
        g.fill(px + 16, rowY + lineH + 30, px + panelWidth - 16, rowY + lineH + 31, COLOUR_OUTLINE)
    }

    private fun outline(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, col: Int) {
        g.fill(x, y, x + w, y + 1, col)              // top
        g.fill(x, y + h - 1, x + w, y + h, col)      // bottom
        g.fill(x, y, x + 1, y + h, col)              // left
        g.fill(x + w - 1, y, x + w, y + h, col)      // right
    }

    companion object {
        // Catppuccin Mocha-ish palette to match Athen's look.
        private const val COLOUR_HEADER  = 0xFF1E1E2E.toInt()
        private const val COLOUR_BODY    = 0xFF181825.toInt()
        private const val COLOUR_OUTLINE = 0xFF313244.toInt()
        private const val COLOUR_TITLE   = 0xFFCBA6F7.toInt()  // mauve
        private const val COLOUR_LABEL   = 0xFFA6ADC8.toInt()  // subtext
        private const val COLOUR_TEXT    = 0xFFCDD6F4.toInt()  // text
        private const val COLOUR_NEW     = 0xFFA6E3A1.toInt()  // green
    }
}
