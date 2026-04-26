package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.features.impl.critsaddons.StatDisplay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Gui.class)
public class MixinGuiStatDisplay {
    @Redirect(
        method = "renderHotbarAndDecorations",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderExperienceLevel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;I)V"
        )
    )
    private void critsaddons$hideExperienceLevel(GuiGraphics context, Font textRenderer, int level) {
        if (!StatDisplay.shouldHideExperienceBar()) {
            ContextualBarRenderer.renderExperienceLevel(context, textRenderer, level);
        }
    }
}
