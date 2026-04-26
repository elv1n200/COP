package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.features.impl.critsaddons.Zoom;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class MixinGameRendererZoom {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void critsaddons$applyZoomFov(Camera camera, float partialTick, boolean useSetting, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(Zoom.modifyFov(cir.getReturnValue()));
    }
}
