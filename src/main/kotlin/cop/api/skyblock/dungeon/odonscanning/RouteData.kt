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
 * ## Schema (correctly understood)
 *
 * Top-level keys are `"<RoomName>-<VariantId>"` — examples: `Tombstone-2`,
 * `Waterfall-8`, `Withermancers-4:1`, `Blaze-Room-1-High`. The variant ID
 * is whatever follows the last `-`; we keep it as a *string* because the
 * upstream uses ints (`2`), int suffixes (`4:1`), and bare words (`High`,
 * `Low`).
 *
 * **The value array is a SEQUENCE of [Step]s**, not alternates — each entry
 * is one secret you collect, in order. Walk to step 0's secret, click/pick
 * it, walk to step 1's secret, etc., until the variant's steps run out.
 * Different `"RoomName-X"` keys for the same room are separate route
 * VARIANTS the player can choose between (e.g. a short 2-secret route vs
 * a full 8-secret clear). Each variant is an independent sequence.
 *
 * ## Step fields
 *
 *   - `locations`        — list of walk waypoints from "previous step end"
 *                          to this step's secret
 *   - `etherwarps`       — etherwarp targets along the way
 *   - `mines`            — blocks to mine
 *   - `interacts`        — blocks to right-click (lever, button, ...)
 *   - `tnts`             — blocks to TNT
 *   - `enderpearls`      — pearl-throw positions (player feet, fractional)
 *   - `enderpearlangles` — `[pitch, yaw]` for each pearl
 *   - `secret`           — the secret itself, `{ type: ..., location: [x,y,z] }`
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

    /** One secret in a route's sequence — its walk-path, action waypoints,
     *  pearls, and the secret target. */
    data class Step(
        val locations: List<BlockPos>,
        val etherwarps: List<BlockPos>,
        val mines: List<BlockPos>,
        val interacts: List<BlockPos>,
        val tnts: List<BlockPos>,
        val pearls: List<Vec3>,
        val pearlAngles: List<PitchYaw>,
        val secret: Secret?,
    )

    /** A chosen-by-the-player route through a room: ordered sequence of [Step]s.
     *  Multiple variants can exist per room (short vs full-clear, high vs low
     *  pearl variants, etc.) — the SecretRoutes module picks one to follow. */
    data class RouteVariant(val variantId: String, val steps: List<Step>)

    /** Keyed by [canonicalKey] (lowercase, alphanumeric-only) — the upstream
     *  route DB uses kebab-case (e.g. `Super-Tall`, `Arrow-Trap`) but COP's
     *  `odon_rooms.json` uses space-separated or concatenated (e.g. `Supertall`,
     *  `Arrow Trap`). Stripping all non-alphanumeric + lowercasing both sides
     *  makes the lookup work without an alias table. */
    private val byCanonicalName: Map<String, List<RouteVariant>> by lazy { load() }

    operator fun get(roomName: String?): List<RouteVariant> {
        if (roomName == null) return emptyList()
        return byCanonicalName[canonicalKey(roomName)].orEmpty()
    }

    private fun canonicalKey(s: String): String = buildString(s.length) {
        for (c in s) if (c.isLetterOrDigit()) append(c.lowercaseChar())
    }

    private fun load(): Map<String, List<RouteVariant>> {
        // (canonicalRoom -> (variantId -> stepList)).
        // We tolerate the same `"RoomName-VariantId"` key appearing in BOTH
        // routes.json and pearlroutes.json by taking the LONGER step list
        // (pearl routes are typically more complete because they need every
        // pearl-throw position recorded). Same-key collisions within a single
        // file shouldn't happen, but if they do we likewise prefer the longer.
        val merged = mutableMapOf<String, MutableMap<String, List<Step>>>()
        readFile("/assets/cop/secretroutes/routes.json", merged)
        readFile("/assets/cop/secretroutes/pearlroutes.json", merged)

        return merged.mapValues { (_, byVariant) ->
            byVariant.entries
                .sortedByDescending { it.value.size }   // longer variants first → default pick covers more secrets
                .map { (id, steps) -> RouteVariant(id, steps) }
        }
    }

    private fun readFile(path: String, into: MutableMap<String, MutableMap<String, List<Step>>>) {
        val stream = RouteData::class.java.getResourceAsStream(path) ?: run {
            logger.warn("SecretRoutes data missing: {}", path)
            return
        }
        try {
            val root = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
            for ((key, value) in root.entrySet()) {
                if (key.startsWith("#") || key == "Version") continue
                val arr = value as? JsonArray ?: continue
                val (roomName, variantId) = splitKey(key) ?: continue

                val steps = arr.mapNotNull { (it as? JsonObject)?.let(::parseStep) }
                if (steps.isEmpty()) continue

                val canon = canonicalKey(roomName)
                val byVariant = into.getOrPut(canon) { mutableMapOf() }
                val existing = byVariant[variantId]
                if (existing == null || steps.size > existing.size) {
                    byVariant[variantId] = steps
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse SecretRoutes file $path", e)
        }
    }

    /** Splits `"Tombstone-2"` → `("Tombstone", "2")`, `"Withermancers-4:1"` →
     *  `("Withermancers", "4:1")`, `"Blaze-Room-1-High"` → `("Blaze-Room-1", "High")`.
     *  The variant ID stays a string so non-integer suffixes parse correctly. */
    private fun splitKey(key: String): Pair<String, String>? {
        val dash = key.lastIndexOf('-')
        if (dash <= 0 || dash == key.length - 1) return null
        return key.substring(0, dash) to key.substring(dash + 1)
    }

    private fun parseStep(obj: JsonObject): Step = Step(
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
