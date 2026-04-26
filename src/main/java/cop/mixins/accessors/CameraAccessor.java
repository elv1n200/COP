package cop.mixins.accessors;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * `Camera.position` was a public field through 1.21.10 but became private in
 * 1.21.11 and Mojang did *not* add a `getPosition()` getter — code accessing
 * the camera position from outside has to go through a mixin accessor on both
 * versions, so we route everything through this interface for source parity.
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("position")
    Vec3 getPosition();
}
