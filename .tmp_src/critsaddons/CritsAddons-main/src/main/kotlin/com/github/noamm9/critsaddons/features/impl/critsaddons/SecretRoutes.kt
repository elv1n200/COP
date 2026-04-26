package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.MOD_NAME
import com.github.noamm9.critsaddons.CritsAddonsDefaults
import com.github.noamm9.event.EventPriority
import com.github.noamm9.event.impl.DungeonEvent
import com.github.noamm9.event.impl.KeyboardEvent
import com.github.noamm9.event.impl.PacketEvent
import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.TickEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ButtonSetting
import com.github.noamm9.ui.clickgui.components.impl.ColorSetting
import com.github.noamm9.ui.clickgui.components.impl.KeybindSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.TextInputSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ActionBarParser
import com.github.noamm9.critsaddons.utils.BlockAimUtils
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.JsonUtils
import com.github.noamm9.utils.MathUtils
import com.github.noamm9.utils.PlayerUtils
import com.github.noamm9.utils.Utils.equalsOneOf
import com.github.noamm9.utils.dungeons.DungeonUtils
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.items.EtherwarpHelper
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render3D
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.awt.Color
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

object SecretRoutes : Feature(
    description = "Record and replay one secret route per dungeon room.",
    name = "Secret Routes",
    toggled = true
) {
    private const val INTERACT_DELAY_MS = 75L
    private const val WARP_SETTLE_TIMEOUT_MS = 3_000L
    private const val MANA_CHECK_INTERVAL_MS = 100L
    private const val BLOCK_MESSAGE_COOLDOWN_MS = 1_000L
    private const val BREAK_RECORD_COOLDOWN_MS = 300L
    private const val BREAK_STEP_DELAY_MS = 100L
    private const val TNT_RECORD_COOLDOWN_MS = 300L
    private const val AUTO_START_CENTER_TOLERANCE = 0.05
    private const val START_LINK_MAX_HOPS = 64
    private const val KEYBINDS_SECTION = "keybinds"
    private const val PLAYBACK_SECTION = "playback"
    private const val RENDER_SECTION = "render"
    private const val SAFETY_SECTION = "safety"
    private const val COSTS_SECTION = "costs"
    private const val CONFIG_SECTION = "config"
    private val WITHER_BLADE_ITEM_IDS = arrayOf("HYPERION", "ASTRAEA", "VALKYRIE", "SCYLLA")

    private val playbackKeybind by KeybindSetting("Playback Keybind").section(KEYBINDS_SECTION)
    private val rotationTimeMs by SliderSetting("Rotation Time (ms)", 170, 5, 500, 5)
        .section(PLAYBACK_SECTION)
        .withDescription("How long smooth playback rotations take.")
    private val waitStepTimeoutSeconds by SliderSetting("Wait Step Timeout (s)", 5.0, 0.5, 5.0, 0.1)
        .withDescription("How long /nsr wait steps wait for secret progress before skipping.")
    private val batWaitTimeoutSeconds by SliderSetting("Bat Wait Timeout (s)", 5.0, 0.5, 5.0, 0.1)
        .withDescription("How long /nsr bat steps wait for a bat spawn before skipping.")
    private val autoStart by ToggleSetting("Auto Start", true)
        .withDescription("Automatically starts playback while in the room when a valid route start condition is met.")
    private val startBlocksOnly by ToggleSetting("Start Blocks Only", true)
        .showIf { autoStart.value }
        .withDescription("Only auto-start from the main/alternate start blocks, not from route step blocks.")
    private val centerOnly by ToggleSetting("Center Only", true)
        .showIf { autoStart.value }
        .withDescription("Requires standing at block center for auto-start checks.")
    private val centerHoldSeconds by SliderSetting("Center Hold Time (s)", 0.0, 0.0, 5.0, 0.1)
        .showIf { autoStart.value && centerOnly.value }
        .withDescription("How long you must stay in the center area before auto-start triggers.")
    private val centerRadius by SliderSetting("Center Radius", 0.1, 0.0, 1.0, 0.01)
        .showIf { autoStart.value && centerOnly.value }
        .withDescription("0 = exact center, 1 = nearly anywhere on the block.")
    private val startRouteFromAnywhere by ToggleSetting("Start Route From Anywhere", false)
        .withDescription("Lets playback start from a centered recorded route step block, such as EW 18.")
    private val renderStart by ToggleSetting("Show Start Block", true).section(RENDER_SECTION)
    private val renderEnd by ToggleSetting("Show End Blocks", true)
    private val renderThroughWalls by ToggleSetting("Render Through Walls", true)
    private val partialRoutes by ToggleSetting("Partial Routes", false).withDescription("Shows only the route up to the next cached secret for the room.")
    private val startBlockColor by ColorSetting("Start Block Color", Color(80, 220, 255, 120), true).showIf { renderStart.value }
    private val endBlockColor by ColorSetting("End Block Color", Color(255, 210, 85, 120), true).showIf { renderEnd.value }
    private val endHelperBlockColor by ColorSetting("End Helper Color", Color(255, 245, 140, 120), true).showIf { renderEnd.value }
    private val renderEtherwarpLines by ToggleSetting("Show Etherwarp Lines", true)
    private val etherwarpLineColor by ColorSetting("Etherwarp Line Color", Color(85, 255, 255, 180), true).showIf { renderEtherwarpLines.value }
    private val etherwarpLineWidth by SliderSetting("Etherwarp Line Width", 2.5f, 1f, 8f, 0.1f).showIf { renderEtherwarpLines.value }
    private val renderEtherwarpTargets by ToggleSetting("Show Etherwarp Targets", true)
    private val etherwarpTargetColor by ColorSetting("Etherwarp Target Color", Color(85, 255, 255, 120), true).showIf { renderEtherwarpTargets.value }
    private val renderTntTargets by ToggleSetting("Show TNT Targets", true)
    private val tntTargetColor by ColorSetting("TNT Target Color", Color(255, 140, 0, 120), true).showIf { renderTntTargets.value }
    private val renderHyperionTargets by ToggleSetting("Show Hyperion Targets", true)
    private val hyperionTargetColor by ColorSetting("Hyperion Target Color", Color(255, 0, 255, 120), true).showIf { renderHyperionTargets.value }
    private val renderBreakTargets by ToggleSetting("Show Break Targets", true)
    private val breakTargetColor by ColorSetting("Break Target Color", Color(255, 64, 64, 120), true).showIf { renderBreakTargets.value }
    private val renderSecretTargets by ToggleSetting("Show Secret Targets", true)
    private val secretTargetColor by ColorSetting("Secret Target Color", Color(0, 255, 120, 120), true).showIf { renderSecretTargets.value }
    private val autoHyperionOnLowEhp by ToggleSetting("Auto Hyperion on Low EHP", true).section(SAFETY_SECTION)
    private val lowEhpMissingThreshold by SliderSetting("Hyperion Heal Missing EHP %", 65, 1, 100, 1).showIf { autoHyperionOnLowEhp.value }
    private val etherwarpManaCost by SliderSetting("Etherwarp Mana Required", 100, 0, 500, 5).section(COSTS_SECTION)
    private val hyperionManaCost by SliderSetting("Hyperion Mana Required", 300, 0, 1000, 5)
    private val routesConfigFileName by TextInputSetting("Routes Config File", "secretRoutes.json")
        .section(CONFIG_SECTION)
        .withDescription("JSON file name inside config/$MOD_NAME to use for Secret Routes.")
    private val reloadRoutesConfigButton by ButtonSetting("Reload Routes File") {
        reloadRoutesConfigFromDisk()
    }.section(CONFIG_SECTION)
        .withDescription("Reloads routes from the selected routes file (for manual JSON edits).")

    private enum class RouteStepType {
        ETHERWARP,
        PLACE_TNT,
        BREAK_BLOCK,
        USE_HYPERION,
        RIGHT_CLICK_SECRET,
        WAIT_FOR_SECRET_PROGRESS,
        WAIT_FOR_BAT_SPAWN
    }

    private data class RouteStep(
        val type: RouteStepType,
        val pos: BlockPos? = null,
        val direction: Direction? = null,
        val secondaryPos: BlockPos? = null,
        val yaw: Float? = null,
        val pitch: Float? = null
    )

    private data class StartLink(
        val from: BlockPos,
        val to: BlockPos,
        val direction: Direction? = null,
        val yaw: Float? = null,
        val pitch: Float? = null
    )

    private data class EndLink(
        val from: BlockPos,
        val to: BlockPos,
        val direction: Direction? = null,
        val yaw: Float? = null,
        val pitch: Float? = null
    )

    private data class RoomRoute(
        val startBlock: BlockPos,
        val steps: MutableList<RouteStep> = mutableListOf(),
        val startLinks: MutableList<StartLink>? = null,
        val endLinks: MutableList<EndLink>? = null,
        val endBlocks: MutableList<BlockPos>? = null,
        val endHelperBlocks: MutableList<BlockPos>? = null
    )

    private data class RecordingSession(
        val roomName: String,
        val startBlock: BlockPos,
        val steps: MutableList<RouteStep> = mutableListOf(),
        val baseStepCount: Int = 0
    )

    private data class StartLinkRecordingSession(
        val roomName: String,
        val sourceStart: BlockPos
    )

    private enum class EndLinkRecordingType {
        HELPER,
        FINAL
    }

    private data class EndLinkRecordingSession(
        val roomName: String,
        val sourceEnd: BlockPos,
        val type: EndLinkRecordingType
    )

    private data class PlaybackPlan(
        val steps: List<RouteStep>,
        val usedAltStart: Boolean,
        val usedStartLink: Boolean = false,
        val resumedFromRouteStep: Boolean = false,
        val resumeLabel: String? = null,
        val primaryEndBlock: BlockPos? = null
    )

    private data class StartChainResolution(
        val success: Boolean,
        val links: List<StartLink> = emptyList(),
        val error: String? = null
    )

    private data class EndChainResolution(
        val success: Boolean,
        val links: List<EndLink> = emptyList(),
        val error: String? = null
    )

    private data class RoomContext(
        val room: UniqueRoom,
        val rotation: Int,
        val corner: BlockPos
    )

    data class RouteHelperSnapshot(
        val roomName: String,
        val sameStartAndOgEnd: Boolean,
        val startBlocksWorld: Set<BlockPos>,
        val endNodesWorld: Set<BlockPos>,
        val ogEndWorld: BlockPos?
    )

    private val routes = mutableMapOf<String, RoomRoute>()
    private val completedRooms = linkedSetOf<String>()
    private val completedSecretsByRoom = mutableMapOf<String, Int>()
    private val lastSecretProgressAtByRoom = mutableMapOf<String, Long>()

    private var recording: RecordingSession? = null
    private var startLinkRecording: StartLinkRecordingSession? = null
    private var endLinkRecording: EndLinkRecordingSession? = null
    private var playbackJob: Job? = null
    private var activePlaybackRoomName: String? = null
    private var lastBlockedMessageAt = 0L
    private var lastBreakRecord: Pair<BlockPos, Long>? = null
    private var lastTntRecord: Triple<BlockPos, Direction, Long>? = null
    private var lastAutoStartAt = 0L
    private var lastAutoStartBlock: BlockPos? = null
    private var centerHoldBlock: BlockPos? = null
    private var centerHoldStartedAt = 0L
    private var recordingPaused = false
    private var activeRoutesConfigPath: String? = null

    override fun init() {
        CritsAddonsDefaults.install()
        loadConfig()

        register<TickEvent.Start> {
            syncSelectedConfigFileIfChanged()
            if (playbackJob?.isActive == true) {
                if (mc.screen != null) releaseMovement()
                return@register
            }

            tryAutoStartPlayback()
        }

        register<KeyboardEvent.KeyPressed> {
            if (event.action != GLFW.GLFW_PRESS) return@register
            if (mc.screen != null) return@register
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            if (!playbackKeybind.isPressed()) return@register

            event.isCanceled = true
            if (playbackJob?.isActive == true) {
                stopPlayback("&eSecret Route playback stopped.")
                return@register
            }
            beginPlayback()
        }

        register<RenderWorldEvent> {
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register
            val ctx = currentRoomContext() ?: return@register
            val route = routes[ctx.room.name] ?: return@register
            renderRoute(event, ctx, route)
        }

        register<DungeonEvent.SecretEvent> {
            val roomName = ScanUtils.currentRoom?.name ?: return@register
            if (!routes.containsKey(roomName)) return@register
            markRoomSecretProgress(roomName)
        }

        register<PlayerInteractEvent.RIGHT_CLICK.AIR>(EventPriority.HIGHEST) {
            if (LocationUtils.inDungeon && !LocationUtils.inBoss) {
                PersistentSecretHeads.findGhostHeadTargetForRoute()?.let { markCurrentRoomSecretClick(it) }
            }

            if (isLinkRecordingCurrentRoom()) {
                if (recordLinkStep()) return@register
                event.isCanceled = true
                blockMessage("Only one etherwarp is allowed while /nsr start or /nsr end recording is active.")
                return@register
            }

            if (!isRecordingCurrentRoom()) return@register
            if (recordGhostHeadClick()) return@register
            if (isTntItemId(event.item?.skyblockId) && recordTntStepFromHitResult()) return@register
            if (recordEtherwarpStep()) return@register

            event.isCanceled = true
            blockMessage("Only etherwarp is allowed while looking at air during route recording.")
        }

        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK>(EventPriority.HIGHEST) {
            if (LocationUtils.inDungeon && !LocationUtils.inBoss) {
                markCurrentRoomSecretClick(event.pos)
            }

            if (isLinkRecordingCurrentRoom()) {
                if (recordLinkStep()) return@register
                event.isCanceled = true
                blockMessage("Only one etherwarp is allowed while /nsr start or /nsr end recording is active.")
                return@register
            }

            if (!isRecordingCurrentRoom()) return@register
            val itemId = event.item?.skyblockId

            when {
                recordGhostHeadClick() -> return@register
                isRouteRightClickTarget(event.pos) -> return@register
                isTntItemId(itemId) -> {
                    recordTntStepFromHitResult()
                    return@register
                }

                isWitherBlade(itemId) -> {
                    appendStep(RouteStep(RouteStepType.USE_HYPERION, currentRelativePos(event.pos)))
                    return@register
                }

                recordEtherwarpStep() -> return@register
                else -> {
                    event.isCanceled = true
                    blockMessage("Only etherwarp, TNT, Hyperion, and secret clicks are allowed while recording.")
                }
            }
        }

        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY>(EventPriority.HIGHEST) {
            if (isLinkRecordingCurrentRoom()) {
                event.isCanceled = true
                blockMessage("Only one etherwarp is allowed while /nsr start or /nsr end recording is active.")
                return@register
            }
            if (!isRecordingCurrentRoom()) return@register
            event.isCanceled = true
            blockMessage("Entity interactions are blocked while route recording is active.")
        }

        register<PlayerInteractEvent.LEFT_CLICK.AIR>(EventPriority.HIGHEST) {
            if (isLinkRecordingCurrentRoom()) {
                event.isCanceled = true
                blockMessage("Only one etherwarp is allowed while /nsr start or /nsr end recording is active.")
                return@register
            }
            if (!isRecordingCurrentRoom()) return@register
            event.isCanceled = true
            blockMessage("Only Dungeoneering Pickaxe breaks are allowed while recording.")
        }

        register<PlayerInteractEvent.LEFT_CLICK.ENTITY>(EventPriority.HIGHEST) {
            if (isLinkRecordingCurrentRoom()) {
                event.isCanceled = true
                blockMessage("Only one etherwarp is allowed while /nsr start or /nsr end recording is active.")
                return@register
            }
            if (!isRecordingCurrentRoom()) return@register
            event.isCanceled = true
            blockMessage("Entity attacks are blocked while route recording is active.")
        }

        register<PlayerInteractEvent.LEFT_CLICK.BLOCK>(EventPriority.HIGHEST) {
            if (isLinkRecordingCurrentRoom()) {
                event.isCanceled = true
                blockMessage("Only one etherwarp is allowed while /nsr start or /nsr end recording is active.")
                return@register
            }
            if (!isRecordingCurrentRoom()) return@register
            if (event.item?.skyblockId != "DUNGEONBREAKER") {
                event.isCanceled = true
                blockMessage("Only the Dungeoneering Pickaxe can record break steps.")
                return@register
            }

            if (shouldRecordBreak(event.pos)) {
                appendStep(RouteStep(RouteStepType.BREAK_BLOCK, currentRelativePos(event.pos)))
            }
        }

        register<PacketEvent.Sent>(EventPriority.HIGHEST) {
            if (isLinkRecordingCurrentRoom()) return@register
            if (!isRecordingCurrentRoom()) return@register
            val packet = event.packet as? ServerboundUseItemOnPacket ?: return@register
            val held = mc.player?.mainHandItem ?: return@register
            val clicked = packet.hitResult.blockPos
            val direction = packet.hitResult.direction

            when {
                isTntItemId(held.skyblockId) -> recordTntStep(clicked, direction)
                isRouteRightClickTarget(clicked) -> appendStep(
                    RouteStep(
                        type = RouteStepType.RIGHT_CLICK_SECRET,
                        pos = currentRelativePos(clicked),
                        direction = direction
                    )
                )
            }
        }

        register<DungeonEvent.RoomEvent.onExit> {
            val session = recording ?: return@register
            if (session.roomName != event.room.name) return@register
            cancelRecording("Left ${event.room.name}; route recording canceled.")
        }

        register<DungeonEvent.RoomEvent.onExit> {
            val session = startLinkRecording ?: return@register
            if (session.roomName != event.room.name) return@register
            cancelRecording("Left ${event.room.name}; /nsr start recording canceled.")
        }

        register<DungeonEvent.RoomEvent.onExit> {
            val session = endLinkRecording ?: return@register
            if (session.roomName != event.room.name) return@register
            cancelRecording("Left ${event.room.name}; /nsr end recording canceled.")
        }

        register<DungeonEvent.RoomEvent.onExit> {
            if (activePlaybackRoomName != event.room.name) return@register
            stopPlayback("&eLeft ${event.room.name}; Secret Route playback canceled.")
        }

        register<DungeonEvent.BossEnterEvent> {
            clearSecretProgressCache()
        }

        register<WorldChangeEvent> {
            recording = null
            startLinkRecording = null
            endLinkRecording = null
            lastBreakRecord = null
            lastTntRecord = null
            lastAutoStartAt = 0L
            lastAutoStartBlock = null
            resetCenterHoldState()
            recordingPaused = false
            clearSecretProgressCache()
            releaseMovement()
            stopPlayback()
        }
    }

    fun startRecording() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to start /nsr.")
        if (recording != null) return ChatUtils.modMessage("&cSave or cancel the current /nsr recording first.")
        if (startLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr start recording first.")
        if (endLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr end recording first.")
        stopPlayback()

        val startBlock = mc.player?.blockPosition()?.below() ?: return ChatUtils.modMessage("&cCould not resolve your starting block.")
        if (!isCenteredOnBlock(startBlock)) {
            return ChatUtils.modMessage("&cStand in the center of the block before starting &b/nsr&c.")
        }
        recording = RecordingSession(ctx.room.name, toRelative(startBlock, ctx))
        lastBreakRecord = null
        lastTntRecord = null
        ChatUtils.modMessage("&aStarted recording Secret Route for &e${ctx.room.name}&a.")
    }

    fun continueRecording() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to use /nsr continue.")
        if (recording != null) return ChatUtils.modMessage("&cSave or cancel the current /nsr recording first.")
        if (startLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr start recording first.")
        if (endLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr end recording first.")
        stopPlayback()

        val route = routes[ctx.room.name]
            ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c. Record it with &b/nsr&c first.")
        if (route.steps.isEmpty()) {
            return ChatUtils.modMessage("&cThe current route has no steps. Use &b/nsr&c to record from scratch.")
        }

        val existingSteps = route.steps.toMutableList()
        recording = RecordingSession(
            roomName = ctx.room.name,
            startBlock = route.startBlock,
            steps = existingSteps,
            baseStepCount = existingSteps.size
        )
        lastBreakRecord = null
        lastTntRecord = null
        ChatUtils.modMessage(
            "&aContinuing Secret Route for &e${ctx.room.name}&a from step &e${existingSteps.size + 1}&a. Use &b/nsr save&a when done."
        )
    }

    fun startStartPathRecording() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to start /nsr start.")
        if (recording != null) return ChatUtils.modMessage("&cSave or cancel the current /nsr recording first.")
        if (startLinkRecording != null) return ChatUtils.modMessage("&cA /nsr start recording is already active.")
        if (endLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr end recording first.")
        stopPlayback()

        val route = routes[ctx.room.name]
            ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c. Record the main route first.")
        val startBlock = mc.player?.blockPosition()?.below() ?: return ChatUtils.modMessage("&cCould not resolve your starting block.")
        if (!isCenteredOnBlock(startBlock)) {
            return ChatUtils.modMessage("&cStand in the center of the block before starting &b/nsr start&c.")
        }
        val relativeStart = toRelative(startBlock, ctx)

        if (relativeStart == route.startBlock) {
            return ChatUtils.modMessage("&eYou are on the main start block. Use &b/nsr&e to record the main route.")
        }

        startLinkRecording = StartLinkRecordingSession(ctx.room.name, relativeStart)
        ChatUtils.modMessage("&aStarted /nsr start link recording for &e${ctx.room.name}&a. Use exactly one etherwarp to a known start block.")
    }

    fun deleteStartLinkFromCurrentBlock() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to delete a start link.")
        val route = routes[ctx.room.name]
            ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c.")
        val worldBlock = mc.player?.blockPosition()?.below() ?: return ChatUtils.modMessage("&cCould not resolve your starting block.")
        if (!isCenteredOnBlock(worldBlock)) return ChatUtils.modMessage("&cStand in the center of the block before using &b/nsr start delete&c.")

        val relative = toRelative(worldBlock, ctx)
        if (relative == route.startBlock) {
            return ChatUtils.modMessage("&cYou cannot delete the original /nsr start block.")
        }

        val links = route.startLinks.orEmpty()
        val startSources = links.map { it.from }.toSet()
        if (!startSources.contains(relative)) {
            return ChatUtils.modMessage("&cThis block is not a deletable start-link start block.")
        }

        val removedSources = collectDependentStartSources(links, relative)
        if (removedSources.isEmpty()) {
            return ChatUtils.modMessage("&eNo start links were removed.")
        }

        val updatedLinks = links.filterNot { it.from in removedSources }.toMutableList()
        routes[ctx.room.name] = route.copy(startLinks = updatedLinks)
        saveConfig()

        val removedCount = removedSources.size
        ChatUtils.modMessage("&aDeleted &e$removedCount&a start block link${if (removedCount == 1) "" else "s"} for &e${ctx.room.name}&a.")
    }

    fun deleteEndLinkFromCurrentBlock() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to delete an end link.")
        val route = routes[ctx.room.name]
            ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c.")
        val worldBlock = mc.player?.blockPosition()?.below() ?: return ChatUtils.modMessage("&cCould not resolve your current block.")
        if (!isCenteredOnBlock(worldBlock)) return ChatUtils.modMessage("&cStand in the center of the block before using &b/nsr end delete&c.")

        val relative = toRelative(worldBlock, ctx)
        val routeEnd = routeEndBlockRelative(route)
        if (routeEnd != null && relative == routeEnd) {
            return ChatUtils.modMessage("&cYou cannot delete the original route end block.")
        }

        val endBlocks = route.endBlocks.orEmpty().toSet()
        val helperBlocks = route.endHelperBlocks.orEmpty().toSet()
        if (relative !in endBlocks && relative !in helperBlocks) {
            return ChatUtils.modMessage("&cThis block is not a deletable end/end-helper block.")
        }

        val linkedRemoval = collectDependentEndNodes(route.endLinks.orEmpty(), relative)
        val removedNodes = linkedRemoval
            .filterNot { it == routeEnd }
            .toSet()
        if (removedNodes.isEmpty()) {
            return ChatUtils.modMessage("&eNo end blocks were removed.")
        }

        val updatedLinks = route.endLinks.orEmpty()
            .filterNot { it.from in removedNodes || it.to in removedNodes }
            .toMutableList()
        val updatedEnds = route.endBlocks.orEmpty()
            .filterNot { it in removedNodes }
            .toMutableList()
        val updatedHelpers = route.endHelperBlocks.orEmpty()
            .filterNot { it in removedNodes }
            .toMutableList()

        routes[ctx.room.name] = route.copy(
            endLinks = updatedLinks,
            endBlocks = updatedEnds,
            endHelperBlocks = updatedHelpers
        )
        saveConfig()

        val removedEndCount = removedNodes.count { it in endBlocks }
        val removedHelperCount = removedNodes.count { it in helperBlocks }
        val removedTotal = removedNodes.size
        ChatUtils.modMessage(
            "&aDeleted &e$removedTotal&a linked end node${if (removedTotal == 1) "" else "s"} " +
                "(&e$removedEndCount&a end, &e$removedHelperCount&a helper) for &e${ctx.room.name}&a."
        )
    }

    fun saveRecording() {
        if (startLinkRecording != null) {
            return ChatUtils.modMessage("&e/nsr start auto-saves after one valid etherwarp. Use &b/nsr cancel&e to cancel it.")
        }
        if (endLinkRecording != null) {
            return ChatUtils.modMessage("&e/nsr end auto-saves after one valid etherwarp. Use &b/nsr cancel&e to cancel it.")
        }
        val session = recording ?: return ChatUtils.modMessage("&cNo Secret Route recording is active.")
        if (session.steps.isEmpty()) return ChatUtils.modMessage("&cNo steps recorded for ${session.roomName}.")

        val previousRoute = routes[session.roomName]
        val existingStartLinks = previousRoute?.startLinks.orEmpty().toMutableList()
        val existingEndLinks = previousRoute?.endLinks.orEmpty().toMutableList()
        val existingEndBlocks = previousRoute?.endBlocks.orEmpty().toMutableList()
        val existingEndHelpers = previousRoute?.endHelperBlocks.orEmpty().toMutableList()

        routes[session.roomName] = RoomRoute(
            startBlock = session.startBlock,
            steps = session.steps.toMutableList(),
            startLinks = existingStartLinks,
            endLinks = existingEndLinks,
            endBlocks = existingEndBlocks,
            endHelperBlocks = existingEndHelpers
        )

        saveConfig()
        val appendedCount = (session.steps.size - session.baseStepCount).coerceAtLeast(0)
        recording = null
        lastBreakRecord = null
        lastTntRecord = null
        ChatUtils.modMessage(
            "&aSaved Secret Route for &e${session.roomName}&a with &e${routes[session.roomName]?.steps?.size}&a steps" +
                if (session.baseStepCount > 0) " (&e+$appendedCount&a appended)." else "."
        )
    }

    fun markCurrentRoomCompleted() {
        val ctx = currentRoomContext()
            ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to use /nsr complete.")
        val roomName = ctx.room.name

        if (!completedRooms.add(roomName)) {
            return ChatUtils.modMessage("&e$roomName is already marked completed.")
        }

        saveConfig()
        ChatUtils.modMessage("&aMarked &e$roomName&a as completed in Secret Routes.")
    }

    fun getCompletedRoomNames(): Set<String> = completedRooms.toSet()

    fun getTrackedCompletionRoomNames(): Set<String> {
        val names = linkedSetOf<String>()
        names.addAll(routes.keys)
        names.addAll(completedRooms)
        return names
    }

    fun isRoomMarkedCompleted(roomName: String): Boolean = completedRooms.contains(roomName)

    fun addEndBlockFromCurrentBlock() {
        startEndLinkRecording(EndLinkRecordingType.FINAL)
    }

    fun addEndHelperBlockFromCurrentBlock() {
        startEndLinkRecording(EndLinkRecordingType.HELPER)
    }

    private fun startEndLinkRecording(type: EndLinkRecordingType) {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to start /nsr end recording.")
        if (recording != null) return ChatUtils.modMessage("&cSave or cancel the current /nsr recording first.")
        if (startLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr start recording first.")
        if (endLinkRecording != null) return ChatUtils.modMessage("&cA /nsr end recording is already active.")
        stopPlayback()

        val route = routes[ctx.room.name]
            ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c. Record the main route first.")

        val worldBlock = mc.player?.blockPosition()?.below() ?: return ChatUtils.modMessage("&cCould not resolve your block.")
        if (!isCenteredOnBlock(worldBlock)) {
            return ChatUtils.modMessage("&cStand in the center of the block before starting &b/nsr end&c.")
        }
        val relative = toRelative(worldBlock, ctx)
        val knownEnds = knownEndBlocksRelative(route)
        if (!knownEnds.contains(relative)) {
            return ChatUtils.modMessage("&cYou must stand on a known end/end-helper block before linking a new end.")
        }

        endLinkRecording = EndLinkRecordingSession(ctx.room.name, relative, type)
        val label = if (type == EndLinkRecordingType.HELPER) "/nsr end helper" else "/nsr end"
        ChatUtils.modMessage("&aStarted $label link recording for &e${ctx.room.name}&a. Use exactly one etherwarp.")
    }

    fun cancelRecording(message: String? = null) {
        if (recording == null && startLinkRecording == null && endLinkRecording == null) return
        recording = null
        startLinkRecording = null
        endLinkRecording = null
        lastBreakRecord = null
        lastTntRecord = null
        ChatUtils.modMessage("&e${message ?: "Secret Route recording canceled."}")
    }

    fun insertWaitStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first before adding a wait step.")
        appendStep(RouteStep(RouteStepType.WAIT_FOR_SECRET_PROGRESS))
    }

    fun insertBatWaitStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first before adding a bat wait step.")
        appendStep(RouteStep(RouteStepType.WAIT_FOR_BAT_SPAWN))
    }

    fun addEtherwarpStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first (or /nsr continue) before using /nsr add.")
        if (recordEtherwarpStep()) return
        ChatUtils.modMessage("&cCould not record etherwarp. Hold your etherwarp item and face a valid target block.")
    }

    fun addTntStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first (or /nsr continue) before using /nsr add.")
        if (recordTntStepFromHitResult()) return
        ChatUtils.modMessage("&cLook at a block face with TNT in hand to add a TNT step.")
    }

    fun addBreakStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first (or /nsr continue) before using /nsr add.")
        val hit = mc.hitResult as? BlockHitResult ?: return ChatUtils.modMessage("&cLook at a block to add a break step.")
        if (!shouldRecordBreak(hit.blockPos)) return
        appendStep(RouteStep(RouteStepType.BREAK_BLOCK, currentRelativePos(hit.blockPos)))
    }

    fun addHyperionStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first (or /nsr continue) before using /nsr add.")
        val target = (mc.hitResult as? BlockHitResult)?.blockPos
            ?: mc.player?.blockPosition()?.below()
            ?: return ChatUtils.modMessage("&cCould not resolve a target block for Hyperion.")
        appendStep(RouteStep(RouteStepType.USE_HYPERION, currentRelativePos(target)))
    }

    fun addSecretStep() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first (or /nsr continue) before using /nsr add.")
        val hit = mc.hitResult as? BlockHitResult ?: return ChatUtils.modMessage("&cLook at a valid secret block to add a secret step.")
        val pos = hit.blockPos
        if (!isRouteRightClickTarget(pos)) {
            return ChatUtils.modMessage("&cTarget block is not a valid secret interaction block.")
        }
        appendStep(RouteStep(RouteStepType.RIGHT_CLICK_SECRET, currentRelativePos(pos), direction = hit.direction))
    }

    fun deleteCurrentRoomRoute() {
        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to delete its route.")
        val removed = routes.remove(ctx.room.name)
            ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c.")

        saveConfig()
        ChatUtils.modMessage("&aDeleted Secret Route for &e${ctx.room.name}&a with &e${removed.steps.size}&a steps.")
    }

    fun killDuringRecording() {
        if (!isRecordingCurrentRoom()) return ChatUtils.modMessage("&cStart /nsr first before using /nsr kill.")
        if (findItemSlot(*WITHER_BLADE_ITEM_IDS) == null) return ChatUtils.modMessage("&cHyperion is not on your hotbar.")
        val ground = mc.player?.blockPosition()?.below() ?: return ChatUtils.modMessage("&cCould not resolve your ground block.")
        SecretRoutesDebugger.recording { "/nsr kill start ground=${ground.toShortString()}." }

        scope.launch {
            withRecordingPaused {
                if (!useTargetedStep(ground, null, WITHER_BLADE_ITEM_IDS, "Hyperion", yOffset = 0.1)) {
                    ChatUtils.modMessage("&cFailed to use Hyperion for /nsr kill.")
                }
                delay(150)
            }
            SecretRoutesDebugger.recording { "/nsr kill finished and recording resumed." }
            ChatUtils.modMessage("&a/nsr kill used Hyperion and resumed recording.")
        }
    }

    fun getCurrentRoomHelperSnapshot(): RouteHelperSnapshot? {
        val ctx = currentRoomContext() ?: return null
        val route = routes[ctx.room.name] ?: return null

        val ogEndRelative = routeEndBlockRelative(route)
        val ogEndWorld = ogEndRelative?.let { toWorld(it, ctx) }
        val startBlocks = knownStartBlocksRelative(route)
            .asSequence()
            .map { toWorld(it, ctx) }
            .toSet()

        val endNodes = linkedSetOf<BlockPos>()
        route.endHelperBlocks.orEmpty()
            .asSequence()
            .mapTo(endNodes) { toWorld(it, ctx) }
        route.endBlocks.orEmpty()
            .asSequence()
            .mapTo(endNodes) { toWorld(it, ctx) }
        ogEndWorld?.let(endNodes::add)

        return RouteHelperSnapshot(
            roomName = ctx.room.name,
            sameStartAndOgEnd = route.startBlock == ogEndRelative,
            startBlocksWorld = startBlocks,
            endNodesWorld = endNodes,
            ogEndWorld = ogEndWorld
        )
    }

    private fun normalizeRoutesConfigName(raw: String): String {
        val base = raw.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "secretRoutes.json" }
        return if (base.lowercase().endsWith(".json")) base else "$base.json"
    }

    private fun selectedRoutesConfigFile(): File {
        val name = normalizeRoutesConfigName(routesConfigFileName.value)
        return File("config/$MOD_NAME/$name")
    }

    private fun syncSelectedConfigFileIfChanged() {
        val selectedPath = selectedRoutesConfigFile().absolutePath
        val activePath = activeRoutesConfigPath ?: return
        if (activePath == selectedPath) return
        if (recording != null || startLinkRecording != null || endLinkRecording != null || playbackJob?.isActive == true) return
        reloadRoutesConfigFromDisk()
    }

    private fun reloadRoutesConfigFromDisk() {
        if (recording != null || startLinkRecording != null || endLinkRecording != null || playbackJob?.isActive == true) {
            ChatUtils.modMessage("&eStop recording/playback before reloading routes config.")
            return
        }

        val file = selectedRoutesConfigFile()
        val loaded = loadConfig()
        clearSecretProgressCache()

        if (loaded) {
            ChatUtils.modMessage("&aReloaded Secret Routes from &e${file.name}&a (${routes.size} rooms).")
        } else {
            ChatUtils.modMessage("&eNo routes file found at &b${file.path}&e. Loaded empty routes.")
        }
    }

    private fun loadConfig(): Boolean {
        val file = selectedRoutesConfigFile()
        routes.clear()
        completedRooms.clear()
        activeRoutesConfigPath = file.absolutePath

        if (!file.exists()) return false

        var loadedAny = false
        runCatching {
            FileReader(file).use { reader ->
                val root = JsonParser.parseReader(reader)
                if (!root.isJsonObject) return@use

                val rootObject = root.asJsonObject
                if (rootObject.has("routes")) {
                    val routesType = object : TypeToken<MutableMap<String, RoomRoute>>() {}.type
                    val loadedRoutes = JsonUtils.gsonBuilder.fromJson<MutableMap<String, RoomRoute>>(
                        rootObject.get("routes"),
                        routesType
                    ) ?: mutableMapOf()
                    routes.putAll(loadedRoutes)

                    rootObject.getAsJsonArray("completedRooms")
                        ?.forEach { element ->
                            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                                completedRooms.add(element.asString)
                            }
                        }
                } else {
                    // Legacy format: top-level map of room -> route.
                    val routesType = object : TypeToken<MutableMap<String, RoomRoute>>() {}.type
                    val loadedRoutes = JsonUtils.gsonBuilder.fromJson<MutableMap<String, RoomRoute>>(rootObject, routesType)
                        ?: mutableMapOf()
                    routes.putAll(loadedRoutes)
                }
                loadedAny = true
                NoammAddons.logger.info(
                    "${this.javaClass.simpleName} Config loaded from ${file.path}: ${routes.size} rooms, ${completedRooms.size} completed."
                )
            }
        }.onFailure {
            NoammAddons.logger.error("${this.javaClass.simpleName} Failed to load config from ${file.path}!", it)
        }

        return loadedAny
    }

    private fun saveConfig() {
        val file = selectedRoutesConfigFile()
        runCatching {
            file.parentFile?.mkdirs()
            FileWriter(file).use { writer ->
                val payload = linkedMapOf<String, Any>(
                    "routes" to routes,
                    "completedRooms" to completedRooms.toList().sorted()
                )
                JsonUtils.gsonBuilder.toJson(payload, writer)
            }
            activeRoutesConfigPath = file.absolutePath
            NoammAddons.logger.info("${this.javaClass.simpleName} Config saved to ${file.path}.")
        }.onFailure {
            NoammAddons.logger.error("${this.javaClass.simpleName} Failed to save config to ${file.path}!", it)
        }
    }

    private fun beginPlayback() {
        if (recording != null) return ChatUtils.modMessage("&cSave or cancel the current /nsr recording before playback.")
        if (startLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr start recording before playback.")
        if (endLinkRecording != null) return ChatUtils.modMessage("&cFinish or cancel the current /nsr end recording before playback.")
        if (playbackJob?.isActive == true) return ChatUtils.modMessage("&eSecret Route playback is already running.")

        val ctx = currentRoomContext() ?: return ChatUtils.modMessage("&cYou must be standing in a scanned dungeon room to play a route.")
        val route = routes[ctx.room.name] ?: return ChatUtils.modMessage("&cNo Secret Route saved for &e${ctx.room.name}&c.")
        val playerStart = mc.player?.blockPosition()?.below()
            ?: return ChatUtils.modMessage("&cCould not resolve your starting block.")
        SecretRoutesDebugger.plan {
            "Begin playback room=${ctx.room.name}, playerStart=${playerStart.toShortString()}, routeSteps=${route.steps.size}, startLinks=${route.startLinks.orEmpty().size}, endBlocks=${route.endBlocks.orEmpty().size}, endHelpers=${route.endHelperBlocks.orEmpty().size}"
        }

        val plan = resolvePlaybackPlan(ctx, route, playerStart)
        if (plan == null) {
            val starts = collectWorldStarts(ctx, route)
            SecretRoutesDebugger.plan {
                "No playback plan matched for playerStart=${playerStart.toShortString()}, validStarts=${starts.joinToString { it.toShortString() }}"
            }
            val baseMessage = "&eStand on a valid Secret Route start block to begin playback."
            if (starts.isEmpty()) return ChatUtils.modMessage(baseMessage)
            return ChatUtils.modMessage("$baseMessage &7Starts: &b${starts.joinToString { it.toShortString() }}")
        }

        if (plan.resumedFromRouteStep) {
            ChatUtils.modMessage("&aStarting Secret Route from &e${plan.resumeLabel ?: "matched step"}&a.")
        } else if (plan.usedStartLink) {
            ChatUtils.modMessage("&aFollowing start links to main start for &e${ctx.room.name}&a.")
        } else if (plan.usedAltStart) {
            ChatUtils.modMessage("&aUsing alternate start path for &e${ctx.room.name}&a.")
        }
        SecretRoutesDebugger.plan {
            "Playback plan matched room=${ctx.room.name}, usedAltStart=${plan.usedAltStart}, resumedFromRouteStep=${plan.resumedFromRouteStep}, resumeLabel=${plan.resumeLabel}, totalSteps=${plan.steps.size}"
        }

        startPlayback(ctx, plan)
    }

    private fun startPlayback(ctx: RoomContext, plan: PlaybackPlan) {
        SecretRoutesDebugger.step {
            "Starting playback coroutine room=${ctx.room.name}, steps=${plan.steps.size}, primaryEnd=${plan.primaryEndBlock?.toShortString() ?: "none"}"
        }
        activePlaybackRoomName = ctx.room.name
        playbackJob = scope.launch {
            runCatching { playRoute(ctx, plan.steps, plan.primaryEndBlock) }
                .onFailure {
                    if (it !is CancellationException) {
                        ChatUtils.modMessage("&cSecret Route playback failed: ${it.message ?: it::class.simpleName}")
                    }
                }
            playbackJob = null
            activePlaybackRoomName = null
        }
    }

    private fun tryAutoStartPlayback() {
        if (!autoStart.value) {
            resetCenterHoldState()
            return
        }
        if (recording != null || endLinkRecording != null) {
            resetCenterHoldState()
            return
        }
        if (!LocationUtils.inDungeon || LocationUtils.inBoss) {
            resetCenterHoldState()
            return
        }
        if (mc.screen != null) {
            resetCenterHoldState()
            return
        }

        val ctx = currentRoomContext() ?: return
        val route = routes[ctx.room.name] ?: return
        val playerStart = mc.player?.blockPosition()?.below() ?: return
        val plan = resolvePlaybackPlan(
            ctx,
            route,
            playerStart,
            allowRouteStepResume = !startBlocksOnly.value
        ) ?: run {
            resetCenterHoldState()
            return
        }
        if (centerOnly.value) {
            if (!isWithinCenterRadius(playerStart)) {
                resetCenterHoldState()
                SecretRoutesDebugger.autoStart {
                    "Skipped auto-start: outside center radius on ${playerStart.toShortString()}."
                }
                return
            }

            val now = System.currentTimeMillis()
            if (centerHoldBlock != playerStart) {
                centerHoldBlock = playerStart.immutable()
                centerHoldStartedAt = now
            }

            val requiredMs = (centerHoldSeconds.value * 1_000.0).toLong()
            val heldMs = now - centerHoldStartedAt
            if (heldMs < requiredMs) {
                SecretRoutesDebugger.autoStart {
                    "Waiting center hold: held=${heldMs}ms required=${requiredMs}ms block=${playerStart.toShortString()}."
                }
                return
            }
        } else {
            resetCenterHoldState()
        }

        val now = System.currentTimeMillis()
        if (lastAutoStartBlock == playerStart && now - lastAutoStartAt < 1_250L) {
            SecretRoutesDebugger.autoStart {
                "Throttled auto-start room=${ctx.room.name}, block=${playerStart.toShortString()}, elapsed=${now - lastAutoStartAt}ms"
            }
            return
        }
        lastAutoStartBlock = playerStart.immutable()
        lastAutoStartAt = now
        resetCenterHoldState()

        if (plan.resumedFromRouteStep) {
            ChatUtils.modMessage("&aAuto-started Secret Route from &e${plan.resumeLabel ?: "matched step"}&a.")
        } else if (plan.usedStartLink) {
            ChatUtils.modMessage("&aAuto-started Secret Route from linked start for &e${ctx.room.name}&a.")
        } else if (plan.usedAltStart) {
            ChatUtils.modMessage("&aAuto-started alternate Secret Route for &e${ctx.room.name}&a.")
        } else {
            ChatUtils.modMessage("&aAuto-started Secret Route for &e${ctx.room.name}&a.")
        }
        SecretRoutesDebugger.autoStart {
            "Auto-starting room=${ctx.room.name}, usedAltStart=${plan.usedAltStart}, resumedFromRouteStep=${plan.resumedFromRouteStep}, resumeLabel=${plan.resumeLabel}, start=${playerStart.toShortString()}, steps=${plan.steps.size}"
        }
        startPlayback(ctx, plan)
    }

    private fun resolvePlaybackPlan(
        ctx: RoomContext,
        route: RoomRoute,
        playerStart: BlockPos,
        allowRouteStepResume: Boolean = true
    ): PlaybackPlan? {
        val mainStartBlock = toWorld(route.startBlock, ctx)
        val primaryEnd = primaryEndBlockRelative(route)
        val endPostRoll = buildEndPostRollSteps(route, ctx)
        SecretRoutesDebugger.plan {
            "Resolving plan room=${ctx.room.name}, playerStart=${playerStart.toShortString()}, mainStart=${mainStartBlock.toShortString()}"
        }
        if (playerStart == mainStartBlock) {
            SecretRoutesDebugger.plan { "Matched main start block for room=${ctx.room.name}." }
            return PlaybackPlan(
                steps = route.steps + endPostRoll,
                usedAltStart = false,
                primaryEndBlock = primaryEnd
            )
        }

        val relativeStart = toRelative(playerStart, ctx)
        val knownStarts = knownStartBlocksRelative(route)
        if (relativeStart != route.startBlock && knownStarts.contains(relativeStart)) {
            val chain = resolveStartLinkChain(route, relativeStart)
            if (!chain.success) {
                val error = chain.error ?: "Unknown start-link error."
                ChatUtils.modMessage("&cFailed to follow start links: $error")
                SecretRoutesDebugger.failure {
                    "Start-link chain failed room=${ctx.room.name}, start=${relativeStart.toShortString()}, error=$error"
                }
                return null
            }

            val preRoll = chain.links.map { link ->
                val fallbackRotation = startLinkFallbackRotation(ctx, link)
                val yaw = link.yaw ?: fallbackRotation?.yaw
                val pitch = link.pitch ?: fallbackRotation?.pitch
                RouteStep(
                    type = RouteStepType.ETHERWARP,
                    pos = link.to,
                    direction = link.direction,
                    yaw = yaw,
                    pitch = pitch
                )
            }
            SecretRoutesDebugger.plan {
                "Resolved start-link chain room=${ctx.room.name}, start=${relativeStart.toShortString()}, hops=${chain.links.size}"
            }
            return PlaybackPlan(
                steps = preRoll + route.steps + endPostRoll,
                usedAltStart = true,
                usedStartLink = true,
                primaryEndBlock = primaryEnd
            )
        }

        if (allowRouteStepResume) {
            val anywherePlan = resolvePlaybackPlanFromAnywhere(ctx, route, playerStart)
            if (anywherePlan != null) return anywherePlan
        } else {
            SecretRoutesDebugger.plan { "Route-step resume disabled for this plan resolution." }
        }

        SecretRoutesDebugger.plan {
            val starts = collectWorldStarts(ctx, route).joinToString { it.toShortString() }
            "No alternate start matched for ${playerStart.toShortString()}. Known starts=$starts"
        }
        return null
    }

    private fun collectWorldStarts(ctx: RoomContext, route: RoomRoute): List<BlockPos> {
        val worldStarts = mutableListOf<BlockPos>()
        fun addIfNew(block: BlockPos) {
            if (worldStarts.none { it == block }) worldStarts.add(block)
        }

        knownStartBlocksRelative(route).forEach { addIfNew(toWorld(it, ctx)) }
        return worldStarts
    }

    private fun resolvePlaybackPlanFromAnywhere(ctx: RoomContext, route: RoomRoute, playerStart: BlockPos): PlaybackPlan? {
        if (!startRouteFromAnywhere.value) return null
        if (!isCenteredOnBlock(playerStart)) {
            SecretRoutesDebugger.plan {
                "Route-step resume skipped: player is not centered on ${playerStart.toShortString()}."
            }
            return null
        }
        val primaryEnd = primaryEndBlockRelative(route)
        val endPostRoll = buildEndPostRollSteps(route, ctx)

        val sequences = listOf(route.steps to false)

        var bestPlan: PlaybackPlan? = null
        var bestIndex = Int.MAX_VALUE

        sequences.forEach { (steps, usedAltStart) ->
            for (index in steps.indices) {
                val anchor = steps[index].startAnchorRelativePos() ?: continue
                if (toWorld(anchor, ctx) != playerStart) continue
                val resumeIndex = resumeStartIndexForMatchedStep(steps, index)
                if (resumeIndex >= steps.size) {
                    SecretRoutesDebugger.plan {
                        "Route-step resume matched ${buildResumeLabel(steps, index)} but no remaining steps after resume index=$resumeIndex."
                    }
                    break
                }

                if (index < bestIndex) {
                    bestIndex = index
                    bestPlan = PlaybackPlan(
                        steps = steps.drop(resumeIndex) + endPostRoll,
                        usedAltStart = usedAltStart,
                        resumedFromRouteStep = true,
                        resumeLabel = buildResumeLabel(steps, index),
                        primaryEndBlock = primaryEnd
                    )
                }
                break
            }
        }

        SecretRoutesDebugger.plan {
            if (bestPlan == null) {
                "Route-step resume found no matching step at ${playerStart.toShortString()}."
            } else {
                "Route-step resume matched ${bestPlan.resumeLabel} at ${playerStart.toShortString()}, usedAltStart=${bestPlan.usedAltStart}, remainingSteps=${bestPlan.steps.size}."
            }
        }

        return bestPlan
    }

    private suspend fun playRoute(ctx: RoomContext, steps: List<RouteStep>, primaryEndBlock: BlockPos?) {
        for ((index, step) in steps.withIndex()) {
            SecretRoutesDebugger.step {
                "Step ${index + 1}/${steps.size}: type=${step.type}, relPos=${step.pos?.toShortString()}, face=${step.direction}, yaw=${step.yaw}, pitch=${step.pitch}"
            }
            if (!ensureSurvival()) return
            waitForMana(step)

            when (step.type) {
                RouteStepType.ETHERWARP -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!etherwarpTo(target, false, step.rotation(ctx), step.direction)) return
                }

                RouteStepType.PLACE_TNT -> {
                    val support = step.pos?.let { toWorld(it, ctx) } ?: continue
                    val face = step.direction ?: Direction.UP
                    if (!useTntStep(support, face, step.rotation(ctx))) return
                }

                RouteStepType.BREAK_BLOCK -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!attackBlock(target, "DUNGEONBREAKER", "Dungeoneering Pickaxe", step.rotation(ctx))) return
                }

                RouteStepType.USE_HYPERION -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!useTargetedStep(target, null, WITHER_BLADE_ITEM_IDS, "Hyperion", 0.1, step.rotation(ctx))) return
                }

                RouteStepType.RIGHT_CLICK_SECRET -> {
                    val target = step.pos?.let { toWorld(it, ctx) } ?: continue
                    if (!useTargetedStep(target, step.direction, arrayOf("DUNGEONBREAKER"), "Dungeoneering Pickaxe", rotation = step.rotation(ctx))) return
                    markRoomSecretProgress(ctx.room.name)
                }

                RouteStepType.WAIT_FOR_SECRET_PROGRESS -> {
                    if (waitForSecretProgress(ctx.room)) {
                        markRoomSecretProgress(ctx.room.name)
                    }
                }
                RouteStepType.WAIT_FOR_BAT_SPAWN -> waitForBatSpawn()
            }
            SecretRoutesDebugger.step { "Step ${index + 1}/${steps.size} completed." }
        }
        val endMessage = primaryEndBlock
            ?.let { "&7(End: &b${toWorld(it, ctx).toShortString()}&7)" }
            .orEmpty()
        ChatUtils.modMessage("&aFinished Secret Route for &e${ctx.room.name}&a. $endMessage")
        releaseMovement()
    }

    private suspend fun ensureSurvival(): Boolean {
        if (!autoHyperionOnLowEhp.value) return true
        val currentPercent = currentEhpPercent() ?: return true
        val missingPercent = (1.0 - currentPercent).coerceAtLeast(0.0)
        if (missingPercent < lowEhpMissingThreshold.value / 100.0) return true
        SecretRoutesDebugger.step {
            "Low EHP safety triggered: current=${(currentPercent * 100.0).toInt()}%, missing=${(missingPercent * 100.0).toInt()}%, threshold=${lowEhpMissingThreshold.value}%."
        }

        val floor = mc.player?.blockPosition()?.below() ?: return true
        if (findItemSlot(*WITHER_BLADE_ITEM_IDS) == null) {
            ChatUtils.modMessage("&cLow EHP detected, but Hyperion is not on your hotbar.")
            return true
        }

        waitForMana(hyperionManaCost.value, "low EHP Hyperion")
        ChatUtils.modMessage("&eLow EHP detected. Using Hyperion before continuing the route.")
        return useTargetedStep(floor, null, WITHER_BLADE_ITEM_IDS, "Hyperion", 0.1)
    }

    private suspend fun waitForMana(step: RouteStep) {
        val requiredMana = requiredManaFor(step) ?: return
        waitForMana(requiredMana, step.type.name.lowercase().replace('_', ' '))
    }

    private suspend fun waitForMana(requiredMana: Int, label: String) {
        var announcedWait = false

        while (playbackJob?.isActive == true) {
            if (availableMana() >= requiredMana) return

            if (!announcedWait) {
                ChatUtils.modMessage("&eWaiting for mana: need &b$requiredMana&e for $label.")
                SecretRoutesDebugger.mana {
                    "Waiting for mana label=$label, required=$requiredMana, current=${availableMana()}."
                }
                announcedWait = true
            }

            delay(MANA_CHECK_INTERVAL_MS)
        }
    }

    private suspend fun etherwarpTo(
        target: BlockPos,
        isStartStep: Boolean,
        rotation: MathUtils.Rotation? = null,
        face: Direction? = null
    ): Boolean {
        SecretRoutesDebugger.etherwarp {
            "Etherwarp start target=${target.toShortString()}, isStartStep=$isStartStep, face=$face, rotation=$rotation"
        }
        PlayerUtils.findHotbarSlot { EtherwarpHelper.getEtherwarpDistance(it) != null }
            ?.let(PlayerUtils::swapToSlot)
            ?: return abortPlayback("&cNo etherwarp item found in your hotbar.")

        PlayerUtils.toggleSneak(true)
        delay(INTERACT_DELAY_MS)
        if (!aimForStep(target, rotation)) {
            PlayerUtils.toggleSneak(false)
            val prefix = if (isStartStep) "start block" else "etherwarp target"
            return abortPlayback("&cCould not get line of sight to the $prefix at &e${target.toShortString()}&c.")
        }
        delay(50)
        PlayerUtils.rightClick()

        delay(50)
        PlayerUtils.toggleSneak(false)

        val landed = waitForStandingOn(target)
        if (!landed) {
            SecretRoutesDebugger.etherwarp {
                "Etherwarp landing timeout target=${target.toShortString()}, current=${mc.player?.blockPosition()?.below()?.toShortString()}."
            }
            val prefix = if (isStartStep) "start block" else "etherwarp target"
            return abortPlayback("&cFailed to land on the $prefix at &e${target.toShortString()}&c.")
        }

        SecretRoutesDebugger.etherwarp { "Etherwarp landed target=${target.toShortString()}." }
        if (isStartStep) ChatUtils.modMessage("&aSecret Route start block reached.")
        return true
    }

    private suspend fun useTargetedStep(
        block: BlockPos,
        face: Direction?,
        itemIds: Array<String>?,
        itemName: String,
        yOffset: Double = 0.5,
        rotation: MathUtils.Rotation? = null
    ): Boolean {
        if (itemIds != null) {
            val slot = findItemSlot(*itemIds)
                ?: return abortPlayback("&c$itemName is not on your hotbar.")
            PlayerUtils.swapToSlot(slot)
            delay(INTERACT_DELAY_MS)
            SecretRoutesDebugger.step { "Equipped $itemName slot=$slot for target=${block.toShortString()}." }
        }

        val targetVec = when {
            face != null -> BlockAimUtils.blockFaceCenter(block, face)
            else -> BlockAimUtils.blockCenter(block, yOffset)
        }

        if (!aimForStep(block, rotation, targetVec)) {
            return abortPlayback("&cCould not aim at ${itemName.lowercase()} target &e${block.toShortString()}&c.")
        }

        delay(INTERACT_DELAY_MS)
        PlayerUtils.rightClick()
        delay(150)
        return true
    }

    private suspend fun useTntStep(block: BlockPos, face: Direction, rotation: MathUtils.Rotation?): Boolean {
        val slot = findTntSlot() ?: return abortPlayback("&cNo Superboom/Infinity Boom TNT is on your hotbar.")
        PlayerUtils.swapToSlot(slot)
        delay(INTERACT_DELAY_MS)
        return useTargetedStep(block, face, null, "Superboom TNT", rotation = rotation)
    }

    private suspend fun attackBlock(block: BlockPos, itemId: String, itemName: String, rotation: MathUtils.Rotation? = null): Boolean {
        val slot = findItemSlot(itemId) ?: return abortPlayback("&c$itemName is not on your hotbar.")

        PlayerUtils.swapToSlot(slot)
        delay(INTERACT_DELAY_MS)
        SecretRoutesDebugger.step { "Equipped $itemName slot=$slot for break target=${block.toShortString()}." }

        if (!aimForStep(block, rotation)) {
            return abortPlayback("&cCould not aim at block break target &e${block.toShortString()}&c.")
        }

        PlayerUtils.leftClick()
        delay(BREAK_STEP_DELAY_MS)
        return true
    }

    private suspend fun waitForSecretProgress(room: UniqueRoom): Boolean {
        val initial = currentRoomSecretCount(room)
        val start = System.currentTimeMillis()
        val timeoutMs = (waitStepTimeoutSeconds.value * 1_000.0).toLong()
        SecretRoutesDebugger.waitStep {
            "Waiting for secret progress room=${room.name}, initial=$initial, timeoutMs=$timeoutMs."
        }

        while (System.currentTimeMillis() - start < timeoutMs) {
            val current = currentRoomSecretCount(room)
            if (current > initial) {
                SecretRoutesDebugger.waitStep {
                    "Secret progress detected room=${room.name}, current=$current, elapsed=${System.currentTimeMillis() - start}ms."
                }
                return true
            }
            delay(50)
        }

        SecretRoutesDebugger.waitStep {
            "Secret progress timed out room=${room.name}, timeoutMs=$timeoutMs."
        }
        ChatUtils.modMessage("&eSkipped wait step after ${formatSeconds(waitStepTimeoutSeconds.value)}s without secret progress.")
        return false
    }

    private suspend fun waitForBatSpawn() {
        val start = System.currentTimeMillis()
        val timeoutMs = (batWaitTimeoutSeconds.value * 1_000.0).toLong()
        SecretRoutesDebugger.waitStep {
            "Waiting for bat spawn timeoutMs=$timeoutMs."
        }
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (PersistentSecretHeads.hasSpawnedBatInCurrentRoom()) {
                SecretRoutesDebugger.waitStep {
                    "Bat spawn detected after ${System.currentTimeMillis() - start}ms."
                }
                return
            }
            delay(50)
        }
        SecretRoutesDebugger.waitStep { "Bat wait timed out after $timeoutMs ms." }
        ChatUtils.modMessage("&eSkipped bat wait step after ${formatSeconds(batWaitTimeoutSeconds.value)}s without a bat spawning.")
    }

    private suspend fun aimAtTarget(block: BlockPos, vec: Vec3 = BlockAimUtils.blockCenter(block)): Boolean {
        BlockAimUtils.aimAt(vec, rotationTimeMs.value.toLong())
        delay(INTERACT_DELAY_MS)

        val player = mc.player ?: return false
        val range = maxOf(6.0, player.position().distanceTo(vec) + 2.0)
        return MathUtils.raytrace(player, range) == block
    }

    private suspend fun aimForStep(block: BlockPos, rotation: MathUtils.Rotation?, fallbackVec: Vec3 = BlockAimUtils.blockCenter(block)): Boolean {
        if (rotation == null) return aimAtTarget(block, fallbackVec)

        PlayerUtils.rotateSmoothly(rotation, rotationTimeMs.value.toLong())
        delay(INTERACT_DELAY_MS)
        return true
    }

    private suspend fun waitForStandingOn(block: BlockPos): Boolean {
        val start = System.currentTimeMillis()
        SecretRoutesDebugger.etherwarp {
            "Waiting to stand on ${block.toShortString()} timeout=${WARP_SETTLE_TIMEOUT_MS}ms."
        }
        while (System.currentTimeMillis() - start < WARP_SETTLE_TIMEOUT_MS) {
            if (mc.player?.blockPosition()?.below() == block) {
                SecretRoutesDebugger.etherwarp {
                    "Standing on ${block.toShortString()} after ${System.currentTimeMillis() - start}ms."
                }
                return true
            }
            delay(50)
        }
        SecretRoutesDebugger.etherwarp {
            "Never stood on ${block.toShortString()} within ${WARP_SETTLE_TIMEOUT_MS}ms."
        }
        return false
    }

    private fun isStandingOn(block: BlockPos): Boolean {
        return mc.player?.blockPosition()?.below() == block
    }

    private fun isCenteredOnBlock(block: BlockPos): Boolean {
        val player = mc.player ?: return false
        val dx = abs(player.x - (block.x + 0.5))
        val dz = abs(player.z - (block.z + 0.5))
        return dx <= AUTO_START_CENTER_TOLERANCE && dz <= AUTO_START_CENTER_TOLERANCE
    }

    private fun isWithinCenterRadius(block: BlockPos): Boolean {
        val player = mc.player ?: return false
        val tolerance = (centerRadius.value.coerceIn(0.0, 1.0) * 0.5)
        val dx = abs(player.x - (block.x + 0.5))
        val dz = abs(player.z - (block.z + 0.5))
        return dx <= tolerance && dz <= tolerance
    }

    private fun resetCenterHoldState() {
        centerHoldBlock = null
        centerHoldStartedAt = 0L
    }

    private fun formatSeconds(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
    }

    private fun stopPlayback(message: String? = null) {
        SecretRoutesDebugger.step { "Stopping playback room=$activePlaybackRoomName, reason=${message ?: "none"}." }
        playbackJob?.cancel()
        playbackJob = null
        activePlaybackRoomName = null
        releaseMovement()
        if (message != null) ChatUtils.modMessage(message)
    }

    private fun findItemSlot(vararg itemIds: String): Int? {
        return PlayerUtils.findHotbarSlot { stack -> stack.skyblockId.equalsOneOf(*itemIds) }
    }

    private fun findTntSlot(): Int? {
        return PlayerUtils.findHotbarSlot { stack -> isTntItemId(stack.skyblockId) }
    }

    private fun currentRoomSecretCount(room: UniqueRoom): Int {
        return ActionBarParser.secrets ?: room.foundSecrets
    }

    private fun availableMana(): Int {
        return ActionBarParser.currentMana + ActionBarParser.overflowMana
    }

    private fun currentEhpPercent(): Double? {
        val maxHealth = ActionBarParser.maxHealth.takeIf { it > 0 } ?: return null
        val defenseMultiplier = 1 + (ActionBarParser.currentDefense / 100.0)
        val maxEhp = maxHealth * defenseMultiplier
        if (maxEhp <= 0.0) return null
        return ActionBarParser.effectiveHP / maxEhp
    }

    private fun requiredManaFor(step: RouteStep): Int? {
        return when (step.type) {
            RouteStepType.ETHERWARP -> etherwarpManaCost.value
            RouteStepType.USE_HYPERION -> hyperionManaCost.value
            else -> null
        }
    }

    private fun isRecordingCurrentRoom(): Boolean {
        if (recordingPaused) return false
        val session = recording ?: return false
        val roomName = ScanUtils.currentRoom?.name ?: return false
        return roomName == session.roomName
    }

    private fun isStartLinkRecordingCurrentRoom(): Boolean {
        val session = startLinkRecording ?: return false
        val roomName = ScanUtils.currentRoom?.name ?: return false
        return roomName == session.roomName
    }

    private fun isEndLinkRecordingCurrentRoom(): Boolean {
        val session = endLinkRecording ?: return false
        val roomName = ScanUtils.currentRoom?.name ?: return false
        return roomName == session.roomName
    }

    private fun isLinkRecordingCurrentRoom(): Boolean {
        return isStartLinkRecordingCurrentRoom() || isEndLinkRecordingCurrentRoom()
    }

    private fun recordLinkStep(): Boolean {
        return recordStartLink() || recordEndLink()
    }

    private suspend fun withRecordingPaused(block: suspend () -> Unit) {
        SecretRoutesDebugger.recording { "Recording paused." }
        recordingPaused = true
        try {
            block()
        } finally {
            recordingPaused = false
            SecretRoutesDebugger.recording { "Recording resumed." }
        }
    }

    private fun recordEtherwarpStep(): Boolean {
        val player = mc.player ?: return false
        val distance = EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) ?: return false
        val etherPos = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, distance)
        val target = etherPos.pos ?: return false
        if (!etherPos.succeeded) return false

        val direction = (mc.hitResult as? BlockHitResult)
            ?.takeIf { it.blockPos == target }
            ?.direction

        SecretRoutesDebugger.recording {
            "Recorded etherwarp step target=${target.toShortString()}, direction=$direction, distance=$distance."
        }
        appendStep(RouteStep(RouteStepType.ETHERWARP, currentRelativePos(target), direction = direction))
        return true
    }

    private fun recordStartLink(): Boolean {
        val session = startLinkRecording ?: return false
        val player = mc.player ?: return false
        val distance = EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) ?: return false
        val etherPos = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, distance)
        val target = etherPos.pos ?: return false
        if (!etherPos.succeeded) return false
        val direction = (mc.hitResult as? BlockHitResult)
            ?.takeIf { it.blockPos == target }
            ?.direction
        val relativeRotation = currentRelativeRotation()

        val ctx = currentRoomContext() ?: return false
        if (ctx.room.name != session.roomName) return false

        val route = routes[session.roomName]
            ?: run {
                startLinkRecording = null
                ChatUtils.modMessage("&cNo Secret Route saved for &e${session.roomName}&c.")
                return true
            }

        val targetRelative = toRelative(target, ctx)
        val source = session.sourceStart
        val knownStarts = knownStartBlocksRelative(route)

        if (!knownStarts.contains(targetRelative)) {
            startLinkRecording = null
            ChatUtils.modMessage("&c/nsr start failed: target must be an existing start block.")
            return true
        }

        if (targetRelative == source) {
            startLinkRecording = null
            ChatUtils.modMessage("&c/nsr start failed: source and target start blocks are the same.")
            return true
        }

        val updatedLinks = route.startLinks.orEmpty()
            .filterNot { it.from == source }
            .toMutableList()
        updatedLinks.add(
            StartLink(
                from = source,
                to = targetRelative,
                direction = direction,
                yaw = relativeRotation?.yaw,
                pitch = relativeRotation?.pitch
            )
        )
        val updatedRoute = route.copy(startLinks = updatedLinks)

        val chainCheck = resolveStartLinkChain(updatedRoute, source)
        if (!chainCheck.success) {
            startLinkRecording = null
            ChatUtils.modMessage("&c/nsr start failed: ${chainCheck.error ?: "invalid start-link chain"}.")
            return true
        }

        routes[session.roomName] = updatedRoute
        saveConfig()
        startLinkRecording = null
        ChatUtils.modMessage(
            "&aSaved start link for &e${session.roomName}&a: &b${source.toShortString()} &7-> &b${targetRelative.toShortString()}&a."
        )
        return true
    }

    private fun recordEndLink(): Boolean {
        val session = endLinkRecording ?: return false
        val player = mc.player ?: return false
        val distance = EtherwarpHelper.getEtherwarpDistance(player.mainHandItem) ?: return false
        val etherPos = EtherwarpHelper.getEtherPos(player.position(), player.lookAngle, distance)
        val target = etherPos.pos ?: return false
        if (!etherPos.succeeded) return false
        val direction = (mc.hitResult as? BlockHitResult)
            ?.takeIf { it.blockPos == target }
            ?.direction
        val relativeRotation = currentRelativeRotation()

        val ctx = currentRoomContext() ?: return false
        if (ctx.room.name != session.roomName) return false

        val route = routes[session.roomName]
            ?: run {
                endLinkRecording = null
                ChatUtils.modMessage("&cNo Secret Route saved for &e${session.roomName}&c.")
                return true
            }

        val targetRelative = toRelative(target, ctx)
        val source = session.sourceEnd
        if (targetRelative == source) {
            endLinkRecording = null
            ChatUtils.modMessage("&c/nsr end failed: source and target end blocks are the same.")
            return true
        }

        val updatedEndLinks = route.endLinks.orEmpty()
            .filterNot { it.from == source && it.to == targetRelative }
            .toMutableList()
        updatedEndLinks.add(
            EndLink(
                from = source,
                to = targetRelative,
                direction = direction,
                yaw = relativeRotation?.yaw,
                pitch = relativeRotation?.pitch
            )
        )

        val updatedHelpers = route.endHelperBlocks.orEmpty().toMutableList()
        val updatedEnds = route.endBlocks.orEmpty().toMutableList()

        when (session.type) {
            EndLinkRecordingType.HELPER -> {
                val isFinalEnd = updatedEnds.contains(targetRelative)
                if (!isFinalEnd && !updatedHelpers.contains(targetRelative)) {
                    updatedHelpers.add(targetRelative)
                }
            }
            EndLinkRecordingType.FINAL -> {
                updatedHelpers.remove(targetRelative)
                if (!updatedEnds.contains(targetRelative)) updatedEnds.add(targetRelative)
            }
        }

        val updatedRoute = route.copy(
            endLinks = updatedEndLinks,
            endBlocks = updatedEnds,
            endHelperBlocks = updatedHelpers
        )

        val routeEnd = routeEndBlockRelative(updatedRoute)
        val primaryEnd = primaryEndBlockRelative(updatedRoute)
        if (primaryEnd != null && routeEnd != null && primaryEnd != routeEnd) {
            val chain = resolveEndLinkChain(updatedRoute, routeEnd, primaryEnd)
            if (!chain.success) {
                endLinkRecording = null
                ChatUtils.modMessage("&c/nsr end failed: ${chain.error ?: "invalid end-link chain"}.")
                return true
            }
        }

        routes[session.roomName] = updatedRoute
        saveConfig()
        endLinkRecording = null
        val kind = if (session.type == EndLinkRecordingType.HELPER) "end helper link" else "end link"
        ChatUtils.modMessage("&aSaved $kind for &e${session.roomName}&a: &b${source.toShortString()} &7-> &b${targetRelative.toShortString()}&a.")
        return true
    }

    private fun recordGhostHeadClick(): Boolean {
        val target = PersistentSecretHeads.findGhostHeadTargetForRoute() ?: return false
        appendStep(RouteStep(RouteStepType.RIGHT_CLICK_SECRET, currentRelativePos(target)))
        return true
    }

    private fun recordTntStepFromHitResult(): Boolean {
        val hit = mc.hitResult as? BlockHitResult ?: return false
        return recordTntStep(hit.blockPos, hit.direction)
    }

    private fun recordTntStep(clicked: BlockPos, direction: Direction): Boolean {
        if (!shouldRecordTnt(clicked, direction)) return false

        appendStep(
            RouteStep(
                type = RouteStepType.PLACE_TNT,
                pos = currentRelativePos(clicked),
                direction = direction,
                secondaryPos = currentRelativePos(clicked.relative(direction))
            )
        )
        return true
    }

    private fun shouldRecordBreak(pos: BlockPos): Boolean {
        val now = System.currentTimeMillis()
        val last = lastBreakRecord
        if (last != null && last.first == pos && now - last.second < BREAK_RECORD_COOLDOWN_MS) return false
        lastBreakRecord = pos to now
        return true
    }

    private fun shouldRecordTnt(pos: BlockPos, direction: Direction): Boolean {
        val now = System.currentTimeMillis()
        val last = lastTntRecord
        if (last != null && last.first == pos && last.second == direction && now - last.third < TNT_RECORD_COOLDOWN_MS) return false
        lastTntRecord = Triple(pos, direction, now)
        return true
    }

    private fun appendStep(step: RouteStep) {
        val session = recording ?: return
        val rotation = currentRelativeRotation()
        val recordedStep = when {
            step.yaw != null && step.pitch != null -> step
            step.type == RouteStepType.WAIT_FOR_SECRET_PROGRESS || step.type == RouteStepType.WAIT_FOR_BAT_SPAWN -> step
            else -> step.copy(yaw = rotation?.yaw, pitch = rotation?.pitch)
        }

        session.steps.add(recordedStep)
        ChatUtils.modMessage("&7Recorded &e${recordedStep.type.name.lowercase().replace('_', ' ')}&7 step (${session.steps.size}).")
    }

    private fun currentRelativeRotation(): MathUtils.Rotation? {
        val player = mc.player ?: return null
        val ctx = currentRoomContext() ?: return null
        val relativeYaw = MathUtils.normalizeYaw(player.yRot + ctx.rotation.toFloat())
        return MathUtils.Rotation(relativeYaw, player.xRot)
    }

    private fun currentRelativePos(worldPos: BlockPos): BlockPos {
        val ctx = currentRoomContext() ?: error("Secret Routes requires a valid room context.")
        return toRelative(worldPos, ctx)
    }

    private fun toRelative(worldPos: BlockPos, ctx: RoomContext): BlockPos {
        return ScanUtils.getRelativeCoord(worldPos, ctx.corner, ctx.rotation)
    }

    private fun toWorld(relativePos: BlockPos, ctx: RoomContext): BlockPos {
        return ScanUtils.getRealCoord(relativePos, ctx.corner, ctx.rotation)
    }

    private fun currentRoomContext(): RoomContext? {
        val room = ScanUtils.currentRoom ?: return null
        val corner = room.corner ?: return null
        val rotation = room.rotation?.let { 360 - it } ?: return null
        return RoomContext(room, rotation, corner)
    }

    private fun renderRoute(event: RenderWorldEvent, ctx: RoomContext, route: RoomRoute) {
        val steps = route.steps.stepsForRender(ctx.room.name)
        val startBlock = toWorld(route.startBlock, ctx)
        val phase = renderThroughWalls.value

        if (renderStart.value) {
            Render3D.renderBlock(event.ctx, startBlock, startBlockColor.value, phase = phase)
            Render3D.renderString(
                "Start",
                startBlock.x + 0.5,
                startBlock.y + 1.2,
                startBlock.z + 0.5,
                scale = 1.5f,
                color = Color.WHITE,
                phase = phase
            )
        }

        if (renderStart.value) {
            var altIndex = 2
            val rendered = hashSetOf<BlockPos>()
            knownStartBlocksRelative(route)
                .asSequence()
                .filter { it != route.startBlock }
                .forEach { relativeStart ->
                    val altStart = toWorld(relativeStart, ctx)
                    if (!rendered.add(altStart)) return@forEach
                    Render3D.renderBlock(event.ctx, altStart, startBlockColor.value, phase = phase)
                    Render3D.renderString(
                        "Start ${altIndex++}",
                        altStart.x + 0.5,
                        altStart.y + 1.2,
                        altStart.z + 0.5,
                        scale = 1.2f,
                        color = Color.WHITE,
                        phase = phase
                    )
            }
        }

        if (renderEnd.value) {
            val routeEnd = routeEndBlockRelative(route)
            routeEnd?.let { relativeRouteEnd ->
                val ogEndBlock = toWorld(relativeRouteEnd, ctx)
                Render3D.renderBlock(event.ctx, ogEndBlock, endHelperBlockColor.value, phase = phase)
                Render3D.renderString(
                    "OG End",
                    ogEndBlock.x + 0.5,
                    ogEndBlock.y + 1.2,
                    ogEndBlock.z + 0.5,
                    scale = 1.1f,
                    color = Color.WHITE,
                    phase = phase
                )
            }

            val primaryEnd = primaryEndBlockRelative(route)
            primaryEnd?.let { relativeEnd ->
                if (relativeEnd != routeEnd) {
                    val endBlock = toWorld(relativeEnd, ctx)
                    Render3D.renderBlock(event.ctx, endBlock, endBlockColor.value, phase = phase)
                    Render3D.renderString(
                        "End 1",
                        endBlock.x + 0.5,
                        endBlock.y + 1.2,
                        endBlock.z + 0.5,
                        scale = 1.2f,
                        color = Color.WHITE,
                        phase = phase
                    )
                }
            }

            var endIndex = 2
            route.endBlocks.orEmpty()
                .asSequence()
                .filter { it != primaryEnd && it != routeEnd }
                .forEach { relativeEnd ->
                    val endBlock = toWorld(relativeEnd, ctx)
                    Render3D.renderBlock(event.ctx, endBlock, endBlockColor.value, phase = phase)
                    Render3D.renderString(
                        "End ${endIndex++}",
                        endBlock.x + 0.5,
                        endBlock.y + 1.2,
                        endBlock.z + 0.5,
                        scale = 1.1f,
                        color = Color.WHITE,
                        phase = phase
                    )
                }

            var helperIndex = 1
            route.endHelperBlocks.orEmpty()
                .asSequence()
                .filter { it != routeEnd }
                .forEach { helper ->
                    val helperBlock = toWorld(helper, ctx)
                    Render3D.renderBlock(event.ctx, helperBlock, endHelperBlockColor.value, phase = phase)
                    Render3D.renderString(
                        "End Helper ${helperIndex++}",
                        helperBlock.x + 0.5,
                        helperBlock.y + 1.2,
                        helperBlock.z + 0.5,
                        scale = 1.0f,
                        color = Color.WHITE,
                        phase = phase
                    )
                }
        }

        if (renderEtherwarpLines.value) {
            val etherwarpPoints = buildList {
                add(startBlock)
                steps.filter { it.type == RouteStepType.ETHERWARP }
                    .mapNotNullTo(this) { it.pos?.let { pos -> toWorld(pos, ctx) } }
            }

            etherwarpPoints.zipWithNext { from, to ->
                Render3D.renderLine(
                    event.ctx,
                    Vec3.atCenterOf(from),
                    Vec3.atCenterOf(to),
                    etherwarpLineColor.value,
                    etherwarpLineWidth.value,
                    phase
                )
            }
        }

        steps.forEachIndexed { index, step ->
            when (step.type) {
                RouteStepType.ETHERWARP -> {
                    if (!renderEtherwarpTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, etherwarpTargetColor.value, "EW ${index + 1}", phase)
                }

                RouteStepType.PLACE_TNT -> {
                    if (!renderTntTargets.value) return@forEachIndexed
                    val target = (step.secondaryPos ?: step.pos)?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, tntTargetColor.value, "TNT", phase)
                }

                RouteStepType.BREAK_BLOCK -> {
                    if (!renderBreakTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, breakTargetColor.value, "Break", phase)
                }

                RouteStepType.USE_HYPERION -> {
                    if (!renderHyperionTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, hyperionTargetColor.value, "Hyp", phase)
                }

                RouteStepType.RIGHT_CLICK_SECRET -> {
                    if (!renderSecretTargets.value) return@forEachIndexed
                    val target = step.pos?.let { toWorld(it, ctx) } ?: return@forEachIndexed
                    renderTarget(event, target, secretTargetColor.value, "Secret", phase)
                }

                RouteStepType.WAIT_FOR_SECRET_PROGRESS,
                RouteStepType.WAIT_FOR_BAT_SPAWN -> Unit
            }
        }
    }

    private fun renderTarget(
        event: RenderWorldEvent,
        pos: BlockPos,
        color: Color,
        label: String,
        phase: Boolean
    ) {
        Render3D.renderBlock(event.ctx, pos, color, phase = phase)
        Render3D.renderString(
            label,
            pos.x + 0.5,
            pos.y + 1.2,
            pos.z + 0.5,
            scale = 1.1f,
            color = Color.WHITE,
            phase = phase
        )
    }

    private fun List<RouteStep>.stepsForRender(roomName: String): List<RouteStep> {
        if (!partialRoutes.value) return this

        val boundaryIndexes = indices.filter { index ->
            this[index].type == RouteStepType.RIGHT_CLICK_SECRET || this[index].type == RouteStepType.WAIT_FOR_SECRET_PROGRESS
        }
        if (boundaryIndexes.isEmpty()) return this

        val completed = completedSecretsByRoom[roomName] ?: 0
        if (completed >= boundaryIndexes.size) return emptyList()

        val lastIndex = boundaryIndexes[completed]
        return subList(0, lastIndex + 1)
    }

    private fun markCurrentRoomSecretClick(worldPos: BlockPos) {
        val ctx = currentRoomContext() ?: return
        val route = routes[ctx.room.name] ?: return
        if (route.steps.none { it.type == RouteStepType.RIGHT_CLICK_SECRET && it.pos?.let { pos -> toWorld(pos, ctx) } == worldPos }) return
        markRoomSecretProgress(ctx.room.name)
    }

    private fun markRoomSecretProgress(roomName: String) {
        val route = routes[roomName] ?: return
        val maxSecrets = route.steps.count {
            it.type == RouteStepType.RIGHT_CLICK_SECRET || it.type == RouteStepType.WAIT_FOR_SECRET_PROGRESS
        }
        if (maxSecrets == 0) return

        val now = System.currentTimeMillis()
        val last = lastSecretProgressAtByRoom[roomName] ?: 0L
        if (now - last < 350L) return
        lastSecretProgressAtByRoom[roomName] = now

        val next = ((completedSecretsByRoom[roomName] ?: 0) + 1).coerceAtMost(maxSecrets)
        completedSecretsByRoom[roomName] = next
    }

    private fun clearSecretProgressCache() {
        completedSecretsByRoom.clear()
        lastSecretProgressAtByRoom.clear()
    }

    private fun abortPlayback(message: String): Boolean {
        SecretRoutesDebugger.failure { "Abort playback: $message" }
        ChatUtils.modMessage(message)
        stopPlayback()
        return false
    }

    private fun blockMessage(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedMessageAt < BLOCK_MESSAGE_COOLDOWN_MS) return
        lastBlockedMessageAt = now
        ChatUtils.modMessage("&e$message")
    }

    private fun releaseMovement() {
        listOf(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump)
            .forEach { it.isDown = false }
    }

    private fun isTntItemId(itemId: String?): Boolean {
        val id = itemId ?: return false
        return (id.contains("SUPERBOOM") && id.contains("TNT")) || (id.contains("BOOM") && id.contains("TNT"))
    }

    private fun isWitherBlade(itemId: String?): Boolean {
        return itemId?.equalsOneOf(*WITHER_BLADE_ITEM_IDS) == true
    }

    private fun isRouteRightClickTarget(pos: BlockPos): Boolean {
        if (DungeonUtils.isSecret(pos)) return true
        val block = mc.level?.getBlockState(pos)?.block ?: return false
        return block.equalsOneOf(Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.REDSTONE_BLOCK)
    }

    private fun knownStartBlocksRelative(route: RoomRoute): Set<BlockPos> {
        val known = linkedSetOf(route.startBlock)
        val links = route.startLinks.orEmpty()

        links.forEach { known.add(it.from) }
        links.forEach { link ->
            if (resolveStartLinkChain(route, link.to).success) {
                known.add(link.to)
            }
        }
        return known
    }

    private fun routeEndBlockRelative(route: RoomRoute): BlockPos? {
        return inferEndBlockFromSteps(route.steps) ?: route.startBlock
    }

    private fun primaryEndBlockRelative(route: RoomRoute): BlockPos? {
        val routeEnd = routeEndBlockRelative(route)
        val ends = route.endBlocks.orEmpty()
        return ends.firstOrNull { it != routeEnd }
            ?: ends.firstOrNull()
            ?: routeEnd
    }

    private fun knownEndBlocksRelative(route: RoomRoute): Set<BlockPos> {
        val known = linkedSetOf<BlockPos>()
        routeEndBlockRelative(route)?.let(known::add)
        known.addAll(route.endHelperBlocks.orEmpty())
        known.addAll(route.endBlocks.orEmpty())
        route.endLinks.orEmpty().forEach {
            known.add(it.from)
            known.add(it.to)
        }
        return known
    }

    private fun inferEndBlockFromSteps(steps: List<RouteStep>): BlockPos? {
        steps.asReversed()
            .asSequence()
            .firstOrNull { it.type == RouteStepType.ETHERWARP }
            ?.pos
            ?.let { return it }

        return steps.asReversed()
            .asSequence()
            .mapNotNull { it.startAnchorRelativePos() }
            .firstOrNull()
    }

    private fun buildEndPostRollSteps(route: RoomRoute, ctx: RoomContext): List<RouteStep> {
        val routeEnd = routeEndBlockRelative(route) ?: return emptyList()
        val primaryEnd = primaryEndBlockRelative(route) ?: return emptyList()
        if (routeEnd == primaryEnd) return emptyList()

        val chain = resolveEndLinkChain(route, routeEnd, primaryEnd)
        if (!chain.success) {
            SecretRoutesDebugger.failure {
                "End-link chain failed room=${ctx.room.name}, routeEnd=${routeEnd.toShortString()}, primaryEnd=${primaryEnd.toShortString()}, error=${chain.error}"
            }
            return emptyList()
        }

        return chain.links.map { link ->
            val fallbackRotation = endLinkFallbackRotation(ctx, link)
            val yaw = link.yaw ?: fallbackRotation?.yaw
            val pitch = link.pitch ?: fallbackRotation?.pitch
            RouteStep(
                type = RouteStepType.ETHERWARP,
                pos = link.to,
                direction = link.direction,
                yaw = yaw,
                pitch = pitch
            )
        }
    }

    private fun resolveEndLinkChain(route: RoomRoute, start: BlockPos, target: BlockPos): EndChainResolution {
        if (start == target) return EndChainResolution(success = true, links = emptyList())

        val linksByFrom = route.endLinks.orEmpty().groupBy { it.from }
        if (linksByFrom[start].isNullOrEmpty()) {
            return EndChainResolution(success = false, error = "dead-end at ${start.toShortString()}")
        }

        val queue = java.util.ArrayDeque<BlockPos>()
        val visited = hashSetOf<BlockPos>()
        val depthByPos = hashMapOf<BlockPos, Int>()
        val previous = hashMapOf<BlockPos, Pair<BlockPos, EndLink>>()
        var exceededHopLimit = false

        queue.add(start)
        visited.add(start)
        depthByPos[start] = 0

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val depth = depthByPos[current] ?: 0
            if (depth >= START_LINK_MAX_HOPS) {
                exceededHopLimit = true
                continue
            }

            for (link in linksByFrom[current].orEmpty()) {
                val next = link.to
                if (visited.contains(next)) continue

                visited.add(next)
                depthByPos[next] = depth + 1
                previous[next] = current to link

                if (next == target) {
                    val path = mutableListOf<EndLink>()
                    var cursor = target
                    while (cursor != start) {
                        val step = previous[cursor]
                            ?: return EndChainResolution(
                                success = false,
                                error = "internal chain reconstruction failure at ${cursor.toShortString()}"
                            )
                        path.add(step.second)
                        cursor = step.first
                    }
                    path.reverse()
                    return EndChainResolution(success = true, links = path)
                }

                queue.add(next)
            }
        }

        if (exceededHopLimit) {
            return EndChainResolution(success = false, error = "too many end links (>${START_LINK_MAX_HOPS})")
        }

        return EndChainResolution(
            success = false,
            error = "no path from ${start.toShortString()} to ${target.toShortString()}"
        )
    }

    private fun resolveStartLinkChain(route: RoomRoute, start: BlockPos): StartChainResolution {
        if (start == route.startBlock) return StartChainResolution(success = true, links = emptyList())

        val nextByFrom = route.startLinks.orEmpty()
            .fold(linkedMapOf<BlockPos, StartLink>()) { acc, link ->
                acc[link.from] = link
                acc
            }

        val visited = hashSetOf<BlockPos>()
        val links = mutableListOf<StartLink>()
        var current = start

        repeat(START_LINK_MAX_HOPS) {
            if (current == route.startBlock) {
                return StartChainResolution(success = true, links = links.toList())
            }
            if (!visited.add(current)) {
                return StartChainResolution(success = false, error = "cycle detected at ${current.toShortString()}")
            }

            val next = nextByFrom[current]
                ?: return StartChainResolution(success = false, error = "dead-end at ${current.toShortString()}")

            links.add(next)
            current = next.to
        }

        return StartChainResolution(success = false, error = "too many start links (>${START_LINK_MAX_HOPS})")
    }

    private fun collectDependentStartSources(links: List<StartLink>, root: BlockPos): Set<BlockPos> {
        val removed = linkedSetOf<BlockPos>()
        val reverse = links.groupBy({ it.to }, { it.from })
        val queue = ArrayDeque<BlockPos>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!removed.add(current)) continue
            reverse[current].orEmpty().forEach { queue.add(it) }
        }

        return removed
    }

    private fun collectDependentEndNodes(links: List<EndLink>, root: BlockPos): Set<BlockPos> {
        val removed = linkedSetOf<BlockPos>()
        val reverse = links.groupBy({ it.to }, { it.from })
        val queue = ArrayDeque<BlockPos>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!removed.add(current)) continue
            reverse[current].orEmpty().forEach { queue.add(it) }
        }

        return removed
    }

    private fun startLinkFallbackRotation(ctx: RoomContext, link: StartLink): MathUtils.Rotation? {
        val from = toWorld(link.from, ctx)
        val to = BlockAimUtils.blockCenter(toWorld(link.to, ctx))
        val eye = Vec3(from.x + 0.5, from.y + 1.62, from.z + 0.5)

        val dx = to.x - eye.x
        val dy = to.y - eye.y
        val dz = to.z - eye.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal <= 1.0e-6 && abs(dy) <= 1.0e-6) return null

        val worldYaw = MathUtils.normalizeYaw((Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat())
        val worldPitch = MathUtils.normalizePitch((-Math.toDegrees(atan2(dy, horizontal))).toFloat())
        val relativeYaw = MathUtils.normalizeYaw(worldYaw + ctx.rotation.toFloat())

        return MathUtils.Rotation(relativeYaw, worldPitch)
    }

    private fun endLinkFallbackRotation(ctx: RoomContext, link: EndLink): MathUtils.Rotation? {
        val from = toWorld(link.from, ctx)
        val to = BlockAimUtils.blockCenter(toWorld(link.to, ctx))
        val eye = Vec3(from.x + 0.5, from.y + 1.62, from.z + 0.5)

        val dx = to.x - eye.x
        val dy = to.y - eye.y
        val dz = to.z - eye.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal <= 1.0e-6 && abs(dy) <= 1.0e-6) return null

        val worldYaw = MathUtils.normalizeYaw((Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat())
        val worldPitch = MathUtils.normalizePitch((-Math.toDegrees(atan2(dy, horizontal))).toFloat())
        val relativeYaw = MathUtils.normalizeYaw(worldYaw + ctx.rotation.toFloat())

        return MathUtils.Rotation(relativeYaw, worldPitch)
    }

    private fun RouteStep.rotation(ctx: RoomContext): MathUtils.Rotation? {
        val yaw = yaw ?: return null
        val pitch = pitch ?: return null
        val worldYaw = MathUtils.normalizeYaw(yaw - ctx.rotation.toFloat())
        return MathUtils.Rotation(worldYaw, pitch)
    }

    private fun RouteStep.startAnchorRelativePos(): BlockPos? {
        return when (type) {
            RouteStepType.ETHERWARP -> pos
            RouteStepType.PLACE_TNT -> secondaryPos ?: pos
            RouteStepType.BREAK_BLOCK -> pos
            RouteStepType.USE_HYPERION -> pos
            RouteStepType.RIGHT_CLICK_SECRET -> pos
            RouteStepType.WAIT_FOR_SECRET_PROGRESS,
            RouteStepType.WAIT_FOR_BAT_SPAWN -> null
        }
    }

    private fun buildResumeLabel(steps: List<RouteStep>, index: Int): String {
        val step = steps.getOrNull(index) ?: return "matched step"
        val ordinal = steps.take(index + 1).count { it.type == step.type }
        val prefix = when (step.type) {
            RouteStepType.ETHERWARP -> "EW"
            RouteStepType.PLACE_TNT -> "TNT"
            RouteStepType.BREAK_BLOCK -> "BREAK"
            RouteStepType.USE_HYPERION -> "HYP"
            RouteStepType.RIGHT_CLICK_SECRET -> "SECRET"
            RouteStepType.WAIT_FOR_SECRET_PROGRESS -> "WAIT"
            RouteStepType.WAIT_FOR_BAT_SPAWN -> "BAT"
        }
        return "$prefix $ordinal"
    }

    private fun resumeStartIndexForMatchedStep(steps: List<RouteStep>, index: Int): Int {
        val step = steps.getOrNull(index) ?: return index
        return when (step.type) {
            RouteStepType.ETHERWARP -> (index + 1).coerceAtMost(steps.size)
            else -> index
        }
    }
}
