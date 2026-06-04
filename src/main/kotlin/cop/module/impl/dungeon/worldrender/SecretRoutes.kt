package cop.module.impl.dungeon.worldrender

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.DungeonEvent
import cop.api.events.RenderEvent
import cop.api.events.WorldEvent
import cop.api.input.CatKeys
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.inBoss
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.dungeon.odonscanning.RouteData
import cop.api.skyblock.dungeon.odonscanning.RouteData.PitchYaw
import cop.api.skyblock.dungeon.odonscanning.RouteData.SecretType
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.ChatUtils.literal
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import cop.utils.render.drawLine
import cop.utils.render.drawText
import cop.utils.render.drawWireFrameBox
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders secret routes for the current dungeon room as world-space waypoints.
 *
 * Route data ships with COP at `assets/cop/secretroutes/{routes,pearlroutes}.json`,
 * sourced from yourboykyle's Secret Routes Mod (see `CREDITS.md`). Coordinates in
 * the DB are room-canonical; on room enter we look them up by [OdonRoom.name] and
 * pre-translate them to world coords via [OdonRoom.getRealCoords], so the render
 * loop is just iteration + draw calls.
 *
 * By default only the route to the **nearest still-uncollected secret** in the
 * room is drawn — most rooms have 2–8 secrets and rendering every alternate for
 * every secret simultaneously is visually overwhelming. As you complete secrets
 * (head clicked, bat killed, item picked up) the active route auto-advances to
 * the next nearest one. Flip [showAllSecrets] on for the whole-room overview.
 *
 * Display only. There is no playback, no auto-walk, no clicking. The original COP
 * port that *did* play routes back was removed (commit `9cdbbf9`) for being too
 * fragile; this module is the simpler "show me where to go" replacement.
 */
object SecretRoutes : Module(
    "Secret Routes",
    area = Island.Dungeon(inClear = true),
    desc = "Renders secret waypoints + tracking line for the current dungeon room."
) {
    // -- Settings -----------------------------------------------------------

    private val showAllSecrets by switch(
        "Show all secrets", false,
        desc = "On: every secret's route in the room is rendered at once (busy view). Off: only the route to the secret closest to you."
    )
    private val nextSecretKey = keybind(
        "Skip current secret", CatKeys.KEY_NONE,
        desc = "Manually mark the currently-displayed secret as done so the route jumps to the next nearest one. Useful when auto-advance misses (chest secrets, weird lever positions, etc.)."
    ).onPress { skipCurrentSecret() }.also { register(it) }
    private val showAllAlternates by switch(
        "Show alternates", false,
        desc = "When a secret has multiple known routes, show all of them instead of just the one whose first waypoint is closest to you."
    )
    private val autoAdvance by switch(
        "Auto-advance", true,
        desc = "Mark secrets you've already collected as done (via secret-interact / item-pickup / bat-kill events) so the active route auto-switches to the next nearest one."
    )
    private val showBeacon by switch(
        "Beacon beam", true,
        desc = "Draw a tall translucent vertical column on each secret target so you can spot them through walls from across the room."
    )
    private val showPearls by switch(
        "Pearl trajectories", true,
        desc = "Render pearl-throw positions + a line along the look angle showing where to aim the ender pearl. Comes from the pearl-route DB (a few rooms have these)."
    )
    private val showStartMarker by switch(
        "Mark start", true,
        desc = "Draw a wireframe box at the first waypoint of each rendered route so you can tell where to begin."
    )
    private val showStartLabel by switch(
        "Label start", true,
        desc = "Float a \"Start\" text label above the start marker."
    )
    private val showWaypointNumbers by switch(
        "Number waypoints", true,
        desc = "Show 1, 2, 3, ... above each walking waypoint so the order to follow is obvious."
    )
    private val lineThickness by slider(
        "Line thickness", 3.0f, 0.5f, 10.0f, 0.1f,
        desc = "Polyline thickness in pixels."
    )
    private val pearlLineThickness by slider(
        "Pearl line thickness", 2.5f, 0.5f, 10.0f, 0.1f,
        desc = "Thickness of pearl-trajectory preview lines."
    )
    private val startThickness by slider(
        "Start outline thickness", 4.0f, 0.5f, 10.0f, 0.1f,
        desc = "Thickness of the start-marker box outline."
    )
    private val throughWalls by switch(
        "Through walls", true,
        desc = "Render through terrain (no depth test)."
    )

    private val lineColour by colourPicker(
        "Line", Colour.CYAN.withAlpha(0.85f), allowAlpha = true,
        desc = "Walking path between locations."
    )
    private val startColour by colourPicker(
        "Start", Colour.LIME.withAlpha(0.9f), allowAlpha = true,
        desc = "Wireframe box at the route's first waypoint."
    )
    private val etherwarpColour by colourPicker(
        "Etherwarp", Colour.BLUE.withAlpha(0.35f), allowAlpha = true,
        desc = "Etherwarp target block."
    )
    private val mineColour by colourPicker(
        "Mine", Colour.YELLOW.withAlpha(0.35f), allowAlpha = true,
        desc = "Block to mine."
    )
    private val interactColour by colourPicker(
        "Interact", Colour.PINK.withAlpha(0.35f), allowAlpha = true,
        desc = "Block to right-click (lever, button, ...)."
    )
    private val tntColour by colourPicker(
        "TNT", Colour.ORANGE.withAlpha(0.35f), allowAlpha = true,
        desc = "Block to place TNT on / Superboom."
    )
    private val pearlColour by colourPicker(
        "Pearl", Colour.CYAN.withAlpha(0.85f), allowAlpha = true,
        desc = "Pearl throw position marker + trajectory preview line."
    )

    // Per-secret-type target colours — knowing the type at a glance tells you
    // what to do (chest → open, bat → wait, item → walk to ground, etc.).
    private val secretInteractColour by colourPicker(
        "Secret: interact", Colour.RED.withAlpha(0.45f), allowAlpha = true,
        desc = "Secret target — interact head."
    )
    private val secretBatColour by colourPicker(
        "Secret: bat", Colour.MAGENTA.withAlpha(0.45f), allowAlpha = true,
        desc = "Secret target — bat spawn spot."
    )
    private val secretItemColour by colourPicker(
        "Secret: item", Colour.LIME.withAlpha(0.45f), allowAlpha = true,
        desc = "Secret target — item drop on the floor."
    )
    private val secretChestColour by colourPicker(
        "Secret: chest", Colour.ORANGE.withAlpha(0.55f), allowAlpha = true,
        desc = "Secret target — chest to open."
    )
    private val secretExitColour by colourPicker(
        "Secret: exit", Colour.PURPLE.withAlpha(0.45f), allowAlpha = true,
        desc = "Secret target — room exit."
    )

    // -- Pre-translated route state ----------------------------------------

    private data class WorldPearl(val throwPos: Vec3, val angle: PitchYaw)

    private data class WorldRoute(
        val locations: List<Vec3>,
        val etherwarps: List<BlockPos>,
        val mines: List<BlockPos>,
        val interacts: List<BlockPos>,
        val tnts: List<BlockPos>,
        val pearls: List<WorldPearl>,
        val secret: WorldSecret?,
    )

    private data class WorldSecret(val type: SecretType, val pos: BlockPos)

    /** Grouped by secret index so "single nearest secret" + "alternates" filters work. */
    private class RoutesForSecret(val secretIndex: Int, val alternates: List<WorldRoute>) {
        /** Anchor used for "which secret is closest to the player" — prefer the
         *  secret target itself (the thing you're trying to reach); fall back to
         *  the first waypoint of any alternate if no secret position is recorded. */
        val anchor: Vec3? = alternates.firstNotNullOfOrNull { it.secret?.let { s -> Vec3.atCenterOf(s.pos) } }
            ?: alternates.firstNotNullOfOrNull { it.locations.firstOrNull() }
        /** Flipped to true by auto-advance event hooks (or the per-frame block
         *  re-check for INTERACT-head secrets, or the manual "next" keybind).
         *  Once true the group is skipped for both "nearest" pick and target-box
         *  rendering. @Volatile because the event handlers are dispatched on the
         *  network thread. */
        @Volatile var collected: Boolean = false
        /** True only if this group's secret target was an actual PLAYER_HEAD
         *  block at room-enter time. Used to gate the per-frame "block missing
         *  => collected" check — INTERACT secrets are commonly levers / buttons
         *  / chests where polling for PLAYER_HEAD absence would mark them
         *  collected immediately. Set in [onRoomEnter] for INTERACT/UNKNOWN
         *  types only. */
        var pollableAsHead: Boolean = false
    }

    private val activeRoutes = CopyOnWriteArrayList<RoutesForSecret>()

    /** Persistent across the current dungeon run: `(roomName, secretIndex)` of
     *  every secret we marked collected via a *genuine completion signal*
     *  (Secret.Interact / Secret.Item / Secret.Bat events, the INTERACT-head
     *  block poll, or the BAT/ITEM proximity poll). Re-entering the same room
     *  later in the run rebuilds [activeRoutes] but immediately re-marks any
     *  group whose key appears here, so the already-done secrets stay hidden.
     *
     *  Manual skip-keybind completions are intentionally *not* persisted — the
     *  user might want to come back and see that route later in the run. The
     *  set is cleared on [WorldEvent.Change] (entering / leaving the dungeon
     *  is a world swap, so a fresh dungeon run starts with an empty set). */
    private val completedSecrets = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, Int>>()

    /** Tracked separately from [Dungeon.currentRoom] because we want the name
     *  the *routes were loaded under* (which we already canonicalised against
     *  the route DB), so the persistence key matches what gets re-checked on
     *  room re-enter. */
    @Volatile private var currentRoomName: String? = null

    /** Auto-advance hit radii — how close an event's position must be to a
     *  recorded secret to count as "this is the one that fired". Bats need a
     *  larger window since they fly around before being killed. */
    private const val INTERACT_HIT_RADIUS = 4.0
    private const val ITEM_HIT_RADIUS = 4.0
    private const val BAT_HIT_RADIUS = 8.0
    /** Proximity radii for the per-frame "player is standing right on the
     *  secret" fallback. Smaller than the event radii because this is a
     *  player-position check, not a packet-source-position one. Matches
     *  yourboykyle's beta3 reference (3 / 2). */
    private const val BAT_PROXIMITY_RADIUS = 3.0
    private const val ITEM_PROXIMITY_RADIUS = 2.0

    init {
        on<DungeonEvent.Room.Enter> {
            onRoomEnter(room)
        }

        on<WorldEvent.Change> {
            activeRoutes.clear()
            currentRoomName = null
            // New dungeon run / lobby exit — wipe the per-run completed log
            // so we start fresh next dungeon.
            completedSecrets.clear()
        }

        // Auto-advance hooks --------------------------------------------------
        on<DungeonEvent.Secret.Interact> {
            if (!autoAdvance) return@on
            markCollectedNearest(Vec3.atCenterOf(blockPos), INTERACT_HIT_RADIUS) {
                it == SecretType.INTERACT || it == SecretType.UNKNOWN
            }
        }
        on<DungeonEvent.Secret.Item> {
            if (!autoAdvance) return@on
            markCollectedNearest(entity.position(), ITEM_HIT_RADIUS) { it == SecretType.ITEM }
        }
        on<DungeonEvent.Secret.Bat> {
            if (!autoAdvance) return@on
            markCollectedNearest(Vec3(packet.x, packet.y, packet.z), BAT_HIT_RADIUS) { it == SecretType.BAT }
        }

        on<RenderEvent.World> {
            if (!inDungeons || inBoss) return@on
            if (activeRoutes.isEmpty()) return@on

            // Catch INTERACT secrets that got collected without a Secret.Interact
            // event (e.g. another party member clicked, or we entered the room
            // after they were already gone). Cheap — at most ~8 getBlockState
            // calls per frame.
            if (autoAdvance) {
                refreshInteractCollected()
                refreshProximityCollected()
            }

            val playerPos = mc.player?.position()
            val visibleGroups = activeRoutes.filter { !it.collected }

            // Always draw a small target marker for EVERY still-uncollected
            // secret in the room so the player has spatial awareness even when
            // only one route's full path is rendered.
            if (!showAllSecrets) {
                for (group in visibleGroups) {
                    val s = group.alternates.firstNotNullOfOrNull { it.secret } ?: continue
                    drawSecretTarget(s)
                }
            }

            val groupsToDraw = if (showAllSecrets) {
                visibleGroups
            } else {
                val nearest = playerPos?.let { p -> visibleGroups.minByOrNull { it.anchor?.distanceToSqr(p) ?: Double.MAX_VALUE } }
                    ?: visibleGroups.firstOrNull()
                if (nearest == null) return@on else listOf(nearest)
            }

            for (group in groupsToDraw) {
                val routesToDraw = if (showAllAlternates || playerPos == null || group.alternates.size <= 1) {
                    group.alternates
                } else {
                    listOf(group.alternates.minBy { firstWaypointSqr(it, playerPos) })
                }
                for (route in routesToDraw) drawRoute(route)
            }
        }
    }

    private fun RenderEvent.World.drawRoute(route: WorldRoute) {
        val depth = !throughWalls

        if (route.locations.size >= 2) {
            ctx.drawLine(route.locations, lineColour, depth = depth, thickness = lineThickness)
        }
        val firstLoc = route.locations.firstOrNull()
        if (showStartMarker && firstLoc != null) {
            val pos = BlockPos(firstLoc.x.toInt(), (firstLoc.y - 0.5).toInt(), firstLoc.z.toInt())
            ctx.drawWireFrameBox(pos.aabb, startColour, thickness = startThickness, depth = depth)
        }
        if (showStartLabel && firstLoc != null) {
            ctx.drawText(
                literal("Start").withColor(startColour.rgb),
                firstLoc.add(0.0, 1.6, 0.0),
                scale = 1.0f,
                depth = depth,
            )
        }
        if (showWaypointNumbers) {
            route.locations.forEachIndexed { idx, loc ->
                ctx.drawText(
                    literal((idx + 1).toString()).withColor(lineColour.rgb),
                    loc.add(0.0, 0.85, 0.0),
                    scale = 0.8f,
                    depth = depth,
                )
            }
        }
        drawBoxes(route.etherwarps, etherwarpColour)
        drawBoxes(route.mines, mineColour)
        drawBoxes(route.interacts, interactColour)
        drawBoxes(route.tnts, tntColour)
        if (showPearls) drawPearls(route.pearls)
        route.secret?.let { drawSecretTarget(it) }
    }

    /** Draw the secret-target box and (optionally) the beacon column. */
    private fun RenderEvent.World.drawSecretTarget(s: WorldSecret) {
        val depth = !throughWalls
        val targetColour = colourForSecret(s.type)
        ctx.drawFilledBox(s.pos.aabb, targetColour, depth = depth)
        if (showBeacon) {
            val beamColour = targetColour.withAlpha(0.18f)
            // 0.3-block-wide vertical column from the floor up to build limit.
            // Using a single tall AABB instead of MC's BeaconRenderer because
            // the 1.21.10 BeaconRenderer API moved to the deferred-render
            // SubmitNodeCollector pipeline which WorldRenderContext doesn't
            // expose — a translucent filled box gives the same "spot it from
            // far across the room" affordance for ~3 lines of code.
            val cx = s.pos.x + 0.5
            val cz = s.pos.z + 0.5
            val beam = AABB(cx - 0.15, s.pos.y.toDouble(), cz - 0.15, cx + 0.15, 320.0, cz + 0.15)
            ctx.drawFilledBox(beam, beamColour, depth = depth)
        }
    }

    private fun RenderEvent.World.drawPearls(pearls: List<WorldPearl>) {
        if (pearls.isEmpty()) return
        val depth = !throughWalls
        for (p in pearls) {
            // Small marker box at the throw position (player feet), 0.5 cube.
            val markBox = AABB(p.throwPos.x - 0.25, p.throwPos.y, p.throwPos.z - 0.25,
                               p.throwPos.x + 0.25, p.throwPos.y + 0.5, p.throwPos.z + 0.25)
            ctx.drawFilledBox(markBox, pearlColour, depth = depth)

            // Eye position + 10-block ray along the look direction. Matches the
            // upstream SecretRoutes mod's pearl preview (which also draws a
            // straight 10-block ray, not a parabolic arc — the ray approximates
            // the pearl's first ~1 second of flight before gravity bends it).
            val yawRad = Math.toRadians(p.angle.yaw.toDouble())
            val pitchRad = Math.toRadians(p.angle.pitch.toDouble())
            val cosP = cos(pitchRad)
            val dx = -sin(yawRad) * cosP
            val dy = -sin(pitchRad)
            val dz =  cos(yawRad) * cosP
            val length = 10.0
            val eye = Vec3(p.throwPos.x, p.throwPos.y + 1.62, p.throwPos.z)
            val end = Vec3(eye.x + dx * length, eye.y + dy * length, eye.z + dz * length)
            ctx.drawLine(listOf(eye, end), pearlColour, depth = depth, thickness = pearlLineThickness)
        }
    }

    private fun RenderEvent.World.drawBoxes(positions: List<BlockPos>, colour: Colour) {
        if (positions.isEmpty()) return
        val depth = !throughWalls
        for (pos in positions) ctx.drawFilledBox(pos.aabb, colour, depth = depth)
    }

    private fun colourForSecret(type: SecretType): Colour = when (type) {
        SecretType.INTERACT -> secretInteractColour
        SecretType.BAT      -> secretBatColour
        SecretType.ITEM     -> secretItemColour
        SecretType.CHEST    -> secretChestColour
        SecretType.EXIT     -> secretExitColour
        SecretType.UNKNOWN  -> secretInteractColour
    }

    private fun firstWaypointSqr(route: WorldRoute, player: Vec3): Double {
        val first = route.locations.firstOrNull() ?: return Double.MAX_VALUE
        return first.distanceToSqr(player)
    }

    // -- Auto-advance plumbing ---------------------------------------------

    /** Mark the closest uncollected secret matching [typePredicate] within
     *  [radius] blocks of [eventPos] as collected. No-op if no match. */
    private fun markCollectedNearest(eventPos: Vec3, radius: Double, typePredicate: (SecretType) -> Boolean) {
        if (activeRoutes.isEmpty()) return
        val r2 = radius * radius
        var best: RoutesForSecret? = null
        var bestSqr = r2
        for (group in activeRoutes) {
            if (group.collected) continue
            val secret = group.alternates.firstNotNullOfOrNull { it.secret } ?: continue
            if (!typePredicate(secret.type)) continue
            val d2 = Vec3.atCenterOf(secret.pos).distanceToSqr(eventPos)
            if (d2 <= bestSqr) {
                bestSqr = d2
                best = group
            }
        }
        best?.let { markGroupCompleted(it) }
    }

    /** Marks a group collected AND remembers it in [completedSecrets] so the
     *  route stays hidden if the player re-enters the room later in the run.
     *  Use only for genuine completion signals (events / proximity / head poll);
     *  the manual skip keybind should write [RoutesForSecret.collected] directly
     *  so it doesn't persist across re-entries. */
    private fun markGroupCompleted(group: RoutesForSecret) {
        group.collected = true
        val room = currentRoomName ?: return
        completedSecrets += room to group.secretIndex
    }

    /** INTERACT-head secrets that got removed without firing our event (e.g. a
     *  party-mate clicked them) — detect by checking the block at the recorded
     *  position is no longer a player head. Only runs on secrets we *know* were
     *  PLAYER_HEAD at room-enter (see [RoutesForSecret.pollableAsHead]) —
     *  otherwise lever / button / chest INTERACT secrets would mark themselves
     *  collected immediately, since their block isn't a PLAYER_HEAD ever. */
    private fun refreshInteractCollected() {
        val level = mc.level ?: return
        for (group in activeRoutes) {
            if (group.collected || !group.pollableAsHead) continue
            val secret = group.alternates.firstNotNullOfOrNull { it.secret } ?: continue
            if (!level.getBlockState(secret.pos).`is`(Blocks.PLAYER_HEAD)) {
                markGroupCompleted(group)
            }
        }
    }

    /** Per-frame fallback for BAT/ITEM secrets that the packet-event hooks
     *  missed (e.g. item velocity pushed it away before pickup, or the bat
     *  damage sound got dropped). If the player is standing within the
     *  type-specific proximity radius of an uncollected BAT/ITEM secret,
     *  mark it collected. Matches yourboykyle beta3's auto-advance fallback. */
    private fun refreshProximityCollected() {
        val player = mc.player?.position() ?: return
        for (group in activeRoutes) {
            if (group.collected) continue
            val secret = group.alternates.firstNotNullOfOrNull { it.secret } ?: continue
            val radius = when (secret.type) {
                SecretType.BAT  -> BAT_PROXIMITY_RADIUS
                SecretType.ITEM -> ITEM_PROXIMITY_RADIUS
                else -> continue
            }
            if (Vec3.atCenterOf(secret.pos).distanceToSqr(player) <= radius * radius) {
                markGroupCompleted(group)
            }
        }
    }

    /** Mark the secret that the route is currently pointing at as collected,
     *  so the active route hops to the next-nearest uncollected one. Bound to
     *  the "Skip current secret" keybind. */
    private fun skipCurrentSecret() {
        if (activeRoutes.isEmpty()) return
        val playerPos = mc.player?.position() ?: return
        val visible = activeRoutes.filter { !it.collected }
        val target = visible.minByOrNull { it.anchor?.distanceToSqr(playerPos) ?: Double.MAX_VALUE } ?: return
        target.collected = true
    }

    // -- Room enter --------------------------------------------------------

    private fun onRoomEnter(room: OdonRoom?) {
        activeRoutes.clear()
        currentRoomName = room?.name
        if (room == null) return

        val groups = RouteData[room.name]
        if (groups.isEmpty()) return

        val level = mc.level
        val roomName = room.name
        for (group in groups) {
            val translated = group.routes.map { r ->
                WorldRoute(
                    locations = r.locations.map { Vec3.atCenterOf(room.getRealCoords(it)) },
                    etherwarps = r.etherwarps.map(room::getRealCoords),
                    mines = r.mines.map(room::getRealCoords),
                    interacts = r.interacts.map(room::getRealCoords),
                    tnts = r.tnts.map(room::getRealCoords),
                    pearls = r.pearls.zip(r.pearlAngles) { pos, ang ->
                        WorldPearl(
                            throwPos = room.getRealCoords(pos),
                            angle = PitchYaw(ang.pitch, room.getRealYaw(ang.yaw)),
                        )
                    },
                    secret = r.secret?.let { WorldSecret(it.type, room.getRealCoords(it.location)) },
                )
            }
            val rfs = RoutesForSecret(group.secretIndex, translated)
            // Snapshot: is this an actual PLAYER_HEAD interact secret? If so,
            // the per-frame "block missing => collected" poll is safe. If it's
            // a lever / chest / button INTERACT (or BAT / ITEM / etc.), poll
            // would falsely auto-collect on first frame.
            val secret = translated.firstNotNullOfOrNull { it.secret }
            if (secret != null && (secret.type == SecretType.INTERACT || secret.type == SecretType.UNKNOWN) &&
                level?.getBlockState(secret.pos)?.`is`(Blocks.PLAYER_HEAD) == true
            ) {
                rfs.pollableAsHead = true
            }
            // Re-apply per-run completion state: if this secret was already
            // collected earlier in the run (then we left + re-entered the room),
            // mark it collected again so the route stays hidden.
            if (roomName to group.secretIndex in completedSecrets) {
                rfs.collected = true
            }
            activeRoutes += rfs
        }
    }
}
