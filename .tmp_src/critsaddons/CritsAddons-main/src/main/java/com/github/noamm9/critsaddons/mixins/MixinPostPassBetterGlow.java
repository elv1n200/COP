package com.github.noamm9.critsaddons.mixins;

import com.github.noamm9.critsaddons.features.impl.critsaddons.BetterGlow;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PostPass.class)
public abstract class MixinPostPassBetterGlow {
    @Shadow
    private String name;

    @Shadow
    private ResourceLocation outputTargetId;

    @Redirect(
        method = "method_67884",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V"
        )
    )
    private void critsaddons$setBetterGlowBlurUniform(RenderPass renderPass, String uniformName, GpuBuffer uniformBuffer) {
        renderPass.setUniform(
            uniformName,
            BetterGlow.getBloomBlurConfig(this.name, this.outputTargetId.getPath(), uniformName, uniformBuffer)
        );
    }
}
