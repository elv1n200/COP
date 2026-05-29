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
import cop.utils.ChatUtils.literal
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import cop.utils.render.drawLine
import cop.utils.render.drawText
import cop.utils.render.drawWireFrameBox
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Renders secret routes for the current dungeon room as world-space waypoints.
 *
 * Route data ships with COP at `assets/cop/secretroutes/{routes,pearlroutes}.json`,
 * sourced from yourboykyle's Secret Routes Mod (see `CREDITS.md`). Coordinates in
 * the DB are room-canonical; on room enter we look them up by [OdonRoom.name] and
 * pre-translate them to world coords via [OdonRoom.getRealCoords], so the render
 * loop is just iteration + draw calls.
 *
 * By default only the route to the **nearest secret** in the room is drawn — most
 * rooms have 2–8 secrets and rendering every alternate for every secret simultaneously
 * is visually overwhelming. Flip [showAllSecrets] on for the whole-room overview.
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
    private val showAllAlternates by switch(
        "Show alternates", false,
        desc = "When a secret has multiple known routes, show all of them instead of just the one whose first waypoint is closest to you."
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

    private data class WorldRoute(
        val locations: List<Vec3>,
        val etherwarps: List<BlockPos>,
        val mines: List<BlockPos>,
        val interacts: List<BlockPos>,
        val tnts: List<BlockPos>,
        val secret: WorldSecret?,
    )

    private data class WorldSecret(val type: SecretType, val pos: BlockPos)

    /** Grouped by secret index so "single nearest secret" + "alternates" filters work. */
    private data class RoutesForSecret(val secretIndex: Int, val alternates: List<WorldRoute>) {
        /** Anchor used for "which secret is closest to the player" — prefer the
         *  secret target itself (the thing you're trying to reach); fall back to
         *  the first waypoint of any alternate if no secret position is recorded. */
        val anchor: Vec3? = alternates.firstNotNullOfOrNull { it.secret?.let { s -> Vec3.atCenterOf(s.pos) } }
            ?: alternates.firstNotNullOfOrNull { it.locations.firstOrNull() }
    }

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

            val playerPos = mc.player?.position()

            // Always draw a small target marker for EVERY secret in the room so
            // the player has spatial awareness of where the goals are — even
            // when only one route's full path is rendered. Keeps "show only
            // nearest" usable: you can still see the other 7 secrets without
            // their lines/waypoints cluttering the view.
            if (!showAllSecrets) {
                for (group in activeRoutes) {
                    val s = group.alternates.firstNotNullOfOrNull { it.secret } ?: continue
                    drawBoxes(listOf(s.pos), colourForSecret(s.type))
                }
            }

            val groupsToDraw = if (showAllSecrets || playerPos == null) {
                activeRoutes
            } else {
                listOf(nearestGroup(playerPos) ?: return@on)
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
        route.secret?.let { drawBoxes(listOf(it.pos), colourForSecret(it.type)) }
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

    private fun nearestGroup(player: Vec3): RoutesForSecret? =
        activeRoutes.minByOrNull { it.anchor?.distanceToSqr(player) ?: Double.MAX_VALUE }

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
