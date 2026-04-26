package cop.mixins;

import cop.api.events.GuiEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(
            method = "init*",
            at = @At("HEAD"),
            cancellable = true
    )
    protected void cop$onInitPre(CallbackInfo ci) {
        if (new GuiEvent.Open((Screen) (Object) this).post()) ci.cancel();
    }

    @Inject(
            method = "init*",
            at = @At("TAIL"),
            cancellable = true
    )
    protected void cop$onInitPost(CallbackInfo ci) {
        if (new GuiEvent.Open.Post((Screen) (Object) this).post()) ci.cancel();
    }

    @Inject(
            method = "onClose",
            at = @At("HEAD"),
            cancellable = true
    )
    protected void cop$onClose(CallbackInfo ci) {
        if (new GuiEvent.Close((Screen) (Object) this).post()) ci.cancel();
    }

    @Inject(
            method = "renderWithTooltipAndSubtitles",
            at = @At("HEAD"),
            cancellable = true
    )
    protected void cop$onRender(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (new GuiEvent.Draw((Screen) (Object) this, context, mouseX, mouseY).post()) ci.cancel();
    }

    @Inject(
            method = "renderWithTooltipAndSubtitles",
            at = @At("TAIL"),
            cancellable = true
    )
    protected void cop$onRenderPost(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (new GuiEvent.Draw.Post((Screen) (Object) this, context, mouseX, mouseY).post()) ci.cancel();
    }

    @Inject(
            method = "renderBackground",
            at = @At("HEAD"),
            cancellable = true
    )
    protected void cop$onRenderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (new GuiEvent.DrawBackground((Screen) (Object) this, context, mouseX, mouseY).post()) ci.cancel();
    }

    @Inject(
            method = "renderBackground",
            at = @At("TAIL"),
            cancellable = true
    )
    protected void cop$onRenderBackgroundPost(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (new GuiEvent.DrawBackground.Post((Screen) (Object) this, context, mouseX, mouseY).post()) ci.cancel();
    }
}
