package cop.mixins;

import cop.module.impl.player.CameraHelper;
import cop.module.impl.player.Tweaks;
import cop.module.impl.player.cheats.NoRotate;
import cop.mixins.accessors.CameraAccessor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static cop.module.impl.render.RenderOptimiser.should;

@Mixin(Camera.class)
public class CameraMixin {
    @Unique
    private boolean wasSneaking = false;

    @Shadow
    private float eyeHeight;

    @Shadow
    private Entity entity;

    @Shadow
    private float eyeHeightOld;

    @Inject(
            method = "update",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;alignWithEntity(F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void cop$zeroPingCamera(DeltaTracker deltaTracker, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.getCameraType().isFirstPerson()) return;
        var predicted = NoRotate.predictedCameraPosition();
        if (predicted != null) ((CameraAccessor) this).cop$invokeSetPosition(predicted);
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Camera;eyeHeight:F",
                    opcode = Opcodes.PUTFIELD
            )
    )
    public void legacySneak(Camera obj, float value) {
        if (entity instanceof Player && should(Tweaks.getInstantSneak())) {
            if (entity.getPose() == Pose.CROUCHING) {
                wasSneaking = true;
                eyeHeightOld = eyeHeight = entity.getEyeHeight();
                return;
            } else if (wasSneaking) {
                wasSneaking = false;
                eyeHeightOld = eyeHeight = entity.getEyeHeight();
                return;
            }
        }
        this.eyeHeight = value;
    }

    /**
     * Short-circuits {@code Camera.getMaxZoom} for the CameraHelper module:
     *  - "Custom distance" returns the user's configured distance
     *  - "Camera clip" returns the requested {@code startingDistance} as-is
     *    (skipping the wall-raycast that normally pushes the camera back).
     *  Custom distance wins over clip when both are enabled.
     */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void cop$cameraHelperZoom(float startingDistance, CallbackInfoReturnable<Float> cir) {
        if (!CameraHelper.INSTANCE.getEnabled()) return;
        if (CameraHelper.getUseCustomDistance()) {
            cir.setReturnValue(CameraHelper.getCustomDistance());
            return;
        }
        if (CameraHelper.getNoClip()) {
            cir.setReturnValue(startingDistance);
        }
    }
}
