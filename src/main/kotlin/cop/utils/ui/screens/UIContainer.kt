package cop.utils.ui.screens

import net.minecraft.client.gui.GuiGraphics
import cop.api.abobaui.AbobaUI
import cop.api.events.GuiEvent
import cop.api.events.PacketEvent
import cop.api.events.core.EventBus.on
import cop.api.input.CatKeyboard.Modifier.isCtrlDown
import cop.api.input.CatKeys
import cop.utils.equalsOneOf
import cop.utils.height
import cop.utils.ui.rendering.NVGSpecialRenderer
import cop.utils.width
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket
import cop.api.input.CatMouse.mx
import cop.api.input.CatMouse.my

class UIContainer(ui: AbobaUI.Instance, val cancelling: Boolean = true) : UIHandler(ui) {

    constructor(ui: AbobaUI, cancelling: Boolean = true) : this(AbobaUI.Instance(ui), cancelling)

    private fun render(ctx: GuiGraphics, cancel: () -> Unit) {
        resize(width, height)

        ui.ctx = ctx
        mouseMove(mx, my)

        NVGSpecialRenderer.draw(ctx, 0, 0, ctx.guiWidth(), ctx.guiHeight()) {
            ui.render(true)
        }
        ui.render(false)
        if (cancelling) cancel()
    }

    override val events = listOf(

        if (cancelling)
            on<GuiEvent.Draw> { render(ctx, ::cancel) }
        else
            on<GuiEvent.DrawTooltip> { render(ctx, ::cancel) },

        on<GuiEvent.Click> {
            if (state) mouseClick(button) else mouseRelease(button)
            if (cancelling) cancel()
        },

        on<GuiEvent.Key.Press> {
            keyTyped(key)

            val ctrlHotkeys = setOf(
                CatKeys.KEY_V,
                CatKeys.KEY_C,
                CatKeys.KEY_W,
                CatKeys.KEY_X,
                CatKeys.KEY_A
            )
            if (isCtrlDown && key in ctrlHotkeys) {
                charTyped(key.toChar())
            }

            if (!key.equalsOneOf(CatKeys.KEY_E, CatKeys.KEY_ESCAPE) && cancelling) cancel()
        },

        on<GuiEvent.Char> {
            charTyped(char)
        },

        on<GuiEvent.Close> {
            close()
        },

        on<PacketEvent.ReceivedClient> {
            if (packet is ClientboundContainerClosePacket) close()
        },

        on<GuiEvent.DrawBackground> {
            if (cancelling) cancel()
        }
    )
}
