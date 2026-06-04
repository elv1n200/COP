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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders secret routes for the current dungeon room as world-space waypoints.
 *
 * Route data ships with COP at `assets/cop/secretroutes/{routes,pearlroutes}.json`,
 * sourced from yourboykyle's Secret Routes Mod (see `CREDITS.md`). Each room's
 * JSON entry is a *sequence* of steps — collect step 0's secret, then step 1's,
 * etc. — and a room can have multiple route variants (e.g. a short 2-secret
 * route vs a full-clear 8-secret one). We default to the longest variant.
 *
 * Default view: only the *active* (first uncollected) step's full route is
 * rendered, plus small target dots for the upcoming secrets in the variant so
 * you can preview what's next. As you complete each step (via the secret-event
 * hooks, the INTERACT-head block poll, or the BAT/ITEM proximity poll) the
 * active step advances. Per-run completion state persists across room re-enters
 * within the same dungeon — once you've done a secret it stays hidden.
 *
 * Display only. There is no playback, no auto-walk, no clicking. The original
 * COP port that *did* play routes back was removed (commit `9cdbbf9`) for being
 * too fragile; this module is the simpler "show me where to go" replacement.
 */
object SecretRoutes : Module(
    "Secret Routes",
    area = Island.Dungeon(inClear = true),
    desc = "Renders secret waypoints + tracking line for the current dungeon room."
) {
    // -- Settings -----------------------------------------------------------

    private val showAllSteps by switch(
        "Show whole route", false,
        desc = "On: render the FULL route (line + waypoints) for every uncollected step in the chosen variant. Off: only the active (next-to-do) step gets the full treatment; remaining steps show as small target dots."
    )
    private val showUpcomingTargets by switch(
        "Show upcoming secrets", true,
        desc = "When only the active step's route is rendered, also draw a small target dot on each upcoming secret in the variant so you can see what's coming."
    )
    private val showAllVariants by switch(
        "Show all variants", false,
        desc = "If a room has multiple known route variants (e.g. Waterfall has a 2-secret and an 8-secret variant), render all of them. Off: only the chosen variant (longest by default)."
    )
    private val nextSecretKey = keybind(
        "Skip current secret", CatKeys.KEY_NONE,
        desc = "Manually mark the active secret as done so the route advances to the next step in the variant. Useful when auto-advance misses (chest secrets, weird positions, etc.)."
    ).onPress { skipCurrentSecret() }.also { register(it) }
    private val autoAdvance by switch(
        "Auto-advance", true,
        desc = "Mark steps you've already collected as done (via secret-interact / item-pickup / bat-kill events, INTERACT-head block check, or BAT/ITEM proximity) so the active step auto-switches forward."
    )
    private val showBeacon by switch(
        "Beacon beam", true,
        desc = "Draw a tall translucent vertical column on each visible secret target so you can spot them through walls from across the room."
    )
    private val showPearls by switch(
        "Pearl trajectories", true,
        desc = "Render pearl-throw positions + a line along the look angle showing where to aim the ender pearl. Comes from the pearl-route DB."
    )
    private val showStartMarker by switch(
        "Mark start", true,
        desc = "Draw a wireframe box at the first waypoint of the active step's route so you can tell where to begin."
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
        desc = "Wireframe box at the active route's first waypoint."
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

    private data class WorldSecret(val type: SecretType, val pos: BlockPos)

    /** Pre-translated counterpart of [RouteData.Step] — coordinates already in
     *  world space so the render loop doesn't redo the rotation each frame. */
    private class WorldStep(
        val locations: List<Vec3>,
        val etherwarps: List<BlockPos>,
        val mines: List<BlockPos>,
        val interacts: List<BlockPos>,
        val tnts: List<BlockPos>,
        val pearls: List<WorldPearl>,
        val secret: WorldSecret?,
    ) {
        /** Marked true by auto-advance (events / poll / proximity) or by the
         *  manual skip keybind. @Volatile because event handlers can run on
         *  the network thread. */
        @Volatile var collected: Boolean = false
        /** True iff this step's secret was a PLAYER_HEAD block at room-enter
         *  time. Gates the per-frame "head removed" poll — INTERACT secrets
         *  also cover levers / chests / buttons where polling for PLAYER_HEAD
         *  absence would falsely mark them collected on the first frame. */
        var pollableAsHead: Boolean = false
    }

    private class WorldVariant(val variantId: String, val steps: List<WorldStep>) {
        /** First uncollected step in the sequence, or null if the variant is
         *  fully done. */
        fun activeStep(): WorldStep? = steps.firstOrNull { !it.collected }

        fun activeStepIndex(): Int = steps.indexOfFirst { !it.collected }
    }

    /** Variants for the current room, in their DB order (longest first per
     *  [RouteData.load]'s sort). The "chosen variant" is `[0]` unless
     *  [showAllVariants] is on. */
    private val activeVariants = java.util.concurrent.CopyOnWriteArrayList<WorldVariant>()

    /** Persistent across the current dungeon run: `(roomName, variantId, stepIndex)`
     *  of every step we marked collected via a *genuine completion signal*. Re-
     *  entering the same room rebuilds [activeVariants] but re-marks any step
     *  whose triple is here, so already-done steps stay hidden.
     *
     *  Manual skip-keybind completions are intentionally *not* persisted — the
     *  user might want to come back to that secret. Cleared on [WorldEvent.Change]. */
    private val completedSteps = ConcurrentHashMap.newKeySet<Triple<String, String, Int>>()

    @Volatile private var currentRoomName: String? = null

    // -- Tunables not surfaced as settings ---------------------------------

    private const val INTERACT_HIT_RADIUS = 4.0
    private const val ITEM_HIT_RADIUS = 4.0
    private const val BAT_HIT_RADIUS = 8.0
    private const val BAT_PROXIMITY_RADIUS = 3.0
    private const val ITEM_PROXIMITY_RADIUS = 2.0

    init {
        on<DungeonEvent.Room.Enter> {
            onRoomEnter(room)
        }

        on<WorldEvent.Change> {
            activeVariants.clear()
            currentRoomName = null
            // New dungeon run / lobby exit — wipe the per-run completed log
            // so we start fresh next dungeon.
            completedSteps.clear()
        }

        // Auto-advance event hooks ------------------------------------------
        on<DungeonEvent.Secret.Interact> {
            if (!autoAdvance) return@on
            markStepCollectedNearest(Vec3.atCenterOf(blockPos), INTERACT_HIT_RADIUS) {
                it == SecretType.INTERACT || it == SecretType.UNKNOWN
            }
        }
        on<DungeonEvent.Secret.Item> {
            if (!autoAdvance) return@on
            markStepCollectedNearest(entity.position(), ITEM_HIT_RADIUS) { it == SecretType.ITEM }
        }
        on<DungeonEvent.Secret.Bat> {
            if (!autoAdvance) return@on
            markStepCollectedNearest(Vec3(packet.x, packet.y, packet.z), BAT_HIT_RADIUS) { it == SecretType.BAT }
        }

        on<RenderEvent.World> {
            if (!inDungeons || inBoss) return@on
            if (activeVariants.isEmpty()) return@on

            if (autoAdvance) {
                refreshInteractCollected()
                refreshProximityCollected()
            }

            val variantsToConsider = if (showAllVariants) activeVariants else listOfNotNull(activeVariants.firstOrNull())

            for (variant in variantsToConsider) {
                if (showAllSteps) {
                    // Whole-route view: draw the full route for every uncollected
                    // step in the variant.
                    for (step in variant.steps) {
                        if (step.collected) continue
                        drawStep(step, isActive = (step === variant.activeStep()))
                    }
                } else {
                    // Default view: only the active step gets the full route;
                    // upcoming steps show as small target dots if enabled.
                    val active = variant.activeStep() ?: continue
                    if (showUpcomingTargets) {
                        var seenActive = false
                        for (step in variant.steps) {
                            if (step.collected) continue
                            if (step === active) { seenActive = true; continue }
                            if (!seenActive) continue   // safety — shouldn't happen
                            val s = step.secret ?: continue
                            drawSecretTargetCompact(s)
                        }
                    }
                    drawStep(active, isActive = true)
                }
            }
        }
    }

    // -- Render helpers ----------------------------------------------------

    private fun RenderEvent.World.drawStep(step: WorldStep, isActive: Boolean) {
        val depth = !throughWalls

        if (step.locations.size >= 2) {
            ctx.drawLine(step.locations, lineColour, depth = depth, thickness = lineThickness)
        }
        val firstLoc = step.locations.firstOrNull()
        if (isActive && showStartMarker && firstLoc != null) {
            val pos = BlockPos(firstLoc.x.toInt(), (firstLoc.y - 0.5).toInt(), firstLoc.z.toInt())
            ctx.drawWireFrameBox(pos.aabb, startColour, thickness = startThickness, depth = depth)
        }
        if (isActive && showStartLabel && firstLoc != null) {
            ctx.drawText(
                literal("Start").withColor(startColour.rgb),
                firstLoc.add(0.0, 1.6, 0.0),
                scale = 1.0f,
                depth = depth,
            )
        }
        if (showWaypointNumbers) {
            step.locations.forEachIndexed { idx, loc ->
                ctx.drawText(
                    literal((idx + 1).toString()).withColor(lineColour.rgb),
                    loc.add(0.0, 0.85, 0.0),
                    scale = 0.8f,
                    depth = depth,
                )
            }
        }
        drawBoxes(step.etherwarps, etherwarpColour)
        drawBoxes(step.mines, mineColour)
        drawBoxes(step.interacts, interactColour)
        drawBoxes(step.tnts, tntColour)
        if (showPearls) drawPearls(step.pearls)
        step.secret?.let { drawSecretTarget(it) }
    }

    /** Full secret target with optional beacon column — for active or whole-route view. */
    private fun RenderEvent.World.drawSecretTarget(s: WorldSecret) {
        val depth = !throughWalls
        val targetColour = colourForSecret(s.type)
        ctx.drawFilledBox(s.pos.aabb, targetColour, depth = depth)
        if (showBeacon) {
            val beamColour = targetColour.withAlpha(0.18f)
            val cx = s.pos.x + 0.5
            val cz = s.pos.z + 0.5
            val beam = AABB(cx - 0.15, s.pos.y.toDouble(), cz - 0.15, cx + 0.15, 320.0, cz + 0.15)
            ctx.drawFilledBox(beam, beamColour, depth = depth)
        }
    }

    /** Compact target marker for upcoming-secret-awareness — smaller box, no
     *  beacon. Keeps the lookahead visually distinct from the active step. */
    private fun RenderEvent.World.drawSecretTargetCompact(s: WorldSecret) {
        val depth = !throughWalls
        val targetColour = colourForSecret(s.type).withAlpha(0.5f)
        val cx = s.pos.x + 0.5
        val cz = s.pos.z + 0.5
        val cy = s.pos.y + 0.5
        val small = AABB(cx - 0.3, cy - 0.3, cz - 0.3, cx + 0.3, cy + 0.3, cz + 0.3)
        ctx.drawFilledBox(small, targetColour, depth = depth)
    }

    private fun RenderEvent.World.drawPearls(pearls: List<WorldPearl>) {
        if (pearls.isEmpty()) return
        val depth = !throughWalls
        for (p in pearls) {
            val markBox = AABB(p.throwPos.x - 0.25, p.throwPos.y, p.throwPos.z - 0.25,
                               p.throwPos.x + 0.25, p.throwPos.y + 0.5, p.throwPos.z + 0.25)
            ctx.drawFilledBox(markBox, pearlColour, depth = depth)
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

    // -- Auto-advance plumbing ---------------------------------------------

    /** Find the closest uncollected step (across the chosen variants) whose
     *  secret matches [typePredicate] and is within [radius] of [eventPos];
     *  mark it AND all earlier-in-the-variant steps as collected. Marking
     *  earlier steps handles out-of-order completion (player skipped ahead). */
    private fun markStepCollectedNearest(eventPos: Vec3, radius: Double, typePredicate: (SecretType) -> Boolean) {
        if (activeVariants.isEmpty()) return
        val variantsToConsider = if (showAllVariants) activeVariants else listOfNotNull(activeVariants.firstOrNull())
        val r2 = radius * radius

        var bestVariant: WorldVariant? = null
        var bestStepIdx = -1
        var bestSqr = r2
        for (variant in variantsToConsider) {
            variant.steps.forEachIndexed { idx, step ->
                if (step.collected) return@forEachIndexed
                val secret = step.secret ?: return@forEachIndexed
                if (!typePredicate(secret.type)) return@forEachIndexed
                val d2 = Vec3.atCenterOf(secret.pos).distanceToSqr(eventPos)
                if (d2 <= bestSqr) {
                    bestSqr = d2
                    bestVariant = variant
                    bestStepIdx = idx
                }
            }
        }
        val v = bestVariant ?: return
        markStepAndEarlierCompleted(v, bestStepIdx)
    }

    /** Mark step at [endIdx] as collected and (for monotonic variant flow)
     *  also mark every earlier still-uncollected step. Persist all of them. */
    private fun markStepAndEarlierCompleted(variant: WorldVariant, endIdx: Int) {
        val room = currentRoomName
        for (i in 0..endIdx) {
            val step = variant.steps[i]
            if (step.collected) continue
            step.collected = true
            if (room != null) completedSteps += Triple(room, variant.variantId, i)
        }
    }

    /** Active INTERACT-head steps that disappeared from world state without
     *  firing a Secret.Interact (party-mate clicked, we entered after the head
     *  was already gone, ...). Only runs on steps we *know* were PLAYER_HEAD
     *  at room enter — see [WorldStep.pollableAsHead]. */
    private fun refreshInteractCollected() {
        val level = mc.level ?: return
        val variantsToConsider = if (showAllVariants) activeVariants else listOfNotNull(activeVariants.firstOrNull())
        for (variant in variantsToConsider) {
            variant.steps.forEachIndexed { idx, step ->
                if (step.collected || !step.pollableAsHead) return@forEachIndexed
                val secret = step.secret ?: return@forEachIndexed
                if (!level.getBlockState(secret.pos).`is`(Blocks.PLAYER_HEAD)) {
                    markStepAndEarlierCompleted(variant, idx)
                }
            }
        }
    }

    /** Per-frame BAT/ITEM fallback: if the player is standing within the
     *  type-specific proximity radius of an uncollected BAT/ITEM step, mark it. */
    private fun refreshProximityCollected() {
        val player = mc.player?.position() ?: return
        val variantsToConsider = if (showAllVariants) activeVariants else listOfNotNull(activeVariants.firstOrNull())
        for (variant in variantsToConsider) {
            variant.steps.forEachIndexed { idx, step ->
                if (step.collected) return@forEachIndexed
                val secret = step.secret ?: return@forEachIndexed
                val radius = when (secret.type) {
                    SecretType.BAT  -> BAT_PROXIMITY_RADIUS
                    SecretType.ITEM -> ITEM_PROXIMITY_RADIUS
                    else -> return@forEachIndexed
                }
                if (Vec3.atCenterOf(secret.pos).distanceToSqr(player) <= radius * radius) {
                    markStepAndEarlierCompleted(variant, idx)
                }
            }
        }
    }

    /** Manual override: mark the active step (in the chosen variant) as done
     *  so the route advances. Does NOT persist — re-entering the room will
     *  re-show that step. The user might want to come back. */
    private fun skipCurrentSecret() {
        val variant = activeVariants.firstOrNull() ?: return
        val active = variant.activeStep() ?: return
        active.collected = true
    }

    // -- Room enter --------------------------------------------------------

    private fun onRoomEnter(room: OdonRoom?) {
        activeVariants.clear()
        currentRoomName = room?.name
        if (room == null) return

        val variants = RouteData[room.name]
        if (variants.isEmpty()) return

        val level = mc.level
        val roomName = room.name
        for (variant in variants) {
            val translatedSteps = variant.steps.map { step ->
                val world = WorldStep(
                    locations = step.locations.map { Vec3.atCenterOf(room.getRealCoords(it)) },
                    etherwarps = step.etherwarps.map(room::getRealCoords),
                    mines = step.mines.map(room::getRealCoords),
                    interacts = step.interacts.map(room::getRealCoords),
                    tnts = step.tnts.map(room::getRealCoords),
                    pearls = step.pearls.zip(step.pearlAngles) { pos, ang ->
                        WorldPearl(
                            throwPos = room.getRealCoords(pos),
                            angle = PitchYaw(ang.pitch, room.getRealYaw(ang.yaw)),
                        )
                    },
                    secret = step.secret?.let { WorldSecret(it.type, room.getRealCoords(it.location)) },
                )
                // Snapshot pollable-as-head at room-enter so the per-frame poll
                // doesn't false-positive on levers / buttons / chests.
                val s = world.secret
                if (s != null && (s.type == SecretType.INTERACT || s.type == SecretType.UNKNOWN) &&
                    level?.getBlockState(s.pos)?.`is`(Blocks.PLAYER_HEAD) == true
                ) {
                    world.pollableAsHead = true
                }
                world
            }
            val worldVariant = WorldVariant(variant.variantId, translatedSteps)
            // Re-apply per-run completion state: if a step was already
            // collected earlier in the run (we left + re-entered), mark it
            // collected again so the route stays advanced.
            worldVariant.steps.forEachIndexed { idx, step ->
                if (Triple(roomName, variant.variantId, idx) in completedSteps) {
                    step.collected = true
                }
            }
            activeVariants += worldVariant
        }
    }
}
