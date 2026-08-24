package cop.module.impl.mining.cheats

import cop.api.events.PacketEvent
import cop.mixins.accessors.ItemInHandRendererAccessor
import cop.mixins.accessors.MultiPlayerGameModeAccessor
import cop.module.Module
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.world.item.ItemStack

/** Keeps an in-progress block break alive when Hypixel refreshes the held stack. */
object NoBreakReset : Module(
    "No Break Reset",
    desc = "Prevents held-item updates from resetting block-breaking progress and the hand animation.",
) {
    private val keepHandAnimation by switch(
        "Keep hand animation",
        true,
        desc = "Also prevents the first-person held item from dipping after an update.",
    )

    init {
        on<PacketEvent.ReceivedPost, ClientboundContainerSetSlotPacket> {
            if (mc.screen != null) return@on
            val p = mc.player ?: return@on
            val gameMode = mc.gameMode ?: return@on
            if (packet.containerId != 0 || !gameMode.isDestroying) return@on

            val slot = packet.slot
            if (slot !in 36..44 || p.inventory.selectedSlot != slot - 36) return@on

            // ReceivedPost runs after vanilla updates the inventory. Only repair
            // the destroy snapshot when this packet still represents the actual
            // selected stack; stale/out-of-order updates must not replace it.
            val currentStack = p.mainHandItem
            if (!ItemStack.isSameItemSameComponents(currentStack, packet.item)) return@on

            (gameMode as MultiPlayerGameModeAccessor).`cop$setDestroyingItem`(currentStack)
            if (keepHandAnimation) {
                (mc.entityRenderDispatcher.itemInHandRenderer as ItemInHandRendererAccessor)
                    .`cop$setMainHandItem`(currentStack)
            }
        }
    }
}
