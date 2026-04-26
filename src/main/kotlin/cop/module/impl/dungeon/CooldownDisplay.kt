package cop.module.impl.dungeon

import net.minecraft.world.item.ItemStack
import cop.api.events.MouseEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.currentDungeonPlayer
import cop.api.skyblock.dungeon.Dungeon.dungeonTeammates
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.ItemUtils.skyblockUuid
import kotlin.math.roundToInt

/**
 * Port of CritsAddons `CooldownDisplay` (com.github.noamm9.critsaddons.features.impl.critsaddons.CooldownDisplay).
 *
 * Applies a client-side cooldown (vanilla grey overlay) on hotbar items when you
 * left- or right-click them, parsing the cooldown duration out of the item lore
 * ("Cooldown: 6s"). This gives you an at-a-glance reminder on abilities without
 * Hypixel's own widget. Mage class cooldown reduction is applied when active.
 *
 * Exposes [startRightClickCooldown] and [isOnCooldown] for use by sibling modules
 * (M3AutoFF, AutoRCM).
 */
object CooldownDisplay : Module(
    "Cooldown Display",
    area = Island.Dungeon,
    desc = "Shows client-side cooldown overlays on hotbar items based on lore 'Cooldown: Xs' strings."
) {
    private enum class TriggerType { RIGHT, LEFT }

    private data class CooldownSpec(
        val rightTicks: Int?,
        val leftTicks: Int?,
        val hasRightAbility: Boolean,
        val hasLeftAbility: Boolean,
    )

    private val onlyInDungeons by switch("Only in dungeons", true,
        desc = "Restricts cooldown overlays to dungeons only.")
    private val rightClickCooldowns by switch("Right click", true,
        desc = "Apply cooldown overlays for right-click abilities.")
    private val leftClickCooldowns by switch("Left click", true,
        desc = "Apply cooldown overlays for left-click abilities.")
    private val fallbackCooldownSeconds by slider("Fallback cooldown", 0f, 0f, 10f, 0.1f, unit = "s",
        desc = "Used when an ability click-type exists in lore but no explicit cooldown line is found.")
    private val mageCooldownReduction by switch("Mage CD reduction", true,
        desc = "Scales cooldowns using Mage class CD reduction when applicable.")
    private val minRetriggerMs by slider("Min retrigger", 120, 0, 1000, 10, unit = "ms",
        desc = "Prevents duplicate cooldown starts from repeated click events.")

    private val specCache = HashMap<String, CooldownSpec>()
    private var lastTriggerAtMs = 0L

    private val FORMATTING_REGEX = Regex("\\u00A7[0-9A-FK-ORa-fk-or]")
    private val COOLDOWN_REGEX = Regex("(?i)cooldown\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s")
    private const val BASE_MAGE_COOLDOWN_REDUCTION = 0.25
    private const val SOLO_MAGE_COOLDOWN_REDUCTION = 0.50
    private const val MAGE_COOLDOWN_REDUCTION_PER_LEVEL = 0.005
    private const val MAX_MAGE_COOLDOWN_REDUCTION = 0.75

    init {
        on<MouseEvent.Click> {
            if (!state) return@on
            if (mc.screen != null) return@on
            val trigger = when (button) {
                0 -> TriggerType.LEFT
                1 -> TriggerType.RIGHT
                else -> return@on
            }
            tryStartCooldown(mc.player?.mainHandItem, trigger)
        }

        on<WorldEvent.Change> {
            specCache.clear()
            lastTriggerAtMs = 0L
        }
    }

    private fun tryStartCooldown(item: ItemStack?, trigger: TriggerType) {
        if (onlyInDungeons && !inDungeons) return
        if (trigger == TriggerType.RIGHT && !rightClickCooldowns) return
        if (trigger == TriggerType.LEFT && !leftClickCooldowns) return

        val held = item ?: return
        if (held.isEmpty) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < minRetriggerMs.toLong()) return

        if (startClientCooldown(held, trigger)) {
            lastTriggerAtMs = now
        }
    }

    fun isOnCooldown(item: ItemStack): Boolean {
        if (item.isEmpty) return false
        return mc.player?.cooldowns?.isOnCooldown(item) == true
    }

    /** Called by sibling modules (M3AutoFF, AutoRCM) after synthesised right-clicks. */
    fun startRightClickCooldown(item: ItemStack?): Boolean {
        val stack = item ?: return false
        return startClientCooldown(stack, TriggerType.RIGHT)
    }

    private fun startClientCooldown(item: ItemStack, trigger: TriggerType): Boolean {
        if (item.isEmpty) return false
        val player = mc.player ?: return false
        val cooldowns = player.cooldowns
        if (cooldowns.isOnCooldown(item)) return false

        val ticks = resolveCooldownTicks(item, trigger) ?: return false
        if (ticks <= 0) return false

        cooldowns.addCooldown(item, ticks)
        return true
    }

    private fun resolveCooldownTicks(item: ItemStack, trigger: TriggerType): Int? {
        val key = cacheKey(item)
        val spec = specCache.getOrPut(key) { parseSpec(item) }

        val ticks = when (trigger) {
            TriggerType.RIGHT -> spec.rightTicks ?: fallbackTicksIfApplicable(spec.hasRightAbility)
            TriggerType.LEFT -> spec.leftTicks ?: fallbackTicksIfApplicable(spec.hasLeftAbility)
        } ?: return null

        return scaleTicks(ticks)
    }

    private fun fallbackTicksIfApplicable(hasAbilityType: Boolean): Int? {
        if (!hasAbilityType) return null
        val ticks = (fallbackCooldownSeconds.toDouble() * 20.0).roundToInt()
        return ticks.takeIf { it > 0 }
    }

    private fun scaleTicks(ticks: Int): Int =
        (ticks * mageScale()).roundToInt().coerceAtLeast(1)

    private fun mageScale(): Double {
        if (!mageCooldownReduction || !inDungeons) return 1.0

        val self = currentDungeonPlayer
        if (self.clazz != DungeonClass.Mage) return 1.0

        val base = if (isOnlyMage()) SOLO_MAGE_COOLDOWN_REDUCTION else BASE_MAGE_COOLDOWN_REDUCTION
        val reduction = (base + self.clazzLvl * MAGE_COOLDOWN_REDUCTION_PER_LEVEL)
            .coerceIn(0.0, MAX_MAGE_COOLDOWN_REDUCTION)
        return (1.0 - reduction).coerceIn(0.1, 1.0)
    }

    private fun isOnlyMage(): Boolean {
        val teammates = dungeonTeammates
        if (teammates.isEmpty()) return true
        val mages = teammates.filter { it.clazz == DungeonClass.Mage }
            .map { it.name.lowercase() }
            .toMutableSet()
        mages.add(currentDungeonPlayer.name.lowercase())
        return mages.size <= 1
    }

    private fun parseSpec(item: ItemStack): CooldownSpec {
        var rightTicks: Int? = null
        var leftTicks: Int? = null
        var hasRightAbility = false
        var hasLeftAbility = false
        var lastMask = 0

        val lines = item.lore ?: return CooldownSpec(null, null, false, false)

        for (line in lines) {
            val stripped = stripFormatting(line)
            val upper = stripped.uppercase()

            val abilityMask = parseAbilityMask(upper)
            if (abilityMask != 0) {
                lastMask = abilityMask
                if ((abilityMask and 1) != 0) hasRightAbility = true
                if ((abilityMask and 2) != 0) hasLeftAbility = true
            }

            val cooldownTicks = parseCooldownTicks(stripped) ?: continue
            when {
                (lastMask and 1) != 0 && rightTicks == null -> rightTicks = cooldownTicks
                (lastMask and 2) != 0 && leftTicks == null -> leftTicks = cooldownTicks
                hasRightAbility && !hasLeftAbility && rightTicks == null -> rightTicks = cooldownTicks
                hasLeftAbility && !hasRightAbility && leftTicks == null -> leftTicks = cooldownTicks
                rightTicks == null -> rightTicks = cooldownTicks
                leftTicks == null -> leftTicks = cooldownTicks
            }
        }

        return CooldownSpec(rightTicks, leftTicks, hasRightAbility, hasLeftAbility)
    }

    private fun parseAbilityMask(upperLine: String): Int {
        if (!upperLine.contains("ABILITY")) return 0
        var mask = 0
        if (upperLine.contains("RIGHT CLICK")) mask = mask or 1
        if (upperLine.contains("LEFT CLICK")) mask = mask or 2
        return mask
    }

    private fun parseCooldownTicks(line: String): Int? {
        val match = COOLDOWN_REGEX.find(line) ?: return null
        val seconds = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        return (seconds * 20.0).roundToInt().coerceAtLeast(1)
    }

    private fun cacheKey(item: ItemStack): String {
        val uuid = item.skyblockUuid?.trim().orEmpty()
        if (uuid.isNotEmpty()) return "uuid:$uuid"

        val id = item.skyblockId?.trim().orEmpty()
        if (id.isNotEmpty()) return "id:$id"

        return "item:${item.item}#${stripFormatting(item.hoverName.string)}"
    }

    private fun stripFormatting(text: String): String =
        text.replace(FORMATTING_REGEX, "")
}
