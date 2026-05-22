package cop.mixins;

import cop.module.impl.render.RenderOptimiser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.state.GuiTextRenderState;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.FormattedCharSequence;
//? if >= 1.21.11 {
/*import org.joml.Matrix3x2fc;
*///?}
import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static cop.module.impl.render.RenderOptimiser.should;

/**
 * Disables (or force-enables, in containers) text shadow by redirecting the
 * {@code GuiTextRenderState} construction inside the {@code drawString}/{@code
 * text} render path.
 *
 * 1.21.11 added a second boolean arg to {@code GuiTextRenderState}'s ctor and
 * switched the matrix parameter from the concrete {@code Matrix3x2f} to the
 * read-only {@code Matrix3x2fc} interface, so the two versions need separate
 * method bodies. 26.x renamed {@code GuiGraphics}->{@code GuiGraphicsExtractor},
 * {@code drawString}->{@code text} and moved {@code GuiTextRenderState} to
 * {@code net.minecraft.client.renderer.state.gui} (all handled by Stonecutter
 * replacements), but the >=1.21.11 ctor shape is unchanged so that branch still
 * applies. Class is named {@code TextShadowMixin} (not {@code GuiGraphicsMixin})
 * so the bare {@code GuiGraphics} replacement can't clobber the class name.
 */
@Mixin(GuiGraphics.class)
public class TextShadowMixin {

    @Redirect(
            method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At(
                    value = "NEW",
                    target = "Lnet/minecraft/client/gui/render/state/GuiTextRenderState;"
            )
    )
    //? if >= 1.21.11 {
    /*private GuiTextRenderState disableShadow(Font textRenderer, FormattedCharSequence orderedText, Matrix3x2fc matrix, int x, int y, int color, int backgroundColor, boolean shadow, boolean outline, ScreenRectangle clipBounds) {
        boolean finalShadow = computeShadow(shadow);
        return new GuiTextRenderState(textRenderer, orderedText, matrix, x, y, color, backgroundColor, finalShadow, outline, clipBounds);
    }
    *///? } else {
    private GuiTextRenderState disableShadow(Font textRenderer, FormattedCharSequence orderedText, Matrix3x2f matrix, int x, int y, int color, int backgroundColor, boolean shadow, ScreenRectangle clipBounds) {
        boolean finalShadow = computeShadow(shadow);
        return new GuiTextRenderState(textRenderer, orderedText, matrix, x, y, color, backgroundColor, finalShadow, clipBounds);
    }
    //? }

    private static boolean computeShadow(boolean originalShadow) {
        boolean disableShadows = should(RenderOptimiser.getDisableTextShadow());
        boolean forceContainerShadows = should(RenderOptimiser.getContainerTextShadow());
        boolean inContainer = Minecraft.getInstance().screen instanceof AbstractContainerScreen;

        if (inContainer && forceContainerShadows) return true;
        if (disableShadows) return false;
        return originalShadow;
    }
}
