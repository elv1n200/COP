package cop.module.impl.dungeon.solvers

import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.RenderEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import cop.utils.render.drawWireFrameBox
import net.minecraft.core.BlockPos

/**
 * Static waypoints for every terminal and lever in the F7/M7 phase 2-5 boss
 * rooms. Lifted from Athen — coordinates are absolute world positions.
 *
 * Default-class assignments mirror the standard "every class handles their
 * own quadrant" convention, but the optional class filter lets you only see
 * the ones assigned to *your* class so the boss render stays uncluttered.
 *
 * @author elvin
 */
object TerminalWaypoints : Module(
    "Terminal Waypoints",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Renders waypoints to every terminal/lever across the four boss sections.",
) {
    // ---- settings ----
    private val style by selector("Style", "Both", arrayListOf("Outline", "Filled", "Both"))
    private val depthTest by switch("Depth test", false)
    private val terminalCol by colourPicker(
        "Terminal colour", Colour.RGB(0, 255, 255).withAlpha(160), allowAlpha = true,
    )
    private val leverCol by colourPicker(
        "Lever colour", Colour.RGB(255, 255, 0).withAlpha(160), allowAlpha = true,
    )
    private val outlineThickness by slider("Thickness", 2.0f, 1.0f, 6.0f, 0.5f)

    private val classFilter by switch("Class filter", false,
        desc = "Only show terminals/levers assigned to your class.")
    private val ownClassOverride by selector(
        "Override class", "Auto",
        arrayListOf("Auto", "Healer", "Mage", "Berserker", "Archer", "Tank"),
        desc = "Force a class instead of auto-detecting from the dungeon team list.",
    ).childOf(::classFilter)

    // ---- waypoint table ----
    private sealed class Node(val pos: BlockPos, val owner: DungeonClass, val section: Int) {
        class Terminal(pos: BlockPos, owner: DungeonClass, section: Int) : Node(pos, owner, section)
        class Lever(pos: BlockPos, owner: DungeonClass, section: Int) : Node(pos, owner, section)
    }

    // Coordinates from Athen's TerminalWaypoints. Each Node uses the "primary"
    // armor-stand block position; we don't need both coords from Athen's
    // (start, end) pairs because we render a 1-block AABB.
    private val nodes: List<Node> = listOf(
        // --- Section 1 (P2 — east half above blood) ---
        Node.Terminal(BlockPos(111, 113, 73), DungeonClass.Tank,    1),
        Node.Terminal(BlockPos(111, 119, 79), DungeonClass.Tank,    1),
        Node.Terminal(BlockPos( 89, 112, 92), DungeonClass.Mage,    1),
        Node.Terminal(BlockPos( 89, 122, 101), DungeonClass.Mage,   1),
        Node.Lever   (BlockPos( 94, 124, 113), DungeonClass.Archer, 1),
        Node.Lever   (BlockPos(106, 124, 113), DungeonClass.Archer, 1),

        // --- Section 2 (P3) ---
        Node.Terminal(BlockPos( 68, 109, 121), DungeonClass.Tank,    2),
        Node.Terminal(BlockPos( 59, 120, 122), DungeonClass.Mage,    2),
        Node.Terminal(BlockPos( 47, 109, 121), DungeonClass.Berserk, 2),
        Node.Terminal(BlockPos( 39, 108, 143), DungeonClass.Archer,  2),
        Node.Terminal(BlockPos( 40, 124, 122), DungeonClass.Berserk, 2),
        Node.Lever   (BlockPos( 27, 124, 127), DungeonClass.Archer,  2),
        Node.Lever   (BlockPos( 23, 132, 138), DungeonClass.Healer,  2),

        // --- Section 3 (P4) ---
        Node.Terminal(BlockPos( -3, 109, 112), DungeonClass.Tank,    3),
        Node.Terminal(BlockPos( -3, 119,  93), DungeonClass.Healer,  3),
        Node.Terminal(BlockPos( 19, 123,  93), DungeonClass.Berserk, 3),
        Node.Terminal(BlockPos( -3, 109,  77), DungeonClass.Archer,  3),
        Node.Lever   (BlockPos( 14, 122,  55), DungeonClass.Archer,  3),
        Node.Lever   (BlockPos(  2, 122,  55), DungeonClass.Archer,  3),

        // --- Section 4 (P5 — west bottom) ---
        Node.Terminal(BlockPos( 41, 109,  29), DungeonClass.Tank,    4),
        Node.Terminal(BlockPos( 44, 121,  29), DungeonClass.Archer,  4),
        Node.Terminal(BlockPos( 67, 109,  29), DungeonClass.Berserk, 4),
        Node.Terminal(BlockPos( 72, 115,  48), DungeonClass.Healer,  4),
        Node.Lever   (BlockPos( 86, 128,  46), DungeonClass.Healer,  4),
        Node.Lever   (BlockPos( 84, 121,  34), DungeonClass.Healer,  4),
    )

    init {
        on<RenderEvent.World> {
            if (Dungeon.deathTick < 0 && !Dungeon.inBoss) return@on
            val ownClass = resolveOwnClass()
            val depth = depthTest

            for (node in nodes) {
                if (classFilter && ownClass != null && node.owner != ownClass) continue

                val box = node.pos.aabb
                val outline = if (node is Node.Terminal) terminalCol else leverCol
                val fill    = outline.withAlpha(60)

                when (style.selected) {
                    "Outline" -> ctx.drawWireFrameBox(box, outline, outlineThickness, depth)
                    "Filled"  -> ctx.drawFilledBox(box, fill, depth)
                    "Both"    -> {
                        ctx.drawFilledBox(box, fill, depth)
                        ctx.drawWireFrameBox(box, outline, outlineThickness, depth)
                    }
                }
            }
        }
    }

    private fun resolveOwnClass(): DungeonClass? = when (ownClassOverride.selected) {
        "Healer"     -> DungeonClass.Healer
        "Mage"       -> DungeonClass.Mage
        "Berserker"  -> DungeonClass.Berserk
        "Archer"     -> DungeonClass.Archer
        "Tank"       -> DungeonClass.Tank
        else /*Auto*/-> Dungeon.currentDungeonPlayer.clazz.takeIf { it != DungeonClass.Unknown }
    }
}
