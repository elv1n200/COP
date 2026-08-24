package cop.mixins.accessors;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * `Camera.position` was a public field through 1.21.10 but became private in
 * newer versions. 26.1.2 exposes `position()`, while the shared older sources
 * still use this accessor; keeping the access here preserves source parity.
 *
 * @author elvin
 */
@Mixin(Camera.class)
public interface CameraAccessor {
    @Accessor("position")
    Vec3 getPosition();

    /** Uses Camera's own lifecycle method so its cached block position stays in sync. */
    @Invoker("setPosition")
    void cop$invokeSetPosition(Vec3 position);
}
