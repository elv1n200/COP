package cop.api.events

import cop.api.events.core.Event
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.state.BlockState
import cop.api.skyblock.dungeon.P3Section
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom

abstract class DungeonEvent {
    class Start : Event()

    abstract class Secret {
        class Interact(val blockPos: BlockPos, val blockState: BlockState) : Event()
        class Item(val entity: ItemEntity) : Event()
        class Bat(val packet: ClientboundSoundPacket) : Event()
    }

    abstract class Room {
        class Enter(val room: OdonRoom?) : Event()
        class Scan(val room: OdonRoom) : Event()
    }

    class SectionComplete(val section: P3Section) : Event() {
        class Full(section: P3Section) : Event()
    }
}