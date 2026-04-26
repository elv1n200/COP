package cop.module.impl.dungeon

import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket
import cop.api.events.PacketEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.utils.skyblock.player.PlayerUtils

object ShadowAssassinAlert : Module(
    "Shadow Assassin Alert",
    area = Island.Dungeon,
    desc = "Sends an alert when SA jumps you."
) {
    init {
        on<PacketEvent.Received, ClientboundInitializeBorderPacket> {
            if (((Dungeon.isFloor(3) || Dungeon.isFloor(2)) && Dungeon.inBoss)) return@on
            PlayerUtils.setTitle("", "§aShadow Assassin!", playSound = true, stayAlive = 35, fadeOut = 0)
        }
    }
}