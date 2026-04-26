package cop.mixins;

import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import cop.api.events.KeyEvent;
import cop.api.input.MutableInput;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    @Redirect(
            method = "tick",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/player/KeyboardInput;keyPresses:Lnet/minecraft/world/entity/player/Input;",
                    opcode = Opcodes.PUTFIELD
            )
    )
    private void onTick(KeyboardInput instance, Input input) {
        KeyEvent.Input event = new KeyEvent.Input(input, new MutableInput(input));
        event.post();
        instance.keyPresses = event.getInput().toInput();
    }
}
