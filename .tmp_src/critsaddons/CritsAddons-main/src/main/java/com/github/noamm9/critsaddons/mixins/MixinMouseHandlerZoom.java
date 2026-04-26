package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.features.impl.critsaddons.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MixinMouseHandlerZoom {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Unique private double critsaddons$filteredDX;
    @Unique private double critsaddons$filteredDY;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void critsaddons$consumeScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (Zoom.consumeScroll(horizontal, vertical)) {
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void critsaddons$applyRotationSmoothing(double deltaTime, CallbackInfo ci) {
        if (!Zoom.isRotationSmoothingActive()) {
            critsaddons$filteredDX = 0.0;
            critsaddons$filteredDY = 0.0;
            return;
        }

        double alpha = Zoom.getRotationSmoothingAlpha();
        critsaddons$filteredDX += (this.accumulatedDX - critsaddons$filteredDX) * alpha;
        critsaddons$filteredDY += (this.accumulatedDY - critsaddons$filteredDY) * alpha;

        this.accumulatedDX = critsaddons$filteredDX;
        this.accumulatedDY = critsaddons$filteredDY;
    }
}
