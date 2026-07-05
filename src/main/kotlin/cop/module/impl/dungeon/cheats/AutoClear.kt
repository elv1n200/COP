package cop.module.impl.dungeon.cheats

import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.player.Input
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import cop.CopMod.scope
import cop.api.abobaui.dsl.*
import cop.api.colour.*
import cop.api.events.*
import cop.api.pathfinding.impl.EtherwarpPathfinder
import cop.api.pathfinding.teleport.PathConfig
import cop.api.pathfinding.teleport.TeleportPathNode
import cop.api.pathfinding.teleport.impl.TeleportEtherwarpPathfinder
import cop.api.pathfinding.teleport.impl.TransmissionPathfinder
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.currentRoom
import cop.api.skyblock.dungeon.Dungeon.inClear
import cop.api.skyblock.dungeon.Dungeon.isDead
import cop.api.skyblock.dungeon.odonscanning.MapRenderer
import cop.api.skyblock.dungeon.odonscanning.MapRenderer.renderMap
import cop.module.impl.dungeon.cheats.autoclear.MobClusterer
import cop.module.impl.dungeon.worldrender.DungeonESP
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom
import cop.api.skyblock.dungeon.odonscanning.tiles.RoomComponent
import cop.api.skyblock.dungeon.odonscanning.tiles.Rotations
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.Setting.Companion.json
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.*
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.containsOneOf
import cop.utils.WorldUtils.etherwarpable
import cop.utils.WorldUtils.nearbyBlocks
import cop.utils.WorldUtils.state
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.PlayerUtils.useItem
import cop.utils.skyblock.player.SwapManager
import cop.utils.ui.hud.impl.TextHud
import cop.utils.ui.screens.UIScreen.Companion.open
import kotlin.math.ceil

/**
 * TODO:
 *  clearable rooms highlight:
 *    - can't be asked tbh, just use brain
 *  auto mobs clear maybe (star mob esp needs a recode for it)
 *  auto auto clear:
 *   -room queuing while auto routing option
 *   -auto room queueing option
 *
 */
object AutoClear : Module(
    "Auto clear",
    desc = "Automatically teleports to a specified room.",
    area = Island.Dungeon(inClear = true)
) {
    private val info by text(
        """
            &e! &rExperimental — expect occasional bugs. &e!
        """.trimIndent()
    )

    private val keepChunks by switch("Keep chunks loaded", true, desc = "Keeps the chunks loaded. Good for long distances.")

    private val openKey by keybind("Open key")
        .onPress {
            if (!enabled || !inClear || isDead) return@onPress
            if (mc.screen?.title?.string == "cop clear map" && closeOn.index == 1) {
                mc.setScreen(null)
            } else if (mc.screen == null) {
                open(map(), background = false)
            }
        }
        .onRelease {
            if (!enabled || closeOn.index != 0 || !inClear || isDead) return@onRelease
            if (mc.screen?.title?.string == "cop clear map") mc.setScreen(null)
        }

    private val closeOn by segmented("Close on", "Release", listOf("Release", "Repress"))

    private val clearMobsKey by keybind("Clear mobs key", desc = "Auto-clears the starred mobs in the current room with Hyperion (experimental).")
        .onPress {
            if (enabled && inClear && !isDead) clearMobs()
        }

    private val visuals by text("Visuals")
    private val shadow by switch("Shadow", true).childOf(::visuals).asParent()
    private val font by segmented("Font", TextHud.HudFont.Minecraft).childOf(::visuals)
    private val fontScale by slider("Font scale", 1f, 0.5f, 3f, 0.1f).childOf(::visuals)
    private val scale by slider("Map scale", 5f, 1f, 10f, 0.5f).childOf(::visuals)
    private val roomRadius by slider("Room radius", 5f, 1f, 10f, 1f).childOf(::visuals)
    val roomInCol by colourPicker("Highlight colour", Colour.GREY.withAlpha(0.5f), allowAlpha = true, desc = "Room the player currently in colour.").childOf(::visuals)

//    private val icons by switch("Icons")
//    private val showHeads by switch("Show player heads").childOf(::icons)
//    private val showOwnHead by switch("Show own head").childOf(::showHeads)
//    private val iconScale by slider("Icon scale", 1f, 0.1f, 3.0f, 0.1f).childOf(::showHeads)
//    private val iconBorder by switch("Border").json("Icon border").childOf(::showHeads)
//    private val classColour by switch("Class border colour").childOf(::iconBorder)
//    private val iconBorderColour by colourPicker("Border colour", Colour.BLACK).json("Icon border colour").childOf(::iconBorder) { !classColour }
//    private val iconBorderThickness by slider("Border thickness", 2, 1, 10, unit = "px").json("Icon border thickness").childOf(::iconBorder)
//
//    private val showNames by switch("Show names").childOf(::icons)
//    private val nameScale by slider("Name scale", 0.8f, 0.1f, 3.0f, 0.1f).childOf(::showNames)

    private val sett by text("Pathfinder settings")
    private val yawStep by slider("Yaw step", 22f, 10f, 30f, desc = "Horizontal density of raycasts. Lower values increase precision but reduce performance.").childOf(::sett)
    private val pitchStep by slider("Pitch step", 22f, 10f, 30f, desc = "Vertical density of raycasts. Lower values increase precision but reduce performance.").childOf(::sett)
    private val hWeight by slider("Guess weight", 6.7, 1.0, 15.0, 0.1, desc = "Higher values make the search much faster; due to path smoothing, the difference in final path quality is negligible.").childOf(::sett)
    private val threads by slider("Threads", 2, 1, 16, desc = "Number of CPU threads to use for simultaneous path expansion.").childOf(::sett)
    private val timeout by slider("Timeout", 1000L, 1000L, 2000L, 50L, unit = "ms", desc = "Maximum time allowed for the pathfinder to search before giving up.").childOf(::sett)

    private var nodes: MutableList<ClearNode>? = null

    private var delay = 0
    private var postDelay = 0
    private var hypeDelay = 0
    var active = false
        private set

    private var pending: Direction? = null
    private var position: Stupid? = null

    private val roomOverrides get() = mapOf(
        "Creeper Beams" to BlockPos(15, 68, 5),
        "Three Weirdos" to BlockPos(15, 68, 22),
        "Water Board" to BlockPos(15, 58, 9),
        "Ice Path" to BlockPos(10, 67, 18),
        "Tic Tac Toe" to BlockPos(11, 68, 16),
        "Ice Fill" to BlockPos(15, 69, 18),
        "Old Trap" to BlockPos(15, 68, -2),
        "New Trap" to BlockPos(15, 68, -2),
        "Cages" to BlockPos(15, 64, 16)
    )

    private val coreOverrides get() = mapOf( // suboptimal if room rot is none, todo find a different way to find goal
        "Gold" to mapOf(
            35550104 to BlockPos(5, 68, 15),
            992885012 to BlockPos(55, 68, 15)
        ),
        "Layers" to mapOf(
            161195688 to BlockPos(53, 68, 53)
        ),

        "Mage" to mapOf(
            925853313 to BlockPos(15, 75, 15)
        ),
        "Deathmite" to mapOf(
            706341009 to BlockPos(5, 68, 15)
        )
    )
    
    init {
        on<PacketEvent.Received> {
            when (packet) {
                is ClientboundPlayerPositionPacket -> if (delay == 1) delay = 2
                is ClientboundForgetLevelChunkPacket -> if (keepChunks) cancel()
            }
        }

        on<TickEvent.Server> {
            if (delay < 2) return@on
            if (delay++ > 9) delay = 0
        }

        on<TickEvent.Start> {

            pending?.let {
                player.useItem(it.yaw, it.pitch)
                pending = null
            }

            if (hypeDelay > 0) hypeDelay--

            if (postDelay > 0) {
                if (--postDelay == 0) active = false
            }

            if (delay != 0) return@on

            if (postDelay == 0) active = false

            val nodes = nodes ?: return@on
            if (nodes.isEmpty()) return@on

            if (position == null) {
                position = Stupid(player.x, player.y, player.z)
            }
            val stupid = position!!

            handleQueue(stupid, nodes)
        }

        on<KeyEvent.Input> {
            if (!active) return@on
            // Only etherwarp needs sneak held; forcing it during a Hyperion cast
            // would just keep us crouched. Gate on the next node's type.
            if (nodes?.firstOrNull() !is ClearEtherNode) return@on
            val old = clientInput

            val new = Input(
                old.forward,
                old.backward,
                old.left,
                old.right,
                old.jump,
                true,
                old.sprint
            )

            input.apply(new)
        }

        on<WorldEvent.Change> {
            nodes = null
            delay = 0
            position = null
            active = false
            postDelay = 0
            hypeDelay = 0
        }
    }

    private fun handleQueue(stupid: Stupid, nodes: MutableList<ClearNode>): Boolean {
        val index = nodes.indexOfFirst { it.inside(stupid) }

        if (index < 0) return false

        val node = nodes[index]

        // Let the Wither-Impact teleport settle a few ticks before the next
        // node's position check runs, so we don't stall on the desync.
        if (node is ClearHypeNode && hypeDelay > 0) return false

        if (player.mainHandItem.skyblockId !in node.items) {
            if (!SwapManager.swapById(*node.items).success) {
                this.nodes = null
                position = null
            }
            return false
        }

        active = true

        if (node.execute(stupid)) {
            nodes.removeAt(index)
            if (node is ClearHypeNode) hypeDelay = 3
            if (nodes.isEmpty()) {
                this.nodes = null
                position = null
                delay = 1
                postDelay = 2
            }
            return true
        }

        return false
    }

    fun getPath(room: OdonRoom, comp: RoomComponent, button: Int) {
        if (!player.onGround()) return
        if (button != 0 && button != 1) return
        if (currentRoom?.name?.containsOneOf("Maze", "Boulder") == true) return
        if (currentRoom?.name?.contains("Trap") == true && currentRoom!!.getRelativeCoords(player.blockPosition()).z >= 0) return

        var start = BlockPos(player.x, ceil(player.y - 1), player.z)
        var dir = getEtherwarpDirection(start)

        if (dir == null) {
            start = start.nearbyBlocks(4f).find { pos ->
                pos.etherwarpable && getEtherwarpDirection(pos).also { dir = it } != null
            } ?: return modMessage("Could not find a valid etherwarpable block nearby.")
        }

        if (button != 0) return
        val goal = run {
            val overridePos = roomOverrides[room.name] ?: coreOverrides[room.name]?.get(comp.core)

            if (overridePos != null && room.rotation != Rotations.NONE) {
                room.getRealCoords(overridePos)
            } else {
                comp.blockPos.nearbyBlocks(25f) { it.etherwarpable && it.state.block != Blocks.REDSTONE_BLOCK }.firstOrNull()
                    ?: return modMessage("Couldn't find goal position for tile &e${comp.core}&r in ${room.name}")
            }
        }

        scope.launch {
            val p = EtherwarpPathfinder.findPath(
                start = start,
                goal = goal,
                yawStep = yawStep,
                pitchStep = pitchStep,
                hWeight = hWeight,
                threads = threads,
                timeout = timeout,
                offset = true,
                dist = 60.0
            ) ?: return@launch

            val new = mutableListOf<ClearNode>()

            new.add(ClearEtherNode(player.position(), dir!!.yaw, dir.pitch))

            new.addAll(p.dropLast(1).map { node ->
                val pos = node.pos.center.addVec(y = 0.5)
                ClearEtherNode(pos, node.yaw, node.pitch)
            }.toMutableList())

            nodes = new

            position = null
            pending = null
        }

    }

    /**
     * Auto-clears the current room's starred mobs with a wither blade. Faithful
     * port of quoi's `pathToMobs` (pigeonlover1998):
     *
     *  1. Scan starred mobs ([DungeonESP.scanStarredMobs]) + group them into the
     *     fewest Hyperion casts, greedy-ordered ([MobClusterer]).
     *  2. For each cluster, pathfind from the current position by distance:
     *     - `> 36` blocks → etherwarp there, then cast down at it ([toRotHype]).
     *     - `10–36`      → AOTV transmission hops, then a hype cast.
     *     - `<= 10`      → chain hype casts straight to it.
     *  3. Feed the node list to the executor.
     *
     * The transmission physics ([predictTransmission]) used by both the pathfinder
     * and the hype/aotv nodes keep the simulated queue exactly aligned with where
     * the player really lands, which is what my earlier etherwarp-only adaptation
     * couldn't do.
     */
    fun clearMobs() {
        if (!player.onGround()) return
        if (currentRoom?.name?.containsOneOf("Maze", "Boulder") == true) return

        val mobs = DungeonESP.scanStarredMobs()
        if (mobs.isEmpty()) return modMessage("&cAuto Clear: no starred mobs found.")

        scope.launch {
            val from = player.position()
            val clusters = MobClusterer.getOrderedClusters(from, mobs)
            if (clusters.isEmpty()) return@launch modMessage("&cAuto Clear: couldn't cluster mobs.")

            val config = PathConfig(
                pitchStep = pitchStep, yawStep = yawStep, hWeight = hWeight,
                threads = threads, timeout = timeout, feedback = false,
            )
            val new = mutableListOf<ClearNode>()

            // The executor chains purely on its simulated position — each node must
            // sit exactly where the previous one lands. So we track [simPos] the
            // same way the executor will (advance it by each node's real landing)
            // and pathfind / aim every segment from it. Setting the next segment's
            // start to a guessed spot (the cluster centre) was the desync that
            // stopped the clear after one cast.
            var simPos = from

            fun push(node: ClearNode) {
                new.add(node)
                val eye = Vec3(simPos.x, simPos.y + getEyeHeight(node.sneak), simPos.z)
                val res = node.raycast(eye)
                if (res.succeeded && res.pos != null) {
                    simPos = Vec3(res.pos.x + 0.5, res.pos.y + node.yOff, res.pos.z + 0.5)
                }
            }

            for (cluster in clusters) {
                val to = cluster.pos
                val toVec = to.vec3
                val nWord = to.center.addVec(y = 0.5)
                val dist = simPos.distanceTo(toVec)

                // Within one cast: aim at the cluster and fire in place, no path.
                // A transmission A* can't land exactly on a flat, same-level target
                // a few blocks away (nothing stops the ray horizontally, so it
                // overshoots), which is why pathfinding this last stretch failed.
                // A single Wither Impact still AOEs the cluster.
                if (dist <= 10.0) {
                    val eye = Vec3(simPos.x, simPos.y + getEyeHeight(false), simPos.z)
                    val aim = getDirection(eye, Vec3.atCenterOf(to).add(0.0, 1.0, 0.0))
                    push(ClearHypeNode(simPos, aim.yaw, aim.pitch))
                    continue
                }

                val segment = when {
                    dist > 36.0 -> TeleportEtherwarpPathfinder.findPath(simPos, to, config, withLast = true)
                    else -> TransmissionPathfinder.findPath(simPos, to, config, dist = 12.0, radius = 2.0)
                }

                if (segment.isNullOrEmpty()) return@launch modMessage("&cAuto Clear: pathfind failed on a cluster.")

                val last = segment.last()
                val body = segment.dropLast(1)

                if (dist > 36.0) {
                    body.forEach { push(it.toEther()) }
                    push(last.toRotHype())
                } else {
                    body.forEach { push(it.toAotv()) }
                    if (last.vec.distanceTo(toVec) > 10.0) {
                        push(last.toAotv())
                        push(last.toRotHype(to, nWord.x, nWord.y, nWord.z))
                    } else {
                        push(last.toHype())
                    }
                }
            }

            if (new.isEmpty()) return@launch modMessage("&cAuto Clear: couldn't build a path to the mobs.")

            nodes = new
            position = null
            pending = null
            modMessage("&aAuto Clear: ${clusters.size} cluster(s), ${mobs.size} mob(s), ${new.size} nodes.")
        }
    }

    // -- TeleportPathNode -> ClearNode converters (quoi) --------------------
    private fun TeleportPathNode.toEther() = ClearEtherNode(vec, yaw, pitch)
    private fun TeleportPathNode.toAotv() = ClearAotvNode(vec, yaw, pitch)
    private fun TeleportPathNode.toHype() = ClearHypeNode(vec, yaw, pitch)
    /** A hype cast looking straight down (0, 90) — used to AOE the cluster once
     *  you've teleported onto/next to it. Position can be overridden. */
    private fun TeleportPathNode.toRotHype(pos: BlockPos = this.pos, x: Double = this.x, y: Double = this.y, z: Double = this.z) =
        TeleportPathNode(x, y, z, pos, g, h, parent, 0f, 90f).toHype()

    private fun map() = aboba("cop clear map") {
//        val iconCfg = MapRenderer.IconConfig(
//            scale = iconScale,
//            heads = showHeads,
//            ownHead = showOwnHead,
//            border = iconBorder,
//            borderColour = iconBorderColour,
//            classColour = classColour,
//            thickness = iconBorderThickness,
//            name = showNames,
//            whenLeap = false,
//            nameScale = nameScale
//        )

        val cfg = MapRenderer.MapConfig(
            scale = scale,
            radius = roomRadius,
            font = font.selected.get(),
            fontScale = fontScale,
            shadow = shadow,
            autoClear = true,
//            icons = icons,
//            icon = iconCfg
        )

        renderMap(config = cfg)
    }

    private data class Stupid(var x: Double, var y: Double, var z: Double)

    /**
     * One teleport in a clear path. Three primitives, matching quoi:
     *  - [ClearEtherNode]  — AOTV etherwarp (sneak, ~60 blocks, lands on a block).
     *  - [ClearAotvNode]   — AOTV instant transmission (no sneak, ~12-block air hop).
     *  - [ClearHypeNode]   — wither-blade Wither Impact (no sneak, ~10-block transmit + AOE).
     *
     * Each provides the [items] it teleports with, whether it's [sneak]ed, the
     * standing [yOff]set after landing, and its [raycast] physics (etherwarp voxel
     * traversal vs transmission prediction) — all driven by the same [EtherPos]
     * result so the executor stays generic.
     */
    private abstract class ClearNode(val pos: Vec3, val yaw: Float, val pitch: Float) {
        abstract val items: Array<String>
        abstract val sneak: Boolean
        abstract val yOff: Double
        abstract fun raycast(from: Vec3): EtherPos

        fun inside(stupid: Stupid): Boolean {
            val dx = pos.x - stupid.x
            val dy = pos.y - stupid.y
            val dz = pos.z - stupid.z
            return dx.sq + dy.sq + dz.sq <= 0.1
        }

        fun execute(stupid: Stupid): Boolean {
            if (player.lastSentInput.shift != sneak) return false

            val from = Vec3(stupid.x, stupid.y + getEyeHeight(sneak), stupid.z)
            val res = raycast(from)

            if (!res.succeeded || res.pos == null) {
                nodes = null
                position = null
                postDelay = 2
                modMessage("failed from &c$from &e$yaw $pitch")
                return false
            }

            pending = Direction(yaw, pitch)
            stupid.x = res.pos.x + 0.5
            stupid.y = res.pos.y + yOff
            stupid.z = res.pos.z + 0.5
            return true
        }
    }

    /** AOTV etherwarp — sneak, lands on a solid block up to ~60 blocks along the aim. */
    private class ClearEtherNode(pos: Vec3, yaw: Float, pitch: Float) : ClearNode(pos, yaw, pitch) {
        override val items = arrayOf("ASPECT_OF_THE_VOID")
        override val sneak = true
        override val yOff = 1.05
        override fun raycast(from: Vec3): EtherPos = from.getEtherPos(yaw, pitch)
    }

    /** AOTV instant transmission — no sneak, ~12-block air hop. */
    private class ClearAotvNode(pos: Vec3, yaw: Float, pitch: Float) : ClearNode(pos, yaw, pitch) {
        override val items = arrayOf("ASPECT_OF_THE_VOID")
        override val sneak = false
        override val yOff = 1.0
        override fun raycast(from: Vec3): EtherPos = from.getTeleportPos(yaw, pitch, 12.0)
    }

    /** Wither-blade (Hyperion / Astraea / Scylla / Valkyrie) — no sneak, ~10-block
     *  Wither Impact transmit plus an AOE that kills the mobs at the landing.
     *  Lands via [predictTransmission] (getTeleportPos) — the real Wither Impact
     *  physics — so the simulated queue matches where the player really ends up. */
    private class ClearHypeNode(pos: Vec3, yaw: Float, pitch: Float) : ClearNode(pos, yaw, pitch) {
        override val items = arrayOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")
        override val sneak = false
        override val yOff = 1.0
        override fun raycast(from: Vec3): EtherPos = from.getTeleportPos(yaw, pitch, 10.0)
    }
}