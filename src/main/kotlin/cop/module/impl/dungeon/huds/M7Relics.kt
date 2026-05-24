package cop.module.impl.dungeon.huds

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import cop.api.animations.Animation
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.ChatEvent
import cop.api.events.PacketEvent
import cop.api.events.RenderEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.invoke
import cop.api.skyblock.dungeon.M7Phases
import cop.config.PersonalBest
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.modMessage
import cop.utils.EntityUtils.getEntities
import cop.utils.StringUtils.noControlCodes
import cop.utils.getDirection
import cop.utils.render.drawFilledBox
import cop.utils.render.drawTracer
import cop.utils.render.drawWireFrameBox
import cop.utils.skyblock.player.RotationUtils.rotateSmoothly
import cop.utils.skyblock.player.interact.AuraAction
import cop.utils.skyblock.player.interact.AuraManager
import cop.utils.ui.textPair

/**
 * Port of NoammAddons M7Relics (com.github.noamm9.features.impl.floor7.M7Relics) — full feature set.
 *
 * Relic visual helpers: box highlights the cauldron that matches your held relic.
 * Spawn timer: counts down the ~42-tick window after "All this, for nothing…".
 * Place timer: per-relic placement time; saves & shows PersonalBest once all 5 are placed.
 * Relic Look: auto-rotates your view to the correct cauldron on red/orange (the hard ones).
 * Block Wrong Relic: cancels right-clicks that would place at the wrong cauldron.
 * Relic Aura: auto-interacts with the relic armor stand when it spawns in range.
 */
object M7Relics : Module(
    "M7 Relics",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Utilities for Necron's corrupted relics in P5."
) {
    private val relicBox by switch("Relic Box", true,
        desc = "Draws a box & tracer on the cauldron that matches your held relic.")
    private val outline by switch("Outline", true).childOf(::relicBox)
    private val thickness by slider("Thickness", 2.0, 0.5, 6.0, 0.5).childOf(::outline)

    private val spawnTimer by switch("Spawn Timer", true,
        desc = "Shows a countdown until the relics spawn.")
    private val placeTimer by switch("Place Timer", true,
        desc = "Reports the placement time for each relic.")

    // --- Cheat settings ---
    private val relicLook by switch("Relic Look",
        desc = "Automatically rotate to the relic cauldron after you pick it up (red/orange only).")
    private val relicLookTime by slider("Relic Look Time", 150.0, 10.0, 300.0, 1.0,
        desc = "How fast should the auto rotate run (in milliseconds).", unit = "ms").childOf(::relicLook)
    private val relicLookStyle by selector("Rotate style", Animation.Style.EaseOutQuint,
        desc = "Easing curve for the auto rotate.").childOf(::relicLook)

    private val blockWrongRelic by switch("Block Wrong Relic",
        desc = "Prevents right-click placement at the wrong cauldron.")

    private val relicAura by switch("Relic Aura",
        desc = "Automatically pick up the relic when it spawns in range.")

    private enum class WitherRelic(val coords: Pair<Int, Int>, val colour: Colour) {
        GREEN(29 to 57, Colour.GREEN),
        PURPLE(93 to 96, Colour.PURPLE),
        BLUE(29 to 96, Colour.CYAN),
        RED(93 to 57, Colour.RED),
        ORANGE(61 to 25, Colour.ORANGE);

        val cauldronPos: BlockPos get() = BlockPos(coords.first, 128, coords.second)
        val cauldronCentre: Vec3 get() = Vec3(coords.first + 0.5, 128.5, coords.second + 0.5)
        val aabb: AABB get() = AABB(
            coords.first.toDouble(), 128.0, coords.second.toDouble(),
            coords.first + 1.0, 129.0, coords.second + 1.0
        )

        companion object {
            fun fromName(name: String?): WitherRelic? {
                if (name.isNullOrBlank()) return null
                val cleaned = name.noControlCodes.lowercase()
                return entries.find { cleaned.contains(it.name.lowercase()) }
            }
        }
    }

    private val relicPickUpRegex = Regex("^(\\w{3,16}) picked the Corrupted (\\w{3,6}) Relic!$")

    private data class RelicEntry(
        val relic: WitherRelic,
        val player: String,
        val pickupTimeMs: Long,
        var placeTimeSeconds: Double = 0.0,
        var isPlaced: Boolean = false,
        var isPB: Boolean = false
    )

    private val relicTimes = mutableListOf<RelicEntry>()
    private var spawnCountdownTicks = 0
    private var p5StartMs = 0L
    private var lastAuraClick = 0L

    init {
        on<WorldEvent.Change> {
            relicTimes.clear()
            spawnCountdownTicks = 0
            p5StartMs = 0L
            lastAuraClick = 0L
        }

        on<ChatEvent.Receive> {
            val msg = message.noControlCodes
            if (!Dungeon.inBoss) return@on

            when {
                msg == "[BOSS] Necron: All this, for nothing..." -> {
                    p5StartMs = System.currentTimeMillis()
                    if (spawnTimer) spawnCountdownTicks = 42
                }
                placeTimer -> relicPickUpRegex.find(msg)?.let { match ->
                    val (p, relicName) = match.destructured
                    val relic = WitherRelic.fromName(relicName) ?: return@let
                    relicTimes.add(RelicEntry(relic, p, System.currentTimeMillis()))

                    // Auto-rotate: only for the tricky red/orange cauldrons
                    if (relicLook && p == mc.user.name && (relic == WitherRelic.RED || relic == WitherRelic.ORANGE)) {
                        val player = mc.player ?: return@let
                        val dir = getDirection(relic.cauldronCentre)
                        player.rotateSmoothly(dir, duration = relicLookTime.toFloat(), style = relicLookStyle.selected)
                    }
                }
            }
        }

        // Block Wrong Relic: cancel right-click placements at any cauldron that isn't the match
        on<PacketEvent.Sent, ServerboundUseItemOnPacket> {
            if (!blockWrongRelic) return@on
            if (Dungeon.getF7Phase() != M7Phases.P5) return@on
            val held = mc.player?.mainHandItem ?: return@on
            val relic = WitherRelic.fromName(held.hoverName.string) ?: return@on
            val hit = packet.hitResult.blockPos
            // If the block is a cauldron-height block at one of the 5 known coords and it's not our target → cancel
            val targets = WitherRelic.entries.map { it.cauldronPos }
            val isAtAnyCauldron = targets.any { it.x == hit.x && it.z == hit.z && hit.y in 127..128 }
            if (!isAtAnyCauldron) return@on
            if (relic.cauldronPos.x == hit.x && relic.cauldronPos.z == hit.z) return@on
            cancel()
        }

        on<TickEvent.Server> {
            if (spawnCountdownTicks > 0) spawnCountdownTicks--

            if (Dungeon.getF7Phase() != M7Phases.P5) return@on

            // --- Place timer & PB ---
            if (placeTimer && relicTimes.isNotEmpty()) {
                val active = relicTimes.filter { !it.isPlaced }
                if (active.isNotEmpty()) {
                    val relicStands = getEntities<ArmorStand>().filter {
                        it.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")
                    }

                    for (stand in relicStands) for (entry in active) {
                        if (!atCauldron(stand.position(), entry.relic)) continue
                        val seconds = (System.currentTimeMillis() - p5StartMs) / 1000.0
                        entry.placeTimeSeconds = (seconds * 100).toInt() / 100.0
                        entry.isPlaced = true

                        if (entry.player == mc.user.name) {
                            entry.isPB = PersonalBest.checkAndSetPB(
                                key = "M7_relic_${entry.relic.name}",
                                value = entry.placeTimeSeconds,
                                lowerIsBetter = true
                            )
                        }
                    }

                    if (relicTimes.size == 5 && relicTimes.all { it.isPlaced }) {
                        relicTimes.sortedBy { it.placeTimeSeconds }.forEach { entry ->
                            val pretty = entry.relic.name.lowercase().replaceFirstChar { it.uppercase() }
                            val pbSuffix = if (entry.isPB) " §d§l(PB)" else ""
                            modMessage("§b$pretty §aRelic placed in §e${entry.placeTimeSeconds}s§a.$pbSuffix")
                        }
                        relicTimes.clear()
                    }
                }
            }

            // --- Relic Aura: auto-interact with a floating relic armor stand ---
            if (relicAura) {
                val now = System.currentTimeMillis()
                if (now - lastAuraClick >= 200) {
                    val heldName = mc.player?.inventory?.getItem(8)?.hoverName?.string ?: ""
                    // only try to pick up if we don't already hold a relic
                    if (!heldName.contains("Relic")) {
                        val player = mc.player
                        if (player != null) {
                            val candidate = getEntities<ArmorStand>(3.0) { stand ->
                                val isRelic = stand.getItemBySlot(EquipmentSlot.HEAD).hoverName.string.contains("Relic")
                                if (!isRelic) return@getEntities false
                                // skip stands already sitting on a cauldron — those are already placed
                                WitherRelic.entries.none { atCauldron(stand.position(), it) }
                            }.firstOrNull()
                            if (candidate != null) {
                                AuraManager.interactEntity(candidate, AuraAction.INTERACT)
                                lastAuraClick = now
                            }
                        }
                    }
                }
            }
        }

        on<RenderEvent.World> {
            if (!relicBox || Dungeon.getF7Phase() != M7Phases.P5) return@on
            val heldName = mc.player?.inventory?.getItem(8)?.hoverName?.string ?: return@on
            val relic = WitherRelic.fromName(heldName) ?: return@on

            ctx.drawFilledBox(relic.aabb, relic.colour.withAlpha(60), depth = true)
            if (outline) ctx.drawWireFrameBox(relic.aabb, relic.colour.withAlpha(255), thickness.toFloat(), depth = true)
            ctx.drawTracer(relic.cauldronCentre, relic.colour.withAlpha(255), 2f, depth = true)
        }

        textHud(
            name = "Relic Spawn Timer",
            colour = Colour.WHITE,
            toggleable = false
        ) {
            visibleIf { this@M7Relics.enabled && spawnTimer && spawnCountdownTicks > 0 }
            textPair(
                string = "Relics:",
                supplier = { "%.2fs".format(spawnCountdownTicks / 20.0) },
                labelColour = colour,
                shadow = shadow,
                font = font
            )
        }.setting()
    }

    private fun atCauldron(pos: Vec3, relic: WitherRelic): Boolean {
        val dx = pos.x - relic.coords.first
        val dz = pos.z - relic.coords.second
        return dx * dx + dz * dz < 1.5 * 1.5
    }
}
