package cop.utils.ui.screens

import cop.CopMod.mc
import cop.api.abobaui.AbobaUI
import cop.api.input.CatKeyboard.Modifier.isCtrlDown
import cop.api.input.CatKeys
import cop.utils.Scheduler.scheduleTask
import cop.utils.sf
import cop.utils.ui.rendering.NVGSpecialRenderer
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class UIScreen(val instance: AbobaUI.Instance, val background: Boolean = true ) : Screen(Component.literal(instance.title)) {

    override fun init() {
        instance.init(width * sf, height * sf)
    }

    // 26.x moved GUI drawing to a deferred extract model: Screen.render(...) ->
    // extractRenderState(...), renderBackground(...) -> extractBackground(...).
    //? if >= 26 {
    /*override fun extractRenderState(ctx: GuiGraphics, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        instance.ctx = ctx
        instance.eventManager.onMouseMove(mouseX * sf.toFloat(), mouseY * sf.toFloat())
        NVGSpecialRenderer.draw(ctx, 0, 0, ctx.guiWidth(), ctx.guiHeight()) {
            instance.render(true)
        }
        instance.render(false)
        super.extractRenderState(ctx, mouseX, mouseY, deltaTicks)
    }*/
    //? } else {
    override fun render(ctx: GuiGraphics, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        instance.ctx = ctx
        instance.eventManager.onMouseMove(mouseX * sf.toFloat(), mouseY * sf.toFloat())
        NVGSpecialRenderer.draw(ctx, 0, 0, ctx.guiWidth(), ctx.guiHeight()) {
            instance.render(true)
        }
        instance.render(false)
        super.render(ctx, mouseX, mouseY, deltaTicks)
    }
    //? }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, doubled: Boolean) =
        instance.eventManager.onMouseClick(mouseButtonEvent.button())

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        instance.eventManager.onMouseRelease(mouseButtonEvent.button())
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        val a = instance.eventManager.onKeyTyped(keyEvent.key)

        val ctrlHotkeys = setOf(
            CatKeys.KEY_V,
            CatKeys.KEY_C,
            CatKeys.KEY_W,
            CatKeys.KEY_X,
            CatKeys.KEY_A
        )
        var b = false
        if (isCtrlDown && keyEvent.key in ctrlHotkeys) {
            b = instance.eventManager.onKeyTyped(keyEvent.key.toChar())
        }
        return a || b || super.keyPressed(keyEvent)
    }

    override fun keyReleased(keyEvent: KeyEvent) = instance.eventManager.onKeyReleased(keyEvent.key)

    override fun charTyped(characterEvent: CharacterEvent) =
        if (characterEvent.isAllowedChatCharacter) instance.eventManager.onKeyTyped(characterEvent.codepoint.toChar()) else false

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double) =
        instance.eventManager.onMouseScroll(verticalAmount.toFloat())

    override fun onClose() {
        instance.close()
        super.onClose()
    }

    override fun isPauseScreen() = false

    //? if >= 26 {
    /*override fun extractBackground(guiGraphics: GuiGraphics, mouseY: Int, j: Int, deltaTicks: Float) {
        if (background) super.extractBackground(guiGraphics, mouseY, j, deltaTicks)
    }*/
    //? } else {
    override fun renderBackground(guiGraphics: GuiGraphics, mouseY: Int, j: Int, deltaTicks: Float) {
        if (background) super.renderBackground(guiGraphics, mouseY, j, deltaTicks)
    }
    //? }

    companion object {
        fun open(ui: AbobaUI.Instance, background: Boolean = true) = scheduleTask { mc.setScreen(UIScreen(ui, background)) }
    }
}