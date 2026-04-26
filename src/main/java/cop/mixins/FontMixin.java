package cop.mixins;

import cop.module.impl.misc.CatMode;
import cop.module.impl.render.NickHider;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public class FontMixin {
    // 1.21.11 added a second boolean (outline) to the FormattedCharSequence
    // overload of `prepareText`; the descriptor selector below has to match the
    // exact signature on each version or mixin won't bind it at runtime.
    @ModifyVariable(
            at = @At("HEAD"),
            //? if >= 1.21.11
            /*method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",*/
            //? if <= 1.21.10
            method = "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            ordinal = 0,
            argsOnly = true
    )
    public FormattedCharSequence prepareOrderedText(FormattedCharSequence value) {
        return NickHider.replaceName(CatMode.replaceText(value));
    }

    @ModifyVariable(
            at = @At("HEAD"),
            method = "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            ordinal = 0,
            argsOnly = true
    )
    public String prepareString(String value) {
        return NickHider.replaceName(CatMode.replaceText(value));
    }

    @ModifyVariable(
            at = @At("HEAD"),
            method = "width(Lnet/minecraft/util/FormattedCharSequence;)I",
            ordinal = 0,
            argsOnly = true
    )
    public FormattedCharSequence getWidth(FormattedCharSequence value) {
        return NickHider.replaceName(CatMode.replaceText(value));
    }

    @ModifyVariable(
            at = @At("HEAD"),
            method = "width(Ljava/lang/String;)I",
            ordinal = 0,
            argsOnly = true
    )
    public String getWidth(String value) {
        return NickHider.replaceName(CatMode.replaceText(value));
    }
}
