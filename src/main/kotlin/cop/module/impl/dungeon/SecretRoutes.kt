package cop.module.impl.dungeon

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.client.KeyMapping
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth.wrapDegrees
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt
import cop.CopMod.scope
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.DungeonEvent
import cop.api.events.KeyEvent
import cop.api.events.RenderEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.input.CatKeys
import cop.api.skyblock.Island
import cop.api.skyblock.SkyblockPlayer
import cop.api.skyblock.dungeon.Dungeon.currentRoom
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom
import cop.api.skyblock.invoke
import cop.mixins.accessors.KeyMappingAccessor
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler
import cop.utils.render.drawFilledBox
import cop.utils.render.drawLine
import cop.utils.render.drawWireFrameBox
import cop.utils.skyblock.ItemUtils.extraAttributes
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.PlayerUtils.rightClick
import cop.utils.skyblock.player.PlayerUtils.leftClick
import cop.utils.skyblock.player.RotationUtils.pitch
import cop.utils.skyblock.player.RotationUtils.rotateSmoothly
import cop.utils.skyblock.player.RotationUtils.yaw
import cop.utils.skyblock.player.SwapManager
import java.io.File

/**
 * Port of CritsAddons `SecretRoutes` (com.github.noamm9.critsaddons.features.impl.critsaddons.SecretRoutes).
 *
 * Plays back pre-recorded secret routes for every dungeon room. The original
 * upstream module also handled recording, start/end-link chaining and command
 * editing — this port focuses on **loading + rendering + playback** since COP
 * ships with ~150 pre-built routes at `assets/cop/secretRoutes.json`.
 *
 *   - Routes load once from the bundled JSON, and a user file at
 *     `config/cop/secretRoutes.json` can override / extend them.
 *   - On every tick, while standing on (or close to) a route's `startBlock`,
 *     the module waits until you're centred for a short grace period and
 *     then kicks off the playback coroutine.
 *   - The 7 step types (ETHERWARP, PLACE_TNT, BREAK_BLOCK, USE_HYPERION,
 *     RIGHT_CLICK_SECRET, WAIT_FOR_SECRET_PROGRESS, WAIT_FOR_BAT_SPAWN) are
 *     dispatched against the current [OdonRoom] using the room's rotation
 *     to convert saved relative coordinates back into world coordinates.
 *   - Press the configurable keybind to toggle playback manually.
 */
object SecretRoutes : Module(
    "Secret Routes",
    area = Island.Dungeon(inClear = true),
    desc = "Plays back pre-recorded secret routes. Ships with a pre-built route DB (/assets/cop/secretRoutes.json)."
) {
    // ---------------------------------------------------------------- settings

    private val playbackKeybind = keybind("Toggle playback", CatKeys.KEY_NONE,
        desc = "Press to cancel an active playback (or force-start one when standing on a start block).")
        .onPress { toggleManualPlayback() }
        .also { register(it) }

    private val autoStart by switch("Auto start", true,
        desc = "Starts playback automatically when you stand centered on a known start block.")
    private val centerHoldTicks by slider("Center hold (ticks)", 4, 0, 20, 1,
        desc = "How long you must stand on the start block before autostart fires.", unit = "t")
    private val centerRadius by slider("Center tolerance", 0.45f, 0.05f, 1.0f, 0.05f,
        desc = "How close to the block centre you must stand to count as \"on\" the block.")

    private val rotationDurationTicks by slider("Rotation time (ticks)", 4, 0, 20, 1,
        desc = "How long rotations take. 0 = snap instantly.", unit = "t")

    private val manaTimeoutMs by slider("Mana wait timeout", 1500, 100, 5000, 100, unit = "ms",
        desc = "How long to wait for enough mana before giving up on a mana-gated step.")
    private val warpSettleTimeoutMs by slider("Warp settle timeout", 3000, 500, 8000, 100, unit = "ms",
        desc = "How long to wait for the etherwarp to land.")
    private val batWaitTimeoutMs by slider("Bat wait timeout", 4000, 1000, 10_000, 500, unit = "ms",
        desc = "How long to wait for a bat to spawn on WAIT_FOR_BAT_SPAWN.")
    private val secretProgressTimeoutMs by slider("Secret progress timeout", 4000, 1000, 10_000, 500, unit = "ms",
        desc = "How long to wait for the secret counter to tick on WAIT_FOR_SECRET_PROGRESS.")

    private val etherwarpManaCost by slider("Etherwarp mana cost", 80, 0, 400, 10,
        desc = "Mana required before an ETHERWARP step fires.")
    private val hyperionManaCost by slider("Hyperion mana cost", 300, 0, 500, 10,
        desc = "Mana required before a USE_HYPERION step fires.")

    private val showStart by switch("Render start blocks", true)
    private val startColour by colourPicker("Start colour", Colour.GREEN.withAlpha(0.4f), allowAlpha = true)
    private val showSteps by switch("Render steps", true)
    private val stepLineColour by colourPicker("Step line colour", Colour.CYAN.withAlpha(0.75f), allowAlpha = true)
    private val etherwarpColour by colourPicker("Etherwarp colour", Colour.CYAN.withAlpha(0.35f), allowAlpha = true)
    private val tntColour by colourPicker("TNT colour", Colour.ORANGE.withAlpha(0.35f), allowAlpha = true)
    private val breakColour by colourPicker("Break colour", Colour.YELLOW.withAlpha(0.35f), allowAlpha = true)
    private val secretColour by colourPicker("Secret colour", Colour.RED.withAlpha(0.35f), allowAlpha = true)

    // ------------------------------------------------------------------- state

    private var routes: Map<String, RoomRoute> = emptyMap()

    @Volatile
    private var playbackJob: Job? = null

    @Volatile
    private var activeRoomName: String? = null

    // How many ticks we've been centred on the current start block (per room).
    private var centerTickCounter = 0
    private var centerTrackedRoom: String? = null

    // Secret event + bat spawn counters used by the WAIT_FOR_* step types.
    @Volatile
    private var secretInteractCounter = 0

    init {
        loadRoutesOnce()

        on<TickEvent.End> {
            if (!autoStart) return@on
            if (playbackJob?.isActive == true) return@on

            val room = currentRoom ?: return@on
            val route = routes[room.name] ?: return@on
            val player = mc.player ?: return@on

            val startWorld = room.getRealCoords(route.startLocal)
            val dx = player.x - startWorld.x
            val dz = player.z - startWorld.z
            val tol = kotlin.math.max(centerRadius.toDouble(), route.startRadius.toDouble())
            val withinXZ = kotlin.math.abs(dx) <= tol && kotlin.math.abs(dz) <= tol
            val withinY = kotlin.math.abs(player.y - startWorld.y) <= 1.2

            if (withinXZ && withinY) {
                if (centerTrackedRoom != room.name) {
                    centerTrackedRoom = room.name
                    centerTickCounter = 0
                }
                if (centerTickCounter < Int.MAX_VALUE) centerTickCounter++
                if (centerTickCounter >= centerHoldTicks) {
                    centerTickCounter = 0
                    beginPlayback(room, route)
                }
            } else {
                centerTrackedRoom = null
                centerTickCounter = 0
            }
        }

        on<RenderEvent.World> {
            val room = currentRoom ?: return@on
            val route = routes[room.name] ?: return@on

            if (showStart) {
                val box = blockBoxAround(room.getRealCoords(route.startLocal))
                ctx.drawFilledBox(box, startColour, depth = true)
                ctx.drawWireFrameBox(box, Colour.WHITE, thickness = 2f, depth = true)
            }

            if (showSteps) {
                val points = mutableListOf<Vec3>()
                for (step in route.steps) {
                    val real = room.getRealCoords(step.localPos)
                    val box = blockBoxAround(real)
                    val colour = colourForStep(step.type)
                    ctx.drawFilledBox(box, colour, depth = true)
                    ctx.drawWireFrameBox(box, Colour.WHITE.withAlpha(0.6f), thickness = 1.5f, depth = true)
                    points.add(Vec3(real.x, real.y + 0.5, real.z))
                }
                if (points.size >= 2) {
                    ctx.drawLine(points, stepLineColour, depth = true, thickness = 2.5f)
                }
            }
        }

        on<WorldEvent.Change> {
            cancelPlayback()
            centerTrackedRoom = null
            centerTickCounter = 0
            activeRoomName = null
        }

        on<DungeonEvent.Room.Enter> {
            if (room?.name != activeRoomName) cancelPlayback()
        }

        on<DungeonEvent.Secret.Interact> {
            secretInteractCounter++
        }
    }

    // ------------------------------------------------------------- public API

    /** Current room that is being played, or null if idle. */
    val activePlaybackRoomName: String? get() = activeRoomName

    fun isPlaying(): Boolean = playbackJob?.isActive == true

    // --------------------------------------------------------------- playback

    private fun toggleManualPlayback() {
        if (mc.screen != null) return

        if (playbackJob?.isActive == true) {
            cancelPlayback()
            modMessage("&eSecret Routes: playback cancelled.")
            return
        }

        val room = currentRoom
        val route = room?.let { routes[it.name] }
        if (room == null || route == null) {
            modMessage("&cSecret Routes: no route for this room.")
            return
        }
        beginPlayback(room, route)
    }

    private fun beginPlayback(room: OdonRoom, route: RoomRoute) {
        if (playbackJob?.isActive == true) return
        activeRoomName = room.name

        playbackJob = scope.launch {
            runCatching {
                playRoute(room, route)
            }.onFailure { t ->
                if (t !is CancellationException) {
                    modMessage("&cSecret Routes: playback error &7(${t.javaClass.simpleName}): ${t.message ?: "?"}")
                    t.printStackTrace()
                }
            }
            if (activeRoomName == room.name) activeRoomName = null
        }
    }

    private fun cancelPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        activeRoomName = null
        releaseSneak()
    }

    private suspend fun playRoute(room: OdonRoom, route: RoomRoute) {
        for (step in route.steps) {
            if (!isModuleStillActive(room.name)) return

            when (step.type) {
                RouteStepType.ETHERWARP  -> runEtherwarp(room, step)
                RouteStepType.AOTV       -> runAotv(room, step)
                RouteStepType.PLACE_TNT  -> runPlaceTnt(room, step)
                RouteStepType.BREAK_BLOCK-> runBreakBlock(room, step)
                RouteStepType.USE_ITEM   -> runUseItem(room, step)
                RouteStepType.WAIT_BAT   -> runWaitBat(room, step)
            }

            // CritsAddons "awaits": wait for N secret-progress events before
            // moving on to the next step.
            if (step.awaitSecrets > 0) waitForSecretProgress(step.awaitSecrets)
        }
    }

    private fun isModuleStillActive(roomName: String): Boolean {
        if (!enabled) return false
        if (currentRoom?.name != roomName) return false
        return true
    }

    // --------------------------------------------------------------- step fns

    private suspend fun runEtherwarp(room: OdonRoom, step: RouteStep) {
        val tgt = step.localTarget ?: return
        waitForMana(etherwarpManaCost.toInt())
        rotateToTargetWorld(room, step.localPos, tgt)
        pressSneak(true)
        try {
            delay(40)
            mc.player?.rightClick()
            waitForLanding(room.getRealCoords(tgt))
        } finally {
            pressSneak(false)
        }
    }

    private suspend fun runAotv(room: OdonRoom, step: RouteStep) {
        val rv = step.rotationVec ?: return
        rotateToDirectionWorld(room, rv)
        delay(40)
        mc.player?.rightClick()
        delay(120)
    }

    private suspend fun runPlaceTnt(room: OdonRoom, step: RouteStep) {
        val tgt = step.localTarget ?: return
        rotateToTargetWorld(room, step.localPos, tgt)
        ensureHoldingTnt()
        delay(40)
        mc.player?.rightClick()
        delay(120)
    }

    private suspend fun runBreakBlock(room: OdonRoom, step: RouteStep) {
        // Look at the first listed block (CritsAddons routes usually only carry one).
        val first = step.blocks.firstOrNull()
        if (first != null) {
            val tgt = Vec3(first.x + 0.5, first.y + 0.5, first.z + 0.5)
            rotateToTargetWorld(room, step.localPos, tgt)
        } else if (step.rotationVec != null) {
            rotateToDirectionWorld(room, step.rotationVec)
        }
        delay(40)
        mc.player?.leftClick()
        delay(120)
    }

    private suspend fun runUseItem(room: OdonRoom, step: RouteStep) {
        val rv = step.rotationVec
        if (rv != null) rotateToDirectionWorld(room, rv)
        // If a Hyperion-class sword is on the hotbar, prefer swapping to it before
        // firing — covers the common "use Hyperion to break a wither door" case.
        val hyp = findHyperionSlot()
        if (hyp != null) {
            waitForMana(hyperionManaCost.toInt())
            SwapManager.swapToSlot(hyp)
            delay(60)
        }
        delay(40)
        mc.player?.rightClick()
        delay(120)
    }

    private suspend fun runWaitBat(room: OdonRoom, step: RouteStep) {
        // Bat steps in CritsAddons format carry an explicit local yaw/pitch.
        val yaw = step.explicitYaw
        val pitch = step.explicitPitch
        if (yaw != null && pitch != null) rotateToSaved(room, yaw, pitch)
        val deadline = System.currentTimeMillis() + batWaitTimeoutMs.toInt()
        while (System.currentTimeMillis() < deadline) {
            if (PersistentSecretHeads.hasSpawnedBatInCurrentRoom()) return
            delay(50)
        }
    }

    private suspend fun waitForSecretProgress(needed: Int) {
        val startCount = SkyblockPlayer.currentSecrets
        val startInteracts = secretInteractCounter
        val deadline = System.currentTimeMillis() + secretProgressTimeoutMs.toInt()
        while (System.currentTimeMillis() < deadline) {
            val secretsGained = SkyblockPlayer.currentSecrets - startCount
            val interactsGained = secretInteractCounter - startInteracts
            if (secretsGained >= needed || interactsGained >= needed) return
            delay(50)
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun waitForMana(cost: Int) {
        if (cost <= 0) return
        val deadline = System.currentTimeMillis() + manaTimeoutMs.toInt()
        while (System.currentTimeMillis() < deadline) {
            if ((SkyblockPlayer.mana + SkyblockPlayer.overflowMana) >= cost) return
            delay(50)
        }
    }

    private suspend fun rotateToSaved(room: OdonRoom, savedYaw: Float, savedPitch: Float) {
        val player = mc.player ?: return
        // NoammAddons saved its yaw as (worldYaw - room.rotation.deg). COP's room.getRealYaw(yaw)
        // reverses the sign, so convert explicitly with the same formula used at save time.
        val worldYaw = wrapDegrees(savedYaw + room.rotation.deg)
        val worldPitch = savedPitch
        val ticks = rotationDurationTicks.toInt()
        if (ticks <= 0) {
            player.yaw = worldYaw
            player.pitch = worldPitch
            return
        }
        player.rotateSmoothly(worldYaw, worldPitch, duration = ticks.toFloat())
        Scheduler.wait(ticks + 1)
    }

    private suspend fun waitForLanding(target: Vec3) {
        val deadline = System.currentTimeMillis() + warpSettleTimeoutMs.toInt()
        while (System.currentTimeMillis() < deadline) {
            val p = mc.player ?: return
            val dx = p.x - target.x
            val dz = p.z - target.z
            val horiz = sqrt(dx * dx + dz * dz)
            val dy = kotlin.math.abs(p.y - target.y)
            if (horiz <= 1.25 && dy <= 1.5) return
            delay(50)
        }
    }

    /**
     * Compute world yaw/pitch for "stand at [stepLocalPos], look at [worldOrLocalTarget]"
     * and smoothly rotate the player there. The target is given in local coordinates
     * and converted to world coords up front.
     */
    private suspend fun rotateToTargetWorld(room: OdonRoom, stepLocalPos: Vec3, localTarget: Vec3) {
        val eyeLocal = Vec3(stepLocalPos.x, stepLocalPos.y + 1.62, stepLocalPos.z)
        val eyeWorld = room.getRealCoords(eyeLocal)
        val tgtWorld = room.getRealCoords(localTarget)
        val dx = tgtWorld.x - eyeWorld.x
        val dy = tgtWorld.y - eyeWorld.y
        val dz = tgtWorld.z - eyeWorld.z
        val (yaw, pitch) = yawPitchFromDir(dx, dy, dz)
        rotateRaw(yaw, pitch)
    }

    /**
     * Compute world yaw/pitch for a local-space unit direction vector and rotate.
     * The vector is in the room's local frame, so we adjust yaw by the room's
     * rotation and pass pitch through unchanged.
     */
    private suspend fun rotateToDirectionWorld(room: OdonRoom, localDir: Vec3) {
        val (localYaw, pitch) = yawPitchFromDir(localDir.x, localDir.y, localDir.z)
        val worldYaw = wrapDegrees(localYaw + room.rotation.deg)
        rotateRaw(worldYaw, pitch)
    }

    private suspend fun rotateRaw(worldYaw: Float, worldPitch: Float) {
        val player = mc.player ?: return
        val ticks = rotationDurationTicks.toInt()
        if (ticks <= 0) {
            player.yaw = worldYaw
            player.pitch = worldPitch
            return
        }
        player.rotateSmoothly(worldYaw, worldPitch, duration = ticks.toFloat())
        Scheduler.wait(ticks + 1)
    }

    private fun yawPitchFromDir(dx: Double, dy: Double, dz: Double): Pair<Float, Float> {
        val horiz = sqrt(dx * dx + dz * dz)
        // Mojang yaw: 0 = +Z (south), 90 = -X (west). atan2(-dx, dz) matches.
        val yaw = (Math.toDegrees(atan2(-dx, dz))).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, horiz))).toFloat()
        return yaw to pitch
    }

    private fun blockBoxAround(world: Vec3): AABB {
        val bx = floor(world.x); val bz = floor(world.z); val by = floor(world.y)
        return AABB(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0)
    }

    private fun findHyperionSlot(): Int? {
        val player = mc.player ?: return null
        for (i in 0..8) {
            val id = player.inventory.getItem(i).skyblockId ?: continue
            if (id in HYPERION_IDS) return i
        }
        return null
    }

    private suspend fun ensureHoldingTnt() {
        val player = mc.player ?: return
        val held = player.mainHandItem
        if (held.isTnt) return
        val slot = (0..8).firstOrNull { player.inventory.getItem(it).isTnt } ?: return
        SwapManager.swapToSlot(slot)
        delay(60)
    }

    private val ItemStack.isTnt: Boolean
        get() {
            if (isEmpty) return false
            val id = skyblockId ?: return false
            // Superboom TNT & vanilla-ish TNT items the router may place.
            return id == "SUPERBOOM_TNT" || id.contains("TNT")
        }

    private fun pressSneak(down: Boolean) {
        val shiftKey = (mc.options.keyShift as KeyMappingAccessor).key
        KeyMapping.set(shiftKey, down)
    }

    private fun releaseSneak() = pressSneak(false)

    private fun colourForStep(type: RouteStepType): Colour = when (type) {
        RouteStepType.ETHERWARP   -> etherwarpColour
        RouteStepType.AOTV        -> etherwarpColour
        RouteStepType.PLACE_TNT   -> tntColour
        RouteStepType.BREAK_BLOCK -> breakColour
        RouteStepType.USE_ITEM    -> secretColour
        RouteStepType.WAIT_BAT    -> Colour.BLACK.withAlpha(0.2f)
    }

    // ------------------------------------------------------------ JSON loader
    //
    // Bundled JSON is the **CritsAddons format** — top-level map of room name to
    // step array. Each step has a lowercase `type`, a `localPos` (room-local Vec3
    // where the player should be standing), `radius`, `start: bool`, an optional
    // `awaits.awaitSecrets` count, and type-specific extras (`localTarget`,
    // `target`, `rotationVec`, `blocks`).

    private fun loadRoutesOnce() {
        val bundled = runCatching {
            SecretRoutes::class.java.getResourceAsStream("/assets/cop/secretRoutes.json")?.use { stream ->
                stream.bufferedReader().readText()
            }
        }.getOrNull()

        val override = runCatching {
            val f = File("config/cop/secretRoutes.json")
            if (f.exists() && f.isFile) f.readText() else null
        }.getOrNull()

        val combined = mutableMapOf<String, RoomRoute>()
        bundled?.let { parseInto(it, combined) }
        override?.let { parseInto(it, combined) }
        routes = combined
        if (combined.isEmpty()) {
            modMessage("&eSecret Routes: no routes loaded — route DB not found.")
        }
    }

    private fun parseInto(raw: String, target: MutableMap<String, RoomRoute>) {
        runCatching {
            val gson = Gson()
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return@runCatching

            for ((roomName, element) in root.entrySet()) {
                val arr = element as? JsonArray ?: continue
                val parsedSteps = arr.mapNotNull { el ->
                    val stepObj = el as? JsonObject ?: return@mapNotNull null
                    parseStep(stepObj)
                }
                if (parsedSteps.isEmpty()) continue

                // The route's start step is the one flagged `start: true`. If none is
                // flagged (rare), fall back to the first step.
                val startStep = parsedSteps.firstOrNull { it.isStart } ?: parsedSteps.first()

                target[roomName] = RoomRoute(
                    startLocal  = startStep.step.localPos,
                    startRadius = startStep.radius,
                    steps       = parsedSteps.map { it.step },
                )
            }
        }.onFailure { it.printStackTrace() }
    }

    private data class ParsedStep(val step: RouteStep, val isStart: Boolean, val radius: Float)

    private fun parseStep(obj: JsonObject): ParsedStep? {
        val typeName = obj.get("type")?.asString?.lowercase() ?: return null
        val localPos = obj.getAsJsonObject("localPos")?.toVec3() ?: return null
        val radius = obj.get("radius")?.asFloat ?: 0.5f
        val isStart = obj.get("start")?.asBoolean ?: false

        // CritsAddons puts the post-step wait in `awaits.awaitSecrets`. Some routes
        // also use `awaits.awaitSecrets` to mean "wait for N secret triggers".
        val awaitSecrets = obj.getAsJsonObject("awaits")
            ?.get("awaitSecrets")?.asInt ?: 0

        val type = when (typeName) {
            "etherwarp" -> RouteStepType.ETHERWARP
            "aotv"      -> RouteStepType.AOTV
            "boom"      -> RouteStepType.PLACE_TNT
            "break"     -> RouteStepType.BREAK_BLOCK
            "bat"       -> RouteStepType.WAIT_BAT
            "use"       -> RouteStepType.USE_ITEM
            else        -> return null
        }

        // Optional fields (each step type uses a subset).
        val localTarget = obj.getAsJsonObject("localTarget")?.toVec3()
            ?: obj.getAsJsonObject("target")?.toVec3()
        val rotationVec = obj.getAsJsonObject("rotationVec")?.toVec3()
        val explicitYaw = obj.get("yaw")?.asFloat
        val explicitPitch = obj.get("pitch")?.asFloat
        val blocks = obj.getAsJsonArray("blocks")?.mapNotNull {
            (it as? JsonObject)?.toBlockPos()
        }.orEmpty()

        return ParsedStep(
            step = RouteStep(
                type = type,
                localPos = localPos,
                localTarget = localTarget,
                rotationVec = rotationVec,
                explicitYaw = explicitYaw,
                explicitPitch = explicitPitch,
                blocks = blocks,
                awaitSecrets = awaitSecrets,
            ),
            isStart = isStart,
            radius = radius,
        )
    }

    private fun JsonObject.toVec3(): Vec3? {
        val x = get("x")?.asDouble ?: return null
        val y = get("y")?.asDouble ?: return null
        val z = get("z")?.asDouble ?: return null
        return Vec3(x, y, z)
    }

    private fun JsonObject.toBlockPos(): BlockPos? {
        val x = get("x")?.asDouble ?: return null
        val y = get("y")?.asDouble ?: return null
        val z = get("z")?.asDouble ?: return null
        return BlockPos(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
    }

    // ----------------------------------------------------------- data classes

    internal enum class RouteStepType {
        ETHERWARP,    // sneak + right-click (etherwarpable item)
        AOTV,         // right-click (Aspect of the Void teleport)
        PLACE_TNT,    // swap to TNT, right-click
        BREAK_BLOCK,  // left-click target block
        WAIT_BAT,     // rotate to explicit yaw/pitch, wait for bat
        USE_ITEM,     // right-click held item (Hyperion/Bonzo Mask/etc.)
    }

    internal data class RouteStep(
        val type: RouteStepType,
        val localPos: Vec3,
        val localTarget: Vec3? = null,
        val rotationVec: Vec3? = null,
        val explicitYaw: Float? = null,
        val explicitPitch: Float? = null,
        val blocks: List<BlockPos> = emptyList(),
        val awaitSecrets: Int = 0,
    )

    internal data class RoomRoute(
        val startLocal: Vec3,
        val startRadius: Float,
        val steps: List<RouteStep>,
    )

    private val HYPERION_IDS = setOf("HYPERION", "ASTRAEA", "VALKYRIE", "SCYLLA")
}
