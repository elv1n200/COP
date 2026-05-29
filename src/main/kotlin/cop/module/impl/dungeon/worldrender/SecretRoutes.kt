package cop.module.impl.dungeon.worldrender

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.DungeonEvent
import cop.api.events.RenderEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.inBoss
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.dungeon.odonscanning.RouteData
import cop.api.skyblock.dungeon.odonscanning.RouteData.SecretType
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonRoom
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import cop.utils.render.drawLine
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Renders secret routes for the current dungeon room as world-space waypoints.
 *
 * The route DB ships with COP at `assets/cop/secretroutes/{routes,pearlroutes}.json`,
 * sourced from yourboykyle's Secret Routes Mod (see `CREDITS.md`). Coordinates in
 * the DB are room-canonical; on room enter we look them up by [OdonRoom.name] and
 * pre-translate them to world coords via [OdonRoom.getRealCoords], so the render
 * loop is just iteration + draw calls.
 *
 * Per secret the DB may carry multiple alternate routes — by default we show the
 * one whose first waypoint is closest to the player, to avoid visual clutter; flip
 * [showAllRoutes] off-by-default to expose all of them.
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

    private val showAllRoutes by switch(
        "Show all routes", false,
        desc = "If off, only the route whose first waypoint is closest to you is shown per secret. If on, all known routes are drawn."
    )
    private val lineThickness by slider(
        "Line thickness", 3.0f, 0.5f, 10.0f, 0.1f,
        desc = "Polyline thickness in pixels."
    )
    private val throughWalls by switch(
        "Through walls", true,
        desc = "Render through terrain (no depth test)."
    )

    private val lineColour by colourPicker(
        "Line", Colour.CYAN.withAlpha(0.85f), allowAlpha = true,
        desc = "Walking path between locations."
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
    private val secretColour by colourPicker(
        "Secret target", Colour.RED.withAlpha(0.45f), allowAlpha = true,
        desc = "The secret itself (interact chest / bat spot / item ground / exit / ...)."
    )

    // -- Pre-translated route state ----------------------------------------

    private data class WorldRoute(
        val locations: List<Vec3>,
        val etherwarps: List<BlockPos>,
        val mines: List<BlockPos>,
        val interacts: List<BlockPos>,
        val tnts: List<BlockPos>,
        val secret: WorldSecret?,
    )

    private data class WorldSecret(val type: SecretType, val pos: BlockPos)

    /** Grouped by secret index so per-secret "closest only" filtering works. */
    private data class RoutesForSecret(val secretIndex: Int, val alternates: List<WorldRoute>)

    private val activeRoutes = CopyOnWriteArrayList<RoutesForSecret>()

    init {
        on<DungeonEvent.Room.Enter> {
            onRoomEnter(room)
        }

        on<WorldEvent.Change> {
            activeRoutes.clear()
        }

        on<RenderEvent.World> {
            if (!inDungeons || inBoss) return@on
            if (activeRoutes.isEmpty()) return@on

            val depth = !throughWalls
            val thickness = lineThickness
            val playerPos = mc.player?.position()

            for (group in activeRoutes) {
                val routesToDraw = if (showAllRoutes || playerPos == null || group.alternates.size <= 1) {
                    group.alternates
                } else {
                    listOf(group.alternates.minBy { firstWaypointSqr(it, playerPos) })
                }
                for (route in routesToDraw) {
                    if (route.locations.size >= 2) {
                        ctx.drawLine(route.locations, lineColour, depth = depth, thickness = thickness)
                    }
                    drawBoxes(route.etherwarps, etherwarpColour)
                    drawBoxes(route.mines, mineColour)
                    drawBoxes(route.interacts, interactColour)
                    drawBoxes(route.tnts, tntColour)
                    route.secret?.let { drawBoxes(listOf(it.pos), secretColour) }
                }
            }
        }
    }

    private fun RenderEvent.World.drawBoxes(positions: List<BlockPos>, colour: Colour) {
        if (positions.isEmpty()) return
        val depth = !throughWalls
        for (pos in positions) {
            ctx.drawFilledBox(pos.aabb, colour, depth = depth)
        }
    }

    private fun firstWaypointSqr(route: WorldRoute, player: Vec3): Double {
        val first = route.locations.firstOrNull() ?: return Double.MAX_VALUE
        return first.distanceToSqr(player)
    }

    private fun onRoomEnter(room: OdonRoom?) {
        activeRoutes.clear()
        if (room == null) return

        val groups = RouteData[room.name]
        if (groups.isEmpty()) return

        for (group in groups) {
            val translated = group.routes.map { r ->
                WorldRoute(
                    locations = r.locations.map { Vec3.atCenterOf(room.getRealCoords(it)) },
                    etherwarps = r.etherwarps.map(room::getRealCoords),
                    mines = r.mines.map(room::getRealCoords),
                    interacts = r.interacts.map(room::getRealCoords),
                    tnts = r.tnts.map(room::getRealCoords),
                    secret = r.secret?.let { WorldSecret(it.type, room.getRealCoords(it.location)) },
                )
            }
            activeRoutes += RoutesForSecret(group.secretIndex, translated)
        }
    }
}
