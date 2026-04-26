package cop.mixins;

import cop.mixininterfaces.IGuiMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(net.minecraft.client.GuiMessage.class)
public abstract class GuiMessage implements IGuiMessage {
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
