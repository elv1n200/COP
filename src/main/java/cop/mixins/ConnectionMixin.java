package cop.mixins;

import cop.api.events.core.EventBus;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class ConnectionMixin {
    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cop$receivePacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (EventBus.onPacketReceived(packet)) ci.cancel();
    }

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("TAIL")
    )
    private void cop$receivePacketPost(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        EventBus.onPacketReceivedPost(packet);
    }

    @Inject(
            method = "sendPacket",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cop$sendImmediately(Packet<?> packet, ChannelFutureListener channelFutureListener, boolean flush, CallbackInfo ci) {
        if (EventBus.onPacketSent(packet)) ci.cancel();
    }
}
