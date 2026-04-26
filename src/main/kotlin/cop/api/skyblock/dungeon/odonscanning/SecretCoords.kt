package cop.api.skyblock.dungeon.odonscanning

import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import cop.CopMod.logger

/**
 * Catalogue of per-room secret coordinates, parsed from `assets/cop/rooms.json`.
 *
 * Each room has five secret-type buckets (relative coords inside the room):
 *   - `redstoneKey`  — secret head that's actually a key
 *   - `wither`       — wither essence head
 *   - `bat`          — bat spawn position
 *   - `item`         — item drops on the floor
 *   - `chest`        — trap/secret chests
 *
 * Unlike `ScanUtils` (which reads `odon_rooms.json` for scanning), this lookup
 * is used by routing / secret-finding modules (PersistentSecretHeads, SecretRoutes)
 * that need to know WHERE secrets are in each room — not just which room you're in.
 *
 * Coordinates are **relative** to the room corner; use
 * [cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom.getRealCoords] to
 * translate them to world positions.
 */
object SecretCoords {
    data class RoomSecrets(
        val redstoneKey: List<BlockPos>,
        val wither: List<BlockPos>,
        val bat: List<BlockPos>,
        val item: List<BlockPos>,
        val chest: List<BlockPos>,
    ) {
        companion object {
            val EMPTY = RoomSecrets(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    private val byRoomName: Map<String, RoomSecrets> by lazy { load() }

    operator fun get(roomName: String?): RoomSecrets {
        if (roomName == null) return RoomSecrets.EMPTY
        return byRoomName[roomName] ?: RoomSecrets.EMPTY
    }

    private fun load(): Map<String, RoomSecrets> = try {
        val stream = SecretCoords::class.java.getResourceAsStream("/assets/cop/rooms.json")
            ?: return emptyMap<String, RoomSecrets>().also {
                logger.warn("rooms.json not found; SecretCoords lookup empty.")
            }

        val root = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonArray
        buildMap {
            root.forEach { element ->
                val obj = element.asJsonObject
                val name = obj.get("name")?.asString ?: return@forEach
                val coords = obj.getAsJsonObject("secretCoords") ?: return@forEach
                put(
                    name,
                    RoomSecrets(
                        redstoneKey = readCoordList(coords, "redstoneKey"),
                        wither = readCoordList(coords, "wither"),
                        bat = readCoordList(coords, "bat"),
                        item = readCoordList(coords, "item"),
                        chest = readCoordList(coords, "chest"),
                    )
                )
            }
        }
    } catch (e: Exception) {
        logger.error("Failed to parse rooms.json secret coords", e)
        emptyMap()
    }

    private fun readCoordList(obj: com.google.gson.JsonObject, key: String): List<BlockPos> {
        val arr = obj.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el.asJsonObject
            val x = o.get("x")?.asInt ?: return@mapNotNull null
            val y = o.get("y")?.asInt ?: return@mapNotNull null
            val z = o.get("z")?.asInt ?: return@mapNotNull null
            BlockPos(x, y, z)
        }
    }
}
