package cop.mixins;

import cop.module.impl.render.NameTags;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(NameTagFeatureRenderer.class)
public class NameTagFeatureRendererMixin {

    // 26.x replaced NameTagFeatureRenderer.render(...) (which called
    // Font.drawInBatch) with renderTranslucent(SubmitNodeCollection, ...) that
    // submits text through the deferred SubmitNodeCollector — there's no
    // drawInBatch call to @ModifyArgs anymore. The NameTags shadow/background
    // override is therefore <=1.21.11-only for now (TODO: re-home onto the 26.x
    // submit path).
    //? if <= 1.21.11 {
    /*@ModifyArgs(
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
    *///? }
}
