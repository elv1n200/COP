package cop.module.impl.dungeon.solvers

import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.PacketEvent
import cop.api.events.RenderEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.dungeon.M7Phases
import cop.api.skyblock.dungeon.P3Section
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.EntityUtils
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import cop.utils.render.drawWireFrameBox
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.Optional

/**
 * Static waypoints for every terminal and lever in the four F7/M7 Goldor-P3
 * sections. Lifted from Athen — coordinates are absolute world positions.
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
    desc = "Renders passive waypoints for terminals/levers across the four Goldor sections.",
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

    private val currentSectionOnly by switch(
        "Current section only", true,
        desc = "During Goldor, only show waypoints from the active terminal section. Falls back to all sections if it cannot be determined.",
    )
    private val interactableTerminalsOnly by switch(
        "Interactable terminals only", true,
        desc = "Hide a terminal only when its exact server state says it is no longer interactable. Unknown states stay visible as a safe fallback.",
    )

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
        // --- Goldor section 1 ---
        Node.Terminal(BlockPos(111, 113, 73), DungeonClass.Tank,    1),
        Node.Terminal(BlockPos(111, 119, 79), DungeonClass.Tank,    1),
        Node.Terminal(BlockPos( 89, 112, 92), DungeonClass.Mage,    1),
        Node.Terminal(BlockPos( 89, 122, 101), DungeonClass.Mage,   1),
        Node.Lever   (BlockPos( 94, 124, 113), DungeonClass.Archer, 1),
        Node.Lever   (BlockPos(106, 124, 113), DungeonClass.Archer, 1),

        // --- Goldor section 2 ---
        Node.Terminal(BlockPos( 68, 109, 121), DungeonClass.Tank,    2),
        Node.Terminal(BlockPos( 59, 120, 122), DungeonClass.Mage,    2),
        Node.Terminal(BlockPos( 47, 109, 121), DungeonClass.Berserk, 2),
        Node.Terminal(BlockPos( 39, 108, 143), DungeonClass.Archer,  2),
        Node.Terminal(BlockPos( 40, 124, 122), DungeonClass.Berserk, 2),
        Node.Lever   (BlockPos( 27, 124, 127), DungeonClass.Archer,  2),
        Node.Lever   (BlockPos( 23, 132, 138), DungeonClass.Healer,  2),

        // --- Goldor section 3 ---
        Node.Terminal(BlockPos( -3, 109, 112), DungeonClass.Tank,    3),
        Node.Terminal(BlockPos( -3, 119,  93), DungeonClass.Healer,  3),
        Node.Terminal(BlockPos( 19, 123,  93), DungeonClass.Berserk, 3),
        Node.Terminal(BlockPos( -3, 109,  77), DungeonClass.Archer,  3),
        Node.Lever   (BlockPos( 14, 122,  55), DungeonClass.Archer,  3),
        Node.Lever   (BlockPos(  2, 122,  55), DungeonClass.Archer,  3),

        // --- Goldor section 4 ---
        Node.Terminal(BlockPos( 41, 109,  29), DungeonClass.Tank,    4),
        Node.Terminal(BlockPos( 44, 121,  29), DungeonClass.Archer,  4),
        Node.Terminal(BlockPos( 67, 109,  29), DungeonClass.Berserk, 4),
        Node.Terminal(BlockPos( 72, 115,  48), DungeonClass.Healer,  4),
        Node.Lever   (BlockPos( 86, 128,  46), DungeonClass.Healer,  4),
        Node.Lever   (BlockPos( 84, 121,  34), DungeonClass.Healer,  4),
    )

    private val terminalNodes = nodes.filterIsInstance<Node.Terminal>()

    /**
     * Only server-confirmed terminal states are cached. `Inactive Terminal` is
     * the interactable state used by COP's existing terminal interaction
     * predicate; `Terminal Active` is no longer actionable. A missing entry is
     * deliberately treated as visible so missed packets, unloaded chunks, or
     * a renamed server label can never hide an unfinished terminal.
     */
    private val activatedTerminals = HashSet<BlockPos>(terminalNodes.size)

    private var observedWorld: ClientLevel? = null
    private var wasInP3 = false
    private var scanCooldown = 0

    init {
        on<WorldEvent.Change> { resetTracking(mc.level) }

        on<PacketEvent.ReceivedClient, ClientboundSetEntityDataPacket> {
            if (!syncTrackingScope()) return@on
            val stand = mc.level?.getEntity(packet.id) as? ArmorStand ?: return@on
            val name = packetCustomName(packet) ?: return@on
            observeTerminalState(stand, name)
        }

        on<TickEvent.End> {
            if (!syncTrackingScope()) return@on

            if (scanCooldown > 0) {
                scanCooldown--
                return@on
            }

            scanCooldown = RESCAN_INTERVAL_TICKS
            scanLoadedTerminalStands()
        }

        on<RenderEvent.World> {
            if (Dungeon.deathTick < 0 && !Dungeon.inBoss) return@on
            if (!syncTrackingScope()) return@on
            val ownClass = resolveOwnClass()
            val depth = depthTest
            val currentSection = if (currentSectionOnly) resolveCurrentSection() else null

            for (node in nodes) {
                if (currentSection != null && node.section != currentSection) continue
                if (classFilter && ownClass != null && node.owner != ownClass) continue
                if (
                    interactableTerminalsOnly &&
                    node is Node.Terminal &&
                    node.pos in activatedTerminals
                ) continue

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

    override fun onEnable() {
        resetTracking(mc.level)
        super.onEnable()
    }

    override fun onDisable() {
        super.onDisable()
        resetTracking(null)
    }

    /**
     * Keeps tracking tied to one client world and one Goldor phase. This is a
     * second line of defence for world changes because area-filtered module
     * events may not run after the player has already left the dungeon.
     */
    private fun syncTrackingScope(): Boolean {
        val world = mc.level
        if (world !== observedWorld) resetTracking(world)

        val inP3 = world != null && Dungeon.getF7Phase() == M7Phases.P3
        if (inP3 != wasInP3) {
            activatedTerminals.clear()
            scanCooldown = 0
            wasInP3 = inP3
        }

        return inP3
    }

    private fun resetTracking(world: ClientLevel?) {
        activatedTerminals.clear()
        observedWorld = world
        wasInP3 = false
        scanCooldown = 0
    }

    private fun resolveCurrentSection(): Int? {
        Dungeon.p3Section
            .takeIf { it != P3Section.Unknown }
            ?.let { return it.number }

        val player = mc.player ?: return null
        return Dungeon.getP3Section(player)
            .takeIf { it != P3Section.Unknown }
            ?.number
    }

    /**
     * Reads only the custom-name value carried by this metadata packet. The
     * ordinary client-thread packet event is intentionally pre-vanilla, so
     * reading [ArmorStand.getCustomName] here could otherwise see the old name.
     */
    private fun packetCustomName(packet: ClientboundSetEntityDataPacket): String? {
        val optional = packet.packedItems()
            .firstOrNull { it.serializer() == EntityDataSerializers.OPTIONAL_COMPONENT }
            ?.value() as? Optional<*> ?: return null

        return (optional.orElse(null) as? Component)?.string?.trim()
    }

    /**
     * A small periodic reconciliation handles enabling the module mid-section
     * and any metadata packet received before its armor stand was spawned. It
     * runs twice per second and only while the player is in Goldor.
     */
    private fun scanLoadedTerminalStands() {
        for (stand in EntityUtils.getEntities<ArmorStand>()) {
            val name = stand.customName?.string?.trim() ?: continue
            if (name != INTERACTABLE_NAME && name != ACTIVATED_NAME) continue
            observeTerminalState(stand, name)
        }
    }

    private fun observeTerminalState(stand: ArmorStand, name: String) {
        val node = nearestTerminal(stand) ?: return
        when (name) {
            INTERACTABLE_NAME -> activatedTerminals.remove(node.pos)
            ACTIVATED_NAME -> activatedTerminals.add(node.pos)
        }
    }

    private fun nearestTerminal(stand: ArmorStand): Node.Terminal? {
        var nearest: Node.Terminal? = null
        var nearestDistance = MAX_MATCH_DISTANCE_SQUARED

        for (node in terminalNodes) {
            val distance = squaredDistance(stand, node)
            if (distance > nearestDistance) continue
            nearest = node
            nearestDistance = distance
        }

        return nearest
    }

    private fun squaredDistance(stand: ArmorStand, node: Node.Terminal): Double {
        val dx = stand.x - node.pos.x
        val dy = stand.y - node.pos.y
        val dz = stand.z - node.pos.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun resolveOwnClass(): DungeonClass? = when (ownClassOverride.selected) {
        "Healer"     -> DungeonClass.Healer
        "Mage"       -> DungeonClass.Mage
        "Berserker"  -> DungeonClass.Berserk
        "Archer"     -> DungeonClass.Archer
        "Tank"       -> DungeonClass.Tank
        else /*Auto*/-> Dungeon.currentDungeonPlayer.clazz.takeIf { it != DungeonClass.Unknown }
    }

    private const val INTERACTABLE_NAME = "Inactive Terminal"
    private const val ACTIVATED_NAME = "Terminal Active"
    private const val RESCAN_INTERVAL_TICKS = 10
    private const val MAX_MATCH_DISTANCE_SQUARED = 6.25
}
