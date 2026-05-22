package cop.mixins;

import cop.module.impl.render.RenderOptimiser;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
// 26.x removed LightTexture; the lightmap brightness math (which reads
// DimensionType.ambientLight()) now lives in Lightmap.getBrightness(...), a
// static method — so the 26 handler is static and the target name differs.
//? if >= 26 {
/*import net.minecraft.client.renderer.Lightmap;*/
//? } else {
import net.minecraft.client.renderer.LightTexture;
//? }
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import static cop.module.impl.render.RenderOptimiser.should;

//? if >= 26 {
/*@Mixin(Lightmap.class)*/
//? } else {
@Mixin(LightTexture.class)
//? }
public class LightTextureMixin {

    //? if >= 26 {
    /*@ModifyExpressionValue(
            method = "getBrightness",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/dimension/DimensionType;ambientLight()F"
            )
    )
    private static float getAmbientLight(float original) {
        return should(RenderOptimiser.getFullBright()) ? 1.0f : original;
    }*/
    //? } else {
    @ModifyExpressionValue(
            method = "updateLightTexture",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/dimension/DimensionType;ambientLight()F"
            )
    )
    private float getAmbientLight(float original) {
        return should(RenderOptimiser.getFullBright()) ? 1.0f : original;
    }
    //? }
}
