package cop.module.impl.misc.economy

import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.module.Module
import cop.utils.StringUtils.noControlCodes
import cop.utils.romanToInt
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INTERACTION
import cop.utils.skyblock.player.AutomationCoordinator.Channel.INVENTORY
import cop.utils.skyblock.player.ContainerUtils.clickSlot
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack

/** Conservative menu-only Chocolate Factory automation. */
object ChocolateFactory : Module(
    "Chocolate Factory",
    desc = "Clicks the factory, claims stray rabbits and buys efficient upgrades while its menu is open.",
) {
    private val clickFactory by switch("Click factory", true)
    private val autoTimeTower by switch(
        "Auto Time Tower",
        true,
        desc = "Activates an inactive Time Tower when a charge is available.",
    )
    private val claimStrays by switch(
        "Claim stray rabbits",
        true,
        desc = "Claims CLICK ME and Golden Rabbit slots in the factory menu.",
    )
    private val autoUpgrade by switch(
        "Auto upgrade",
        false,
        desc = "Buys the affordable upgrade with the shortest estimated payback time.",
    )
    private val actionDelay by slider("Action delay", 160L, 75L, 1_500L, 5L, unit = "ms")
    private val upgradeDelay by slider("Upgrade delay", 650L, 300L, 3_000L, 50L, unit = "ms")

    private var lastActionAt = 0L
    private var lastUpgradeAt = 0L

    init {
        on<TickEvent.End> {
            val screen = mc.screen as? AbstractContainerScreen<*> ?: return@on
            if (!screen.title.string.noControlCodes.equals("Chocolate Factory", ignoreCase = true)) return@on

            val now = System.currentTimeMillis()
            if (now - lastActionAt >= actionDelay && tickFactoryActions(screen)) {
                lastActionAt = now
                return@on
            }
            if (now - lastUpgradeAt >= upgradeDelay && tickUpgrade(screen)) lastUpgradeAt = now
        }

        on<WorldEvent.Change> { reset() }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun tickFactoryActions(screen: AbstractContainerScreen<*>): Boolean {
        val menu = screen.menu
        if (!AutomationCoordinator.acquire(OWNER, actionDelay, INVENTORY, INTERACTION)) return false

        if (autoTimeTower && shouldActivateTimeTower(menu.slots.getOrNull(TIME_TOWER_SLOT)?.item)) {
            player.clickSlot(TIME_TOWER_SLOT, menu.containerId)
            return true
        }

        if (claimStrays) {
            val stray = topSlots(menu).firstOrNull { slot ->
                !slot.item.isEmpty && slot.item.hoverName.string.noControlCodes
                    .let { it.equals("CLICK ME!", true) || it.contains("Golden Rabbit", true) }
            }
            if (stray != null) {
                val slotId = menu.slots.indexOf(stray)
                if (slotId < 0) {
                    AutomationCoordinator.release(OWNER)
                    return false
                }
                player.clickSlot(slotId, menu.containerId)
                return true
            }
        }

        if (clickFactory && isFactoryButton(menu.slots.getOrNull(FACTORY_SLOT)?.item)) {
            player.clickSlot(FACTORY_SLOT, menu.containerId, button = 1)
            return true
        }

        AutomationCoordinator.release(OWNER)
        return false
    }

    private fun tickUpgrade(screen: AbstractContainerScreen<*>): Boolean {
        if (!autoUpgrade) return false
        val menu = screen.menu
        val chocolate = parseChocolate(menu.slots.getOrNull(FACTORY_SLOT)?.item) ?: return false
        val candidate = bestUpgrade(menu) ?: return false
        if (candidate.slot !in topSlots(menu).indices) return false
        if (chocolate < candidate.cost) return false
        if (!AutomationCoordinator.acquire(OWNER, upgradeDelay, INVENTORY, INTERACTION)) return false

        player.clickSlot(candidate.slot, menu.containerId, button = 2)
        return true
    }

    private fun bestUpgrade(menu: AbstractContainerMenu): UpgradeCandidate? {
        val cps = parseChocolatePerSecond(menu.slots.getOrNull(FACTORY_SLOT)?.item) ?: return null
        val multiplier = parseTotalMultiplier(menu.slots.getOrNull(MULTIPLIER_SLOT)?.item) ?: return null
        if (cps <= 0.0 || multiplier <= 0.0) return null

        val tower = menu.slots.getOrNull(TIME_TOWER_SLOT)?.item
        val towerLevel = parseUpgradeTier(tower)
        val towerActive = isTimeTowerActive(tower)
        val rawCps = cps / multiplier
        val baseMultiplier = (multiplier - if (towerActive) towerLevel * 0.1 else 0.0).coerceAtLeast(0.01)
        val currentAverage = rawCps * baseMultiplier

        return buildList {
            RABBIT_GAINS.forEach { (slot, gain) ->
                val cost = parseUpgradeCost(menu.slots.getOrNull(slot)?.item) ?: return@forEach
                val gainPerSecond = gain * baseMultiplier
                if (gainPerSecond > 0.0) add(UpgradeCandidate(slot, cost, cost / gainPerSecond))
            }

            parseUpgradeCost(tower)?.let { cost ->
                val gainPerSecond = rawCps * 0.1 / 8.0
                if (gainPerSecond > 0.0) add(UpgradeCandidate(TIME_TOWER_SLOT, cost, cost / gainPerSecond))
            }

            val coach = menu.slots.getOrNull(COACH_SLOT)?.item
            parseUpgradeCost(coach)?.let { cost ->
                val upgraded = rawCps * (baseMultiplier + 0.01)
                val gainPerSecond = upgraded - currentAverage
                if (gainPerSecond > 0.0) add(UpgradeCandidate(COACH_SLOT, cost, cost / gainPerSecond))
            }
        }.minByOrNull { it.paybackSeconds }
    }

    private fun parseChocolate(item: ItemStack?): Long? {
        val raw = item?.hoverName?.string?.noControlCodes ?: return null
        val token = NUMBER.find(raw)?.value ?: return null
        return parseCompactNumber(token)?.toLong()
    }

    private fun parseUpgradeCost(item: ItemStack?): Long? {
        val lore = item?.lore.orEmpty().map { it.noControlCodes }
        val costIndex = lore.indexOfFirst { it.contains("Cost", ignoreCase = true) }
        if (costIndex < 0) return null
        val candidates = lore.drop(costIndex).take(3)
        val line = candidates.firstOrNull { it.contains("Chocolate", ignoreCase = true) && NUMBER.containsMatchIn(it) }
            ?: return null
        return NUMBER.find(line)?.value?.let(::parseCompactNumber)?.toLong()
    }

    private fun parseChocolatePerSecond(item: ItemStack?): Double? = item?.lore.orEmpty()
        .asSequence()
        .map { it.noControlCodes }
        .firstNotNullOfOrNull { line ->
            CPS.find(line)?.groupValues?.getOrNull(1)?.let(::parseCompactNumber)
        }

    private fun parseCompactNumber(raw: String): Double? {
        val clean = raw.replace(",", "").trim().uppercase()
        if (clean.isEmpty()) return null
        val multiplier = when (clean.last()) {
            'K' -> 1_000.0
            'M' -> 1_000_000.0
            'B' -> 1_000_000_000.0
            'T' -> 1_000_000_000_000.0
            else -> 1.0
        }
        val number = if (multiplier == 1.0) clean else clean.dropLast(1)
        return number.toDoubleOrNull()?.times(multiplier)
    }

    private fun topSlots(menu: AbstractContainerMenu) = menu.slots.takeWhile { it.container !is Inventory }

    private fun isFactoryButton(item: ItemStack?): Boolean {
        if (item == null || item.isEmpty) return false
        val name = item.hoverName.string.noControlCodes
        return name.contains("Chocolate", true) && item.lore.orEmpty().any {
            it.noControlCodes.contains("per second", true)
        }
    }

    private fun parseTotalMultiplier(item: ItemStack?): Double? = item?.lore.orEmpty()
        .asSequence()
        .map { it.noControlCodes }
        .firstNotNullOfOrNull { line ->
            MULTIPLIER.find(line)?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
        }

    private fun parseUpgradeTier(item: ItemStack?): Int {
        val suffix = item?.hoverName?.string?.noControlCodes?.substringAfterLast(' ', "").orEmpty()
        return runCatching { romanToInt(suffix) }.getOrDefault(0)
    }

    private fun isTimeTowerActive(item: ItemStack?): Boolean = item?.lore.orEmpty()
        .any { it.noControlCodes.contains("Status: ACTIVE", ignoreCase = true) }

    private fun shouldActivateTimeTower(item: ItemStack?): Boolean {
        if (item == null || item.isEmpty || !item.hoverName.string.noControlCodes.contains("Time Tower", true)) return false
        if (isTimeTowerActive(item)) return false
        val charges = item.lore.orEmpty().asSequence().map { it.noControlCodes }
            .firstNotNullOfOrNull { CHARGES.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        return charges != null && charges > 0
    }

    private fun reset() {
        lastActionAt = 0L
        lastUpgradeAt = 0L
        AutomationCoordinator.release(OWNER)
    }

    private data class UpgradeCandidate(val slot: Int, val cost: Long, val paybackSeconds: Double)

    private const val OWNER = "Chocolate Factory"
    private const val FACTORY_SLOT = 13
    private const val TIME_TOWER_SLOT = 39
    private const val COACH_SLOT = 42
    private const val MULTIPLIER_SLOT = 45
    private val RABBIT_GAINS = mapOf(28 to 1.0, 29 to 2.0, 30 to 3.0, 31 to 4.0, 32 to 5.0, 33 to 6.0, 34 to 7.0)
    private val NUMBER = Regex("\\d[\\d,]*(?:\\.\\d+)?(?:[kKmMbBtT])?")
    private val CPS = Regex("(\\d[\\d,]*(?:\\.\\d+)?(?:[kKmMbBtT])?)\\s+per second", RegexOption.IGNORE_CASE)
    private val MULTIPLIER = Regex("Total Multiplier:\\s*([\\d.]+)x", RegexOption.IGNORE_CASE)
    private val CHARGES = Regex("Charges:\\s*(\\d+)\\s*/\\s*\\d+", RegexOption.IGNORE_CASE)
}
