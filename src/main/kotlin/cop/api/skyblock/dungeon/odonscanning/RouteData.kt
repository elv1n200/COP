package cop.api.skyblock.dungeon.odonscanning

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import cop.CopMod.logger

/**
 * Loader for the per-room secret-routes database used by the SecretRoutes
 * module. Sourced from `assets/cop/secretroutes/routes.json` and
 * `assets/cop/secretroutes/pearlroutes.json`, both bundled verbatim from
 * yourboykyle's [Secret Routes Mod](https://github.com/yourboykyle/SecretRoutes)
 * (see `CREDITS.md`). The bundled JSON keeps its original `#origin` /
 * `#copyright` keys, which the parser skips.
 *
 * Schema (per top-level key, e.g. `"Tombstone-2"`):
 *   - Key  is `"<RoomName>-<SecretIndex>"`. Multiple routes can share a key —
 *     each entry in the value array is one alternate path to the same secret.
 *   - Value is an array of [Route] objects, each containing:
 *     - `locations`     — list of walk waypoints
 *     - `etherwarps`    — list of blocks to etherwarp to
 *     - `mines`         — list of blocks to mine
 *     - `interacts`     — list of blocks to right-click (lever, button, etc.)
 *     - `tnts`          — list of blocks to place TNT on
 *     - `enderpearls`   — pearl-route only: list of pearl-launch destinations
 *     - `enderpearlangles` — pearl-route only: list of pearl-launch trajectories
 *     - `secret`        — the secret itself: `{ type: ..., location: [x,y,z] }`
 *
 * Coordinates are **relative** to the room's canonical (NORTH-facing) corner;
 * translate to world coords via
 * [cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom.getRealCoords].
 */
object RouteData {

    enum class SecretType { INTERACT, BAT, ITEM, CHEST, EXIT, UNKNOWN;
        companion object {
            fun parse(s: String?): SecretType = when (s?.lowercase()) {
                "interact" -> INTERACT
                "bat" -> BAT
                "item" -> ITEM
                "chest" -> CHEST
                "exit" -> EXIT
                else -> UNKNOWN
            }
        }
    }

    data class Secret(val type: SecretType, val location: BlockPos)

    /** Player look direction at the moment a pearl was thrown. Yaw is in the
     *  room's canonical orientation; rotate to world via [OdonRoom.getRealYaw]. */
    data class PitchYaw(val pitch: Float, val yaw: Float)

    data class Route(
        val locations: List<BlockPos>,
        val etherwarps: List<BlockPos>,
        val mines: List<BlockPos>,
        val interacts: List<BlockPos>,
        val tnts: List<BlockPos>,
        /** Pearl throw positions (player feet, fractional precision). Parallel
         *  to [pearlAngles]: pearls[i] is thrown with angle pearlAngles[i]. */
        val pearls: List<Vec3>,
        val pearlAngles: List<PitchYaw>,
        val secret: Secret?,
    )

    /** All routes for one secret. `secretIndex` is the suffix on the JSON key
     *  (`Tombstone-2` → 2). `routes` are the alternate paths to that secret. */
    data class RoomSecretRoutes(val secretIndex: Int, val routes: List<Route>)

    /** Keyed by [canonicalKey] (lowercase, alphanumeric-only) — the upstream
     *  route DB uses kebab-case (e.g. `Super-Tall`, `Arrow-Trap`) but COP's
     *  `odon_rooms.json` uses space-separated or concatenated (e.g. `Supertall`,
     *  `Arrow Trap`). Stripping all non-alphanumeric + lowercasing both sides
     *  makes the lookup work without an alias table. */
    private val byCanonicalName: Map<String, List<RoomSecretRoutes>> by lazy { load() }

    operator fun get(roomName: String?): List<RoomSecretRoutes> {
        if (roomName == null) return emptyList()
        return byCanonicalName[canonicalKey(roomName)].orEmpty()
    }

    private fun canonicalKey(s: String): String = buildString(s.length) {
        for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    private fun load(): Map<String, List<RoomSecretRoutes>> {
        val merged = mutableMapOf<String, MutableMap<Int, MutableList<Route>>>()
        readFile("/assets/cop/secretroutes/routes.json", merged)
        readFile("/assets/cop/secretroutes/pearlroutes.json", merged)

        return merged.mapValues { (_, bySecret) ->
            bySecret.entries.sortedBy { it.key }.map { (idx, routes) -> RoomSecretRoutes(idx, routes) }
        }
    }

    private fun readFile(path: String, into: MutableMap<String, MutableMap<Int, MutableList<Route>>>) {
        val stream = RouteData::class.java.getResourceAsStream(path) ?: run {
            logger.warn("SecretRoutes data missing: {}", path)
            return
        }
        try {
            val root = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
            for ((key, value) in root.entrySet()) {
                if (key.startsWith("#") || key == "Version") continue
                val routesArray = value as? JsonArray ?: continue
                val (roomName, secretIdx) = splitKey(key) ?: continue

                val parsed = routesArray.mapNotNull { (it as? JsonObject)?.let(::parseRoute) }
                if (parsed.isEmpty()) continue

                into.getOrPut(canonicalKey(roomName)) { mutableMapOf() }
                    .getOrPut(secretIdx) { mutableListOf() }
                    .addAll(parsed)
            }
        } catch (e: Exception) {
            logger.error("Failed to parse SecretRoutes file $path", e)
        }
    }

    private fun splitKey(key: String): Pair<String, Int>? {
        val dash = key.lastIndexOf('-')
        if (dash <= 0 || dash == key.length - 1) return null
        val idx = key.substring(dash + 1).toIntOrNull() ?: return null
        return key.substring(0, dash) to idx
    }

    private fun parseRoute(obj: JsonObject): Route = Route(
        locations = readPosList(obj, "locations"),
        etherwarps = readPosList(obj, "etherwarps"),
        mines = readPosList(obj, "mines"),
        interacts = readPosList(obj, "interacts"),
        tnts = readPosList(obj, "tnts"),
        pearls = readVec3List(obj, "enderpearls"),
        pearlAngles = readPitchYawList(obj, "enderpearlangles"),
        secret = parseSecret(obj.getAsJsonObject("secret")),
    )

    private fun parseSecret(obj: JsonObject?): Secret? {
        if (obj == null) return null
        val type = SecretType.parse(obj.get("type")?.asString)
        val loc = obj.get("location") as? JsonArray ?: return null
        val pos = readPos(loc) ?: return null
        return Secret(type, pos)
    }

    private fun readPosList(obj: JsonObject, key: String): List<BlockPos> {
        val arr = obj.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { (it as? JsonArray)?.let(::readPos) }
    }

    private fun readPos(arr: JsonArray): BlockPos? {
        if (arr.size() < 3) return null
        return try {
            BlockPos(arr.get(0).asInt, arr.get(1).asInt, arr.get(2).asInt)
        } catch (_: Exception) {
            null
        }
    }

    /** Pearl-throw positions are recorded as the player's exact feet position,
     *  so the x/y/z are fractional doubles. Keep that precision (a 0.7 offset
     *  matters for the trajectory preview). */
    private fun readVec3List(obj: JsonObject, key: String): List<Vec3> {
        val arr = obj.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { el ->
            val a = el as? JsonArray ?: return@mapNotNull null
            if (a.size() < 3) return@mapNotNull null
            try { Vec3(a.get(0).asDouble, a.get(1).asDouble, a.get(2).asDouble) } catch (_: Exception) { null }
        }
    }

    /** Pearl-throw angles are recorded as 2-element [pitch, yaw] arrays. */
    private fun readPitchYawList(obj: JsonObject, key: String): List<PitchYaw> {
        val arr = obj.getAsJsonArray(key) ?: return emptyList()
        return arr.mapNotNull { el ->
            val a = el as? JsonArray ?: return@mapNotNull null
            if (a.size() < 2) return@mapNotNull null
            try { PitchYaw(a.get(0).asFloat, a.get(1).asFloat) } catch (_: Exception) { null }
        }
    }
}
