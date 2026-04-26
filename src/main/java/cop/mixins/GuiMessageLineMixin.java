package cop.mixins;

import cop.mixininterfaces.IGuiMessage;
import net.minecraft.client.GuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// https://github.com/MeteorDevelopment/meteor-client/blob/master/src/main/java/meteordevelopment/meteorclient/mixin/ChatHudLineVisibleMixin.java

@Mixin(GuiMessage.Line.class)
public abstract class GuiMessageLineMixin implements IGuiMessage {
    @Unique
    private int cop$id;

    @Override
    public int cop$getId() {
        return cop$id;
    }

    @Override
    public void cop$setId(int id) {
        this.cop$id = id;
    }
}