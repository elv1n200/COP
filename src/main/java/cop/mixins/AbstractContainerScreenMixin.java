package cop.mixins;

import cop.api.events.GuiEvent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public class AbstractContainerScreenMixin {

//    @Inject(
//            method = "render",
//            at = @At("HEAD"),
//            cancellable = true
//    )
//    protected void cop$onRender(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
//        if (new GuiEvent.Draw((Screen) (Object) this, context, mouseX, mouseY).post()) ci.cancel();
//    }

//    @Inject(
//            method = "renderBackground",
//            at = @At("HEAD"),
//            cancellable = true
//    )
//    protected void cop$onRenderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
//        if (new GuiEvent.DrawBackground((Screen) (Object) this, context, mouseX, mouseY).post()) ci.cancel();
//    }

    // 1.21.11 added `mouseX` + `mouseY` int params to `renderSlot`. Same body
    // either way — we don't actually need the new params because the event
    // payload only carries the slot.
    //? if <= 1.21.10 {
    @Inject(
            method = "renderSlot",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cop$onDrawSlot(GuiGraphics context, Slot slot, CallbackInfo ci) {
        if (new GuiEvent.Slot.Draw((Screen) (Object) this, context, slot).post()) ci.cancel();
    }
    //? } else {
    /*@Inject(
            method = "renderSlot",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cop$onDrawSlot(GuiGraphics context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        if (new GuiEvent.Slot.Draw((Screen) (Object) this, context, slot).post()) ci.cancel();
    }
    *///?}

    @Inject(
            method = "slotClicked",
            at = @At("HEAD"),
            cancellable = true
    )
    public void cop$onMouseClickedSlot(Slot slot, int slotId, int button, ClickType actionType, CallbackInfo ci) {
        if (slot == null) return;
        if (new GuiEvent.Slot.Click((Screen) (Object) this, slot, slotId, button, actionType).post()) ci.cancel();
    }

//    @Inject(
//            method = "mouseClicked",
//            at = @At("HEAD"),
//            cancellable = true
//    )
//    public void cop$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
//        if (EventBus.INSTANCE.post(new GuiEvent.Click((Screen) (Object) this, (int) mouseX, (int) mouseY, button, false)))
//            cir.cancel();
//    }
//
//    @Inject(
//            method = "mouseReleased",
//            at = @At("HEAD"),
//            cancellable = true
//    )
//    public void cop$onMouseRelease(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
//        if (EventBus.INSTANCE.post(new GuiEvent.Click((Screen) (Object) this, (int) mouseX, (int) mouseY, button, true)))
//            cir.cancel();
//    }

//    @Inject(
//            method = "keyPressed",
//            at = @At("HEAD"),
//            cancellable = true
//    )
//    public void cop$onKeyPressed(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
//        if (new GuiEvent.Key((Screen) (Object) this, input.input()).post()) cir.cancel();
//    }

    @Inject(
            method = "renderTooltip",
            at = @At("HEAD"),
            cancellable = true
    )
    public void cop$onDrawMouseoverTooltip(GuiGraphics context, int mouseX, int mouseY, CallbackInfo ci) {
        if (new GuiEvent.DrawTooltip((Screen) (Object) this, context, mouseX, mouseY).post()) {
            ci.cancel();
        }
    }
}
