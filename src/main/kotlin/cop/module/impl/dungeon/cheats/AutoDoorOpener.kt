package cop.module.impl.dungeon.cheats

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.odonscanning.ScanUtils
import cop.api.skyblock.dungeon.odonscanning.tiles.DoorType
import cop.api.skyblock.dungeon.odonscanning.tiles.OdonDoor
import cop.api.skyblock.invoke
import cop.module.Module
import cop.module.settings.UIComponent.Companion.visibleIf
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.interact.AuraManager
import kotlin.math.abs

/** Opens only scanner-confirmed, still-locked Wither and Blood doors. */
object AutoDoorOpener : Module(
    "Auto Door Opener",
    area = Island.Dungeon(inClear = true),
    desc = "Automatically opens nearby scanned Wither and Blood doors."
) {
    private val mode by selector(
        "Mode",
        "Triggerbot",
        listOf("Triggerbot", "Aura"),
        desc = "Triggerbot opens the looked-at door; Aura opens the closest door."
    )
    private val auraRange by slider(
        "Aura range",
        5.0,
        2.0,
        6.0,
        0.1,
        unit = "m",
        desc = "Maximum eye-to-door distance in Aura mode."
    ).visibleIf { mode.selected == "Aura" }
    private val retryDelay by slider(
        "Retry delay",
        500,
        100,
        2_000,
        50,
        unit = "ms",
        desc = "Minimum delay between door interactions."
    )
    private val swingHand by switch("Swing hand", true, desc = "Shows a hand swing when opening a door.")
    private val allowMenus by switch("Allow in menus", desc = "Allows the Aura while a menu is open.")

    private var lastInteractionAt = 0L

    init {
        on<WorldEvent.Change> {
            lastInteractionAt = 0L
            AutomationCoordinator.release(OWNER)
        }

        on<TickEvent.End> {
            if (!Dungeon.inClear || Dungeon.isDead || mc.player == null || mc.level == null) return@on
            if (mc.screen != null && !allowMenus) return@on

            val now = System.currentTimeMillis()
            if (now - lastInteractionAt < retryDelay.toLong()) return@on

            val doors = ScanUtils.scannedDoors.asSequence()
                .filter { it.locked && it.type in OPENABLE_TYPES }
                .filter(::isStillClosed)
                .toList()

            val target = when (mode.selected) {
                "Aura" -> closestDoor(doors)
                else -> lookedAtDoor(doors)
            } ?: return@on

            if (!AutomationCoordinator.acquire(OWNER, 350L, Channel.INTERACTION)) return@on
            AuraManager.interactBlock(target, force = true)
            if (swingHand) player.swing(InteractionHand.MAIN_HAND)
            lastInteractionAt = now
        }
    }

    override fun onDisable() {
        AutomationCoordinator.release(OWNER)
        super.onDisable()
    }

    private fun closestDoor(doors: List<OdonDoor>): BlockPos? {
        val eye = player.eyePosition
        val maxDistanceSq = auraRange * auraRange
        return doors.asSequence()
            .map(::interactionPos)
            .filter { eye.distanceToSqr(it.center) <= maxDistanceSq }
            .minByOrNull { eye.distanceToSqr(it.center) }
    }

    private fun lookedAtDoor(doors: List<OdonDoor>): BlockPos? {
        val hit = (mc.hitResult as? BlockHitResult)
            ?.takeIf { it.type == HitResult.Type.BLOCK } ?: return null
        val hitPos = hit.blockPos
        if (hitPos.y !in 69..73) return null

        val door = doors.firstOrNull { candidate ->
            abs(hitPos.x - candidate.pos.x) <= 2 &&
                abs(hitPos.z - candidate.pos.z) <= 2 &&
                when (candidate.type) {
                    DoorType.WITHER -> level.getBlockState(hitPos).block == Blocks.COAL_BLOCK
                    DoorType.BLOOD -> level.getBlockState(hitPos).block == Blocks.RED_TERRACOTTA
                    else -> false
                }
        } ?: return null

        return hitPos.takeIf { isStillClosed(door) }
    }

    private fun interactionPos(door: OdonDoor) = BlockPos(door.pos.x, 69, door.pos.z)

    private fun isStillClosed(door: OdonDoor): Boolean {
        val block = mc.level?.getBlockState(interactionPos(door))?.block ?: return false
        return when (door.type) {
            DoorType.WITHER -> block == Blocks.COAL_BLOCK
            DoorType.BLOOD -> block == Blocks.RED_TERRACOTTA
            else -> false
        }
    }

    private const val OWNER = "AutoDoorOpener"
    private val OPENABLE_TYPES = setOf(DoorType.WITHER, DoorType.BLOOD)
}
