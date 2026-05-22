package cop.mixins;

import cop.module.impl.render.NameTags;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(NameTagFeatureRenderer.class)
public class NameTagFeatureRendererMixin {

    // 26.x renamed render(...) -> renderTranslucent(SubmitNodeCollection, ...), but
    // it still funnels through Font.drawInBatch with the same arg order, so the
    // shadow (arg 4) / background-color (arg 8) overrides still apply. Only the
    // target method name and the matrix param (Matrix4f -> Matrix4fc) differ.
    // Spelled out per-version because the bare method name must NOT be "render"
    // (the >=26 method= replacement rewrites that to extractRenderState).
    //? if >= 26 {
    /*@ModifyArgs(
            method = "renderTranslucent",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"
            )
    )
    private void draw(Args args) {
        if (!NameTags.INSTANCE.getEnabled()) return;
        args.set(4, NameTags.getShadow());
        if (NameTags.getCustomBg()) args.set(8, NameTags.getBgColour().getRgb());
    }*/
    //? } else {
    @ModifyArgs(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Font;drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V"
            )
    )
    private void draw(Args args) {
        if (!NameTags.INSTANCE.getEnabled()) return;
        args.set(4, NameTags.getShadow());
        if (NameTags.getCustomBg()) args.set(8, NameTags.getBgColour().getRgb());
    }
    //? }
}
