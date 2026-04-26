package cop.utils.ui.screens

import cop.api.abobaui.AbobaUI
import cop.api.events.core.EventBus.on
import cop.api.events.GuiEvent
import cop.api.events.RenderEvent
import cop.api.input.CatMouse.mx
import cop.api.input.CatMouse.my
import cop.utils.height
import cop.utils.ui.rendering.NVGSpecialRenderer
import cop.utils.width

class UIOverlay(ui: AbobaUI.Instance) : UIHandler(ui) {

    constructor(ui: AbobaUI) : this(AbobaUI.Instance(ui))

    override val events = listOf(

        on<RenderEvent.Overlay> {
            resize(width, height)
            ui.ctx = ctx
            mouseMove(mx, my)
            NVGSpecialRenderer.draw(ctx, 0, 0, ctx.guiWidth(), ctx.guiHeight()) {
                ui.render(true)
            }

            ui.render(false)
        },

        on<GuiEvent.Click> {
            if (state) mouseClick(button) else mouseRelease(button)
        },

        on<GuiEvent.Key.Press> {
            keyTyped(key)
        }
    )
}