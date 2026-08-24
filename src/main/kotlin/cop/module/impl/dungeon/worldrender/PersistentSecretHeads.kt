package cop.module.impl.dungeon.worldrender

import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.DungeonEvent
import cop.api.events.RenderEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.odonscanning.ScanUtils
import cop.api.skyblock.dungeon.odonscanning.SecretCoords
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom
import cop.api.skyblock.dungeon.odonscanning.tiles.Rotations
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import cop.utils.render.drawWireFrameBox
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

object PersistentSecretHeads : Module(
    "Persistent Secret Heads",
    area = Island.Dungeon(inClear = true),
    desc = "Keeps local markers on redstone-key and wither-essence heads in the current room.",
) {
    private val showRedstoneKeys by switch("Redstone keys", true)
    private val redstoneKeyColour by colourPicker(
        "Redstone key colour",
        Colour.RED.withAlpha(0.38f),
        allowAlpha = true,
    ).childOf(::showRedstoneKeys)

    private val showWitherEssence by switch("Wither essence", true)
    private val witherEssenceColour by colourPicker(
        "Wither essence colour",
        Colour.PURPLE.withAlpha(0.38f),
        allowAlpha = true,
    ).childOf(::showWitherEssence)

    private val renderDistance by slider(
        "Render distance",
        64.0,
        8.0,
        256.0,
        4.0,
        unit = "blocks",
    )
    private val throughWalls by switch(
        "Through walls",
        true,
        desc = "Draws markers without terrain depth testing.",
    )
    private val outline by switch("Outline", true)
    private val outlineWidth by slider(
        "Outline width",
        2.0f,
        0.5f,
        8.0f,
        0.5f,
    ).childOf(::outline)

    private enum class HeadKind { REDSTONE_KEY, WITHER_ESSENCE }

    private data class Marker(val pos: BlockPos, val kind: HeadKind)

    private data class RoomTransform(
        val name: String,
        val rotation: Rotations,
        val origin: BlockPos,
    )

    @Volatile
    private var activeRoom: OdonRoom? = null

    @Volatile
    private var markers: List<Marker> = emptyList()

    private var markerTransform: RoomTransform? = null

    init {
        on<DungeonEvent.Room.Enter> {
            activeRoom = room
            rebuildMarkers(room)
        }

        on<DungeonEvent.Room.Scan> {
            if (room === activeRoom) rebuildMarkers(room)
        }

        on<WorldEvent.Change> {
            clearRoom()
        }

        on<RenderEvent.World> {
            val playerPosition = mc.player?.position() ?: return@on
            val distanceSquared = renderDistance * renderDistance
            val depth = !throughWalls

            markers.forEach { marker ->
                val colour = when (marker.kind) {
                    HeadKind.REDSTONE_KEY -> if (showRedstoneKeys) redstoneKeyColour else return@forEach
                    HeadKind.WITHER_ESSENCE -> if (showWitherEssence) witherEssenceColour else return@forEach
                }
                if (colour.alpha <= 0) return@forEach
                if (Vec3.atCenterOf(marker.pos).distanceToSqr(playerPosition) > distanceSquared) return@forEach

                val box = marker.pos.aabb.inflate(0.015)
                ctx.drawFilledBox(box, colour, depth = depth)
                if (outline) {
                    ctx.drawWireFrameBox(
                        box,
                        colour.withAlpha(1.0f),
                        thickness = outlineWidth,
                        depth = depth,
                    )
                }
            }
        }
    }

    override fun onEnable() {
        super.onEnable()
        activeRoom = ScanUtils.currentRoom
        rebuildMarkers(activeRoom)
    }

    override fun onDisable() {
        clearRoom()
        super.onDisable()
    }

    private fun rebuildMarkers(room: OdonRoom?) {
        if (room == null || room.rotation == Rotations.NONE) {
            markers = emptyList()
            markerTransform = null
            return
        }

        val transform = RoomTransform(room.name, room.rotation, room.clayPos)
        if (transform == markerTransform) return

        val secrets = SecretCoords[room.name]
        markers = buildList {
            secrets.redstoneKey.forEach { relative ->
                add(Marker(room.getRealCoords(relative), HeadKind.REDSTONE_KEY))
            }
            secrets.wither.forEach { relative ->
                add(Marker(room.getRealCoords(relative), HeadKind.WITHER_ESSENCE))
            }
        }.distinctBy { it.pos to it.kind }
        markerTransform = transform
    }

    private fun clearRoom() {
        activeRoom = null
        markers = emptyList()
        markerTransform = null
    }
}
