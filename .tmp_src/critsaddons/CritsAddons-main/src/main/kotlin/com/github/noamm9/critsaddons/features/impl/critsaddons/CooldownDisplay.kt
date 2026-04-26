package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.impl.PlayerInteractEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.dungeons.DungeonListener
import com.github.noamm9.utils.dungeons.enums.DungeonClass
import com.github.noamm9.utils.items.ItemUtils.itemUUID
import com.github.noamm9.utils.items.ItemUtils.lore
import com.github.noamm9.utils.items.ItemUtils.skyblockId
import com.github.noamm9.utils.location.LocationUtils
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

object CooldownDisplay : Feature(
    name = "Cooldown Display",
    description = "Shows client-side vanilla-style item cooldown overlays for item abilities."
) {
    private enum class TriggerType { RIGHT, LEFT }

    private data class CooldownSpec(
        val rightTicks: Int?,
        val leftTicks: Int?,
        val hasRightAbility: Boolean,
        val hasLeftAbility: Boolean
    )

    private val settingsSection = "settings"

    private val onlyInDungeons by ToggleSetting("Only In Dungeons", true)
        .section(settingsSection)
        .withDescription("Only show client cooldown overlays while in dungeons.")

    private val rightClickCooldowns by ToggleSetting("Right Click Cooldowns", true)
        .section(settingsSection)
        .withDescription("Apply cooldown overlays for right-click item abilities.")

    private val leftClickCooldowns by ToggleSetting("Left Click Cooldowns", true)
        .section(settingsSection)
        .withDescription("Apply cooldown overlays for left-click item abilities.")

    private val fallbackCooldownSeconds by SliderSetting("Fallback Cooldown (s)", 0.0, 0.0, 10.0, 0.1)
        .section(settingsSection)
        .withDescription("Used when an ability click type exists but no explicit lore cooldown is found.")

    private val mageCooldownReduction by ToggleSetting("Mage CD Reduction", true)
        .section(settingsSection)
        .withDescription("Automatically shortens ability cooldowns while you are playing Mage in dungeons.")

    private val minRetriggerMs by SliderSetting("Min Re-Trigger Delay (ms)", 120, 0, 1000, 10)
        .section(settingsSection)
        .withDescription("Prevents duplicate cooldown starts from repeated click events.")

    private val specCache = HashMap<String, CooldownSpec>()
    private var lastTriggerAtMs = 0L

    override fun init() {
        register<PlayerInteractEvent.RIGHT_CLICK.AIR> {
            tryStartCooldown(event.item, TriggerType.RIGHT)
        }
        register<PlayerInteractEvent.RIGHT_CLICK.BLOCK> {
            tryStartCooldown(event.item, TriggerType.RIGHT)
        }
        register<PlayerInteractEvent.RIGHT_CLICK.ENTITY> {
            tryStartCooldown(event.item, TriggerType.RIGHT)
        }
        register<PlayerInteractEvent.LEFT_CLICK.AIR> {
            tryStartCooldown(event.item, TriggerType.LEFT)
        }
        register<PlayerInteractEvent.LEFT_CLICK.ENTITY> {
            tryStartCooldown(event.item, TriggerType.LEFT)
        }
        register<WorldChangeEvent> {
            specCache.clear()
            lastTriggerAtMs = 0L
        }
    }

    private fun tryStartCooldown(item: ItemStack?, trigger: TriggerType) {
        if (!enabled) return
        if (onlyInDungeons.value && !LocationUtils.inDungeon) return
        if (mc.screen != null) return
        if (trigger == TriggerType.RIGHT && !rightClickCooldowns.value) return
        if (trigger == TriggerType.LEFT && !leftClickCooldowns.value) return

        val player = mc.player ?: return
        val held = item ?: player.mainHandItem ?: return
        if (held.isEmpty) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < minRetriggerMs.value.toLong()) return

        if (startClientCooldown(held, trigger)) {
            lastTriggerAtMs = now
        }
    }

    fun rightClickCooldownTicks(item: ItemStack): Int? {
        return resolveCooldownTicks(item, TriggerType.RIGHT)
    }

    fun isOnCooldown(item: ItemStack): Boolean {
        if (item.isEmpty) return false
        return mc.player?.cooldowns?.isOnCooldown(item) == true
    }

    fun startRightClickCooldown(item: ItemStack?): Boolean {
        return startClientCooldown(item ?: return false, TriggerType.RIGHT)
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
        val ticks = (fallbackCooldownSeconds.value * 20.0).roundToInt()
        return ticks.takeIf { it > 0 }
    }

    private fun scaleTicks(ticks: Int): Int {
        return (ticks * mageScale()).roundToInt().coerceAtLeast(1)
    }

    private fun mageScale(): Double {
        if (!mageCooldownReduction.value || !LocationUtils.inDungeon) return 1.0

        val self = DungeonListener.thePlayer ?: return 1.0
        if (self.clazz != DungeonClass.Mage) return 1.0

        val reduction = (baseMageCooldownReduction() + self.clazzLvl * MAGE_COOLDOWN_REDUCTION_PER_LEVEL)
            .coerceIn(0.0, MAX_MAGE_COOLDOWN_REDUCTION)
        return (1.0 - reduction).coerceIn(0.1, 1.0)
    }

    private fun baseMageCooldownReduction(): Double {
        return if (isOnlyMage()) SOLO_MAGE_COOLDOWN_REDUCTION
        else BASE_MAGE_COOLDOWN_REDUCTION
    }

    private fun isOnlyMage(): Boolean {
        val self = DungeonListener.thePlayer ?: return false
        val teammates = DungeonListener.dungeonTeammates
        if (teammates.isEmpty()) return true

        val mages = teammates
            .filter { it.clazz == DungeonClass.Mage }
            .map { it.name.lowercase() }
            .toMutableSet()
        mages.add(self.name.lowercase())

        return mages.size <= 1
    }

    private fun parseSpec(item: ItemStack): CooldownSpec {
        var rightTicks: Int? = null
        var leftTicks: Int? = null
        var hasRightAbility = false
        var hasLeftAbility = false
        var lastMask = 0

        for (line in item.lore) {
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

        return CooldownSpec(
            rightTicks = rightTicks,
            leftTicks = leftTicks,
            hasRightAbility = hasRightAbility,
            hasLeftAbility = hasLeftAbility
        )
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
        val uuid = item.itemUUID.trim()
        if (uuid.isNotEmpty()) return "uuid:$uuid"

        val id = item.skyblockId.trim()
        if (id.isNotEmpty()) return "id:$id"

        return "item:${item.item}#${stripFormatting(item.hoverName.string)}"
    }

    private fun stripFormatting(text: String): String {
        return text.replace(FORMATTING_REGEX, "")
    }

    private val FORMATTING_REGEX = Regex("\\u00A7[0-9A-FK-ORa-fk-or]")
    private val COOLDOWN_REGEX = Regex("(?i)cooldown\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s")
    private const val BASE_MAGE_COOLDOWN_REDUCTION = 0.25
    private const val SOLO_MAGE_COOLDOWN_REDUCTION = 0.50
    private const val MAGE_COOLDOWN_REDUCTION_PER_LEVEL = 0.005
    private const val MAX_MAGE_COOLDOWN_REDUCTION = 0.75
}
