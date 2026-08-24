package cop.mixins;

import cop.api.events.ChatEvent;
import cop.api.skyblock.dungeon.odonscanning.MapRenderer;
import cop.module.impl.player.Tweaks;
import cop.module.impl.player.cheats.NoRotate;
import cop.mixins.accessors.LocalPlayerAccessor;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static cop.module.impl.render.RenderOptimiser.should;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Unique
    private float cop$preTeleportYaw;
    @Unique
    private float cop$preTeleportPitch;
    @Unique
    private float cop$preTeleportYawOld;
    @Unique
    private float cop$preTeleportPitchOld;
    @Unique
    private float cop$preTeleportHeadYaw;
    @Unique
    private float cop$preTeleportHeadYawOld;
    @Unique
    private float cop$preTeleportBodyYaw;
    @Unique
    private float cop$preTeleportBodyYawOld;
    @Unique
    private float cop$preTeleportLastYaw;
    @Unique
    private float cop$preTeleportLastPitch;
    @Unique
    private boolean cop$preserveTeleportRotation;

    @Inject(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void cop$captureTeleportRotation(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        cop$capturePlayerRotation();
    }

    /**
     * Restore before vanilla constructs its PosRot acknowledgement. This keeps
     * the client view and the rotation sent back to the server identical.
     */
    @Inject(
            method = "handleMovePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getYRot()F"
            )
    )
    private void cop$restoreTeleportRotationBeforeAck(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        cop$restorePlayerRotation();
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void cop$restoreTeleportRotation(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        cop$restorePlayerRotation();
        cop$clearRotationSnapshot();
        NoRotate.onTeleportApplied();
    }

    @Inject(
            method = "handleRotatePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void cop$captureRotationCorrection(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        cop$capturePlayerRotation();
    }

    @Inject(
            method = "handleRotatePlayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;getYRot()F"
            )
    )
    private void cop$restoreRotationCorrectionBeforeAck(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        cop$restorePlayerRotation();
    }

    @Inject(method = "handleRotatePlayer", at = @At("TAIL"))
    private void cop$finishRotationCorrection(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
        cop$restorePlayerRotation();
        cop$clearRotationSnapshot();
        NoRotate.onTeleportApplied();
    }

    @Unique
    private void cop$capturePlayerRotation() {
        LocalPlayer player = Minecraft.getInstance().player;
        cop$preserveTeleportRotation = player != null && NoRotate.shouldPreserveRotation();
        if (!cop$preserveTeleportRotation) return;

        LocalPlayerAccessor accessor = (LocalPlayerAccessor) player;
        cop$preTeleportYaw = player.getYRot();
        cop$preTeleportPitch = player.getXRot();
        cop$preTeleportYawOld = player.yRotO;
        cop$preTeleportPitchOld = player.xRotO;
        cop$preTeleportHeadYaw = player.yHeadRot;
        cop$preTeleportHeadYawOld = player.yHeadRotO;
        cop$preTeleportBodyYaw = player.yBodyRot;
        cop$preTeleportBodyYawOld = player.yBodyRotO;
        cop$preTeleportLastYaw = accessor.getLastYawClient();
        cop$preTeleportLastPitch = accessor.getLastPitchClient();
    }

    @Unique
    private void cop$restorePlayerRotation() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!cop$preserveTeleportRotation || player == null) return;

        player.setYRot(cop$preTeleportYaw);
        player.setXRot(cop$preTeleportPitch);
        player.yRotO = cop$preTeleportYawOld;
        player.xRotO = cop$preTeleportPitchOld;
        player.setYHeadRot(cop$preTeleportHeadYaw);
        player.yHeadRotO = cop$preTeleportHeadYawOld;
        player.setYBodyRot(cop$preTeleportBodyYaw);
        player.yBodyRotO = cop$preTeleportBodyYawOld;

        LocalPlayerAccessor accessor = (LocalPlayerAccessor) player;
        accessor.cop$setLastYawClient(cop$preTeleportLastYaw);
        accessor.cop$setLastPitchClient(cop$preTeleportLastPitch);
    }

    @Unique
    private void cop$clearRotationSnapshot() {
        cop$preserveTeleportRotation = false;
    }

    @Inject(method = "handleMapItemData", at = @At("TAIL"))
    private void onMapItemData(ClientboundMapItemDataPacket packet, CallbackInfo ci) {
        MapRenderer.INSTANCE.update(packet);
    }

    @Inject(
            method = "sendChat(Ljava/lang/String;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onSendChatMessage(String message, CallbackInfo ci) {
        if (new ChatEvent.Sent(message, false).post()) ci.cancel();
    }

    @Inject(
            method = "sendCommand",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onSendChatCommand(String command, CallbackInfo ci) {
        if (new ChatEvent.Sent(command, true).post()) ci.cancel();
    }

    @Inject(
            method = "handleSetEntityData",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V"
            )
    )
    private void onEntityTrackerUpdate(ClientboundSetEntityDataPacket packet, CallbackInfo ci, @Local Entity entity) {
        if(!entity.equals(Minecraft.getInstance().player) || !should(Tweaks.getFixDoubleSneak())) return;
        packet.packedItems().removeIf(entry -> entry.serializer().equals(EntityDataSerializers.POSE));
    }
}
