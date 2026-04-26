package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.features.impl.critsaddons.StatDisplay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceBarRenderer.class)
public class MixinExperienceBarRendererStatDisplay {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void critsaddons$hideExperienceBarBackground(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (StatDisplay.shouldHideExperienceBar()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void critsaddons$hideExperienceBar(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (StatDisplay.shouldHideExperienceBar()) {
            ci.cancel();
        }
    }
}
