package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ColorSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.showIf
import com.github.noamm9.ui.hud.HudElement
import com.github.noamm9.utils.dungeons.map.core.Room
import com.github.noamm9.utils.dungeons.map.core.RoomType
import com.github.noamm9.utils.dungeons.map.core.UniqueRoom
import com.github.noamm9.utils.dungeons.map.utils.ScanUtils
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.render.Render2D
import com.github.noamm9.utils.render.Render2D.width
import com.github.noamm9.utils.render.Render3D
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import java.awt.Color
import kotlin.math.abs
import kotlin.math.roundToInt

object NSRHelper : Feature(
    name = "NSR Helper",
    description = "Checks secret route start/end doorway coverage for the current room."
) {
    private const val FALLBACK_DUNGEON_TILE_STEP = 16
    private const val DOOR_STAND_DISTANCE_FROM_DOOR = 3
    private const val REQUIRED_BLOCK_Y = 68

    private val hudSection = "hud"
    private val renderSection = "render"

    private val showHud by ToggleSetting("Show HUD", true).section(hudSection)
    private val renderMissing by ToggleSetting("Render Missing Blocks", true).section(renderSection)
    private val renderThroughWalls by ToggleSetting("Render Through Walls", true).showIf { renderMissing.value }
    private val missingBlockColor by ColorSetting("Missing Block Color", Color(255, 48, 48, 140), true).showIf { renderMissing.value }
    private val hudBackgroundColor by ColorSetting("HUD Background", Color(0, 0, 0, 120), true).section(hudSection)
    private val hudBorderColor by ColorSetting("HUD Border", Color(255, 255, 255, 150), true).showIf { showHud.value }

    private data class HelperState(
        val roomName: String,
        val routePresent: Boolean,
        val sameStartAndEnd: Boolean,
        val totalDoors: Int,
        val startCovered: Int,
        val endCovered: Int,
        val startComplete: Boolean,
        val endComplete: Boolean,
        val completed: Boolean,
        val missingStartBlocks: Set<BlockPos>,
        val missingEndBlocks: Set<BlockPos>
    )

    private val hudElement = object : HudElement() {
        override val name: String = "NSR Helper"
        override val toggle get() = this@NSRHelper.enabled && showHud.value
        override val shouldDraw get() = mc.player != null && LocationUtils.inDungeon && !LocationUtils.inBoss

        override fun draw(ctx: net.minecraft.client.gui.GuiGraphics, example: Boolean): Pair<Float, Float> {
            val lines = if (example) {
                listOf(
                    "&bRoom: &fExample Room",
                    "&bSame Start/End: &aYes",
                    "&bStart Door Coverage: &a4/4",
                    "&bEnd Door Coverage: &a4/4",
                    "&bStatus: &aCompleted"
                )
            } else {
                val state = buildState()
                buildHudLines(state)
            }

            if (lines.isEmpty()) return 0f to 0f

            val padding = 4f
            val lineHeight = 9f
            val width = lines.maxOf { it.width().toFloat() } + padding * 2f
            val height = lines.size * lineHeight + padding * 2f

            Render2D.drawRect(ctx, 0f, 0f, width, height, hudBackgroundColor.value)
            Render2D.drawBorder(ctx, 0f, 0f, width, height, hudBorderColor.value, 1)

            lines.forEachIndexed { index, line ->
                Render2D.drawString(ctx, line, padding, padding + index * lineHeight)
            }

            return width to height
        }
    }.also(hudElements::add)

    override fun init() {
        register<RenderWorldEvent> {
            if (!enabled || !renderMissing.value) return@register
            if (!LocationUtils.inDungeon || LocationUtils.inBoss) return@register

            val state = buildState() ?: return@register
            if (!state.routePresent) return@register
            val missing = linkedSetOf<BlockPos>()
            missing.addAll(state.missingStartBlocks)
            missing.addAll(state.missingEndBlocks)

            missing.forEach { pos ->
                Render3D.renderBlock(event.ctx, pos, missingBlockColor.value, phase = renderThroughWalls.value)
            }
        }
    }

    private fun buildHudLines(state: HelperState?): List<String> {
        if (state == null) {
            return listOf(
                "&bRoom: &7N/A",
                "&bStatus: &cNot Completed"
            )
        }

        val sameLine = if (state.sameStartAndEnd) "&aYes" else "&cNo"
        val startLine = if (state.startComplete) "&a${state.startCovered}/${state.totalDoors}" else "&c${state.startCovered}/${state.totalDoors}"
        val endLine = if (state.endComplete) "&a${state.endCovered}/${state.totalDoors}" else "&c${state.endCovered}/${state.totalDoors}"

        val status = if (state.completed) "&aCompleted" else "&cNot Completed"
        val routeLine = if (state.routePresent) "&aLoaded" else "&cMissing"

        return listOf(
            "&bRoom: &f${state.roomName}",
            "&bRoute: $routeLine",
            "&bSame Start/End: $sameLine",
            "&bStart Door Coverage: $startLine",
            "&bEnd Door Coverage: $endLine",
            "&bStatus: $status"
        )
    }

    private fun buildState(): HelperState? {
        val room = ScanUtils.currentRoom ?: return null
        val routeSnapshot = SecretRoutes.getCurrentRoomHelperSnapshot()
        val totalDoors = expectedDoorTotal(room)
        val requiredDoorBlocks = computeRequiredDoorBlocks(room, routeSnapshot, totalDoors)
        val requiredXZ = requiredDoorBlocks
            .asSequence()
            .map { it.x to it.z }
            .toSet()

        val startXZ = routeSnapshot?.startBlocksWorld.orEmpty()
            .asSequence()
            .map { it.x to it.z }
            .toSet()
        val endXZ = routeSnapshot?.endNodesWorld.orEmpty()
            .asSequence()
            .map { it.x to it.z }
            .toSet()

        val startCovered = requiredXZ.count { it in startXZ }
        val endCovered = requiredXZ.count { it in endXZ }
        val same = routeSnapshot?.sameStartAndOgEnd == true
        val startComplete = startCovered >= totalDoors
        val endComplete = endCovered >= totalDoors
        val completed = routeSnapshot != null && same && startComplete && endComplete

        val missingStart = requiredDoorBlocks
            .filterNot { (it.x to it.z) in startXZ }
            .toSet()
        val missingEnd = requiredDoorBlocks
            .filterNot { (it.x to it.z) in endXZ }
            .toSet()

        return HelperState(
            roomName = room.name,
            routePresent = routeSnapshot != null,
            sameStartAndEnd = same,
            totalDoors = totalDoors,
            startCovered = startCovered,
            endCovered = endCovered,
            startComplete = startComplete,
            endComplete = endComplete,
            completed = completed,
            missingStartBlocks = missingStart,
            missingEndBlocks = missingEnd
        )
    }

    private fun computeRequiredDoorBlocks(
        room: UniqueRoom,
        routeSnapshot: SecretRoutes.RouteHelperSnapshot?,
        totalDoors: Int
    ): Set<BlockPos> {
        if (totalDoors <= 0) return emptySet()
        val candidates = computePerimeterDoorBlocks(room)
        if (candidates.isEmpty()) return emptySet()

        if (totalDoors == 1) {
            return setOf(selectSingleDoorBlock(candidates, room, routeSnapshot))
        }

        if (candidates.size == totalDoors) return candidates.toSet()
        if (candidates.size < totalDoors) return candidates.toSet()

        val center = room.centerPos
        return candidates
            .sortedBy { abs(it.x - center.x) + abs(it.z - center.z) }
            .take(totalDoors)
            .toSet()
    }

    private fun selectSingleDoorBlock(
        candidates: List<BlockPos>,
        room: UniqueRoom,
        routeSnapshot: SecretRoutes.RouteHelperSnapshot?
    ): BlockPos {
        val reference = routeSnapshot?.startBlocksWorld?.firstOrNull()
            ?: routeSnapshot?.endNodesWorld?.firstOrNull()
            ?: room.centerPos

        return candidates.minByOrNull { abs(it.x - reference.x) + abs(it.z - reference.z) } ?: candidates.first()
    }

    private fun computePerimeterDoorBlocks(
        room: UniqueRoom
    ): List<BlockPos> {
        val discoveredTiles = room.tiles
            .asSequence()
            .map { it.x to it.z }
            .toSet()
        val shape = room.data.shape
            .lowercase()
            .replace(" ", "")
            .replace("-", "")

        val rectangular = parseShapeDimensions(shape)
        if (rectangular != null && !shape.contains("l")) {
            val rectBlocks = computeRectangularDoorBlocks(discoveredTiles, rectangular.first, rectangular.second)
            if (rectBlocks.isNotEmpty()) return rectBlocks
        }

        val tileStep = resolveDungeonTileStep(discoveredTiles)
        val roomTiles = buildOccupiedRoomTiles(room, discoveredTiles, tileStep)
        val horizontal = listOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
        val blocks = linkedMapOf<Pair<Int, Int>, BlockPos>()

        roomTiles.forEach { (tileX, tileZ) ->
            horizontal.forEach { dir ->
                val neighbor = tileX + dir.stepX * tileStep to tileZ + dir.stepZ * tileStep
                if (neighbor in roomTiles) return@forEach

                val doorX = tileX + dir.stepX * tileStep
                val doorZ = tileZ + dir.stepZ * tileStep
                val standX = doorX - dir.stepX * DOOR_STAND_DISTANCE_FROM_DOOR
                val standZ = doorZ - dir.stepZ * DOOR_STAND_DISTANCE_FROM_DOOR
                val key = standX to standZ
                if (key in blocks) return@forEach

                blocks[key] = BlockPos(standX, REQUIRED_BLOCK_Y, standZ)
            }
        }

        return blocks.values.toList()
    }

    private fun computeRectangularDoorBlocks(
        discoveredTiles: Set<Pair<Int, Int>>,
        widthTiles: Int,
        heightTiles: Int
    ): List<BlockPos> {
        if (discoveredTiles.isEmpty() || widthTiles <= 0 || heightTiles <= 0) return emptyList()

        val xs = discoveredTiles.map { it.first }
        val zs = discoveredTiles.map { it.second }
        val minX = xs.minOrNull() ?: return emptyList()
        val maxX = xs.maxOrNull() ?: return emptyList()
        val minZ = zs.minOrNull() ?: return emptyList()
        val maxZ = zs.maxOrNull() ?: return emptyList()

        val stepX = if (widthTiles > 1) {
            ((maxX - minX).toDouble() / (widthTiles - 1)).roundToInt().coerceAtLeast(1)
        } else {
            resolveDungeonTileStep(discoveredTiles)
        }
        val stepZ = if (heightTiles > 1) {
            ((maxZ - minZ).toDouble() / (heightTiles - 1)).roundToInt().coerceAtLeast(1)
        } else {
            resolveDungeonTileStep(discoveredTiles)
        }

        val xCenters = (0 until widthTiles).map { minX + it * stepX }
        val zCenters = (0 until heightTiles).map { minZ + it * stepZ }

        val northZ = zCenters.first() - (stepZ - DOOR_STAND_DISTANCE_FROM_DOOR)
        val southZ = zCenters.last() + (stepZ - DOOR_STAND_DISTANCE_FROM_DOOR)
        val westX = xCenters.first() - (stepX - DOOR_STAND_DISTANCE_FROM_DOOR)
        val eastX = xCenters.last() + (stepX - DOOR_STAND_DISTANCE_FROM_DOOR)

        val blocks = linkedMapOf<Pair<Int, Int>, BlockPos>()
        xCenters.forEach { x ->
            blocks[x to northZ] = BlockPos(x, REQUIRED_BLOCK_Y, northZ)
            blocks[x to southZ] = BlockPos(x, REQUIRED_BLOCK_Y, southZ)
        }
        zCenters.forEach { z ->
            blocks[westX to z] = BlockPos(westX, REQUIRED_BLOCK_Y, z)
            blocks[eastX to z] = BlockPos(eastX, REQUIRED_BLOCK_Y, z)
        }
        return blocks.values.toList()
    }

    private fun parseShapeDimensions(shape: String): Pair<Int, Int>? {
        val match = Regex("""^(\d+)x(\d+)$""").find(shape) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private fun buildOccupiedRoomTiles(
        room: UniqueRoom,
        discoveredTiles: Set<Pair<Int, Int>>,
        tileStep: Int
    ): Set<Pair<Int, Int>> {
        if (discoveredTiles.size <= 1) return discoveredTiles

        val shape = room.data.shape
            .lowercase()
            .replace(" ", "")
            .replace("-", "")
        if (shape.contains("l")) return discoveredTiles

        val xs = discoveredTiles.map { it.first }
        val zs = discoveredTiles.map { it.second }
        val minX = xs.minOrNull() ?: return discoveredTiles
        val maxX = xs.maxOrNull() ?: return discoveredTiles
        val minZ = zs.minOrNull() ?: return discoveredTiles
        val maxZ = zs.maxOrNull() ?: return discoveredTiles

        val filled = linkedSetOf<Pair<Int, Int>>()
        var x = minX
        while (x <= maxX) {
            var z = minZ
            while (z <= maxZ) {
                filled.add(x to z)
                z += tileStep
            }
            x += tileStep
        }
        return filled
    }

    private fun resolveDungeonTileStep(discoveredTiles: Set<Pair<Int, Int>>): Int {
        if (discoveredTiles.size <= 1) return FALLBACK_DUNGEON_TILE_STEP

        val xDiffs = discoveredTiles
            .asSequence()
            .map { it.first }
            .distinct()
            .sorted()
            .zipWithNext()
            .map { (a, b) -> b - a }
            .filter { it > 0 }
            .toList()
        val zDiffs = discoveredTiles
            .asSequence()
            .map { it.second }
            .distinct()
            .sorted()
            .zipWithNext()
            .map { (a, b) -> b - a }
            .filter { it > 0 }
            .toList()

        val diffs = (xDiffs + zDiffs)
        return diffs.minOrNull() ?: FALLBACK_DUNGEON_TILE_STEP
    }

    private fun expectedDoorTotal(room: UniqueRoom): Int {
        val type = room.data.type
        if (type == RoomType.TRAP || type == RoomType.PUZZLE || type == RoomType.BLOOD || type == RoomType.CHAMPION) {
            return 1
        }

        val shape = room.data.shape
            .lowercase()
            .replace(" ", "")
            .replace("-", "")

        return when {
            shape == "1x1" -> 4
            shape == "2x1" || shape == "1x2" -> 6
            shape == "2x2" -> 8
            shape.contains("l") -> 8
            shape == "3x1" || shape == "1x3" -> 8
            shape == "4x1" || shape == "1x4" -> 10
            else -> 4
        }
    }
}
