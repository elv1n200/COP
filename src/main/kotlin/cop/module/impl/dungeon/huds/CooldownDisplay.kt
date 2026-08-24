package cop.module.impl.dungeon.huds

import cop.api.abobaui.dsl.px
import cop.api.abobaui.elements.impl.Text.Companion.shadow
import cop.api.abobaui.elements.impl.Text.Companion.textSupplied
import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.module.Module
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.lore
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.ItemUtils.skyblockUuid
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ServerboundUseItemPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object CooldownDisplay : Module(
    "Cooldown Display",
    area = Island.Dungeon,
    desc = "Tracks right-click item abilities and lists active hotbar cooldowns.",
) {
    private val showHotbarSlot by switch(
        "Show hotbar slot",
        true,
        desc = "Prefixes every cooldown with its current hotbar slot.",
    )
    private val decimalPlaces by slider(
        "Decimal places",
        1,
        0,
        2,
        1,
        desc = "Number of decimal places used for remaining seconds.",
    )
    private val applyMageReduction by switch(
        "Mage cooldown reduction",
        true,
        desc = "Accounts for the active dungeon Mage class cooldown multiplier.",
    )

    private data class ActiveCooldown(
        val startedAtNanos: Long,
        val expiresAtNanos: Long,
    ) {
        val durationNanos: Long get() = expiresAtNanos - startedAtNanos
    }

    private val activeCooldowns = ConcurrentHashMap<String, ActiveCooldown>()

    @Volatile
    private var trackedWorld: ClientLevel? = null
    private var cleanupTicks = 0

    @Suppress("unused")
    private val cooldownHud by textHud("Ability cooldowns") {
        visibleIf { preview || hotbarContainsActiveCooldown() }

        column {
            repeat(HOTBAR_SIZE) { slot ->
                val row = textSupplied(
                    supplier = {
                        if (preview) PREVIEW_ROWS.getOrElse(slot) { "" }
                        else formatHotbarCooldown(slot)
                    },
                    font = font,
                    size = 18.px,
                    colour = colour,
                )
                row.shadow = shadow
                row.visibleIf {
                    if (preview) slot < PREVIEW_ROWS.size else activeCooldownAt(slot) != null
                }
            }
        }
    }.withSettings(::showHotbarSlot, ::decimalPlaces, ::applyMageReduction).setting()

    init {
        on<PacketEvent.Sent, ServerboundUseItemPacket> {
            if (packet.hand != InteractionHand.MAIN_HAND) return@on
            startRightClickCooldown(player.getItemInHand(packet.hand))
        }

        on<TickEvent.End> {
            cleanupTicks++
            if (cleanupTicks >= CLEANUP_INTERVAL_TICKS) {
                cleanupTicks = 0
                removeExpired(System.nanoTime())
            }
        }

        on<WorldEvent.Change> {
            clearCooldowns()
        }
    }

    override fun onDisable() {
        clearCooldowns()
        super.onDisable()
    }

    @JvmStatic
    fun isOnCooldown(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        ensureCurrentWorld()
        val key = cooldownKey(stack) ?: return false
        return activeCooldown(key, System.nanoTime()) != null
    }

    @JvmStatic
    fun startRightClickCooldown(stack: ItemStack?) {
        if (stack == null || stack.isEmpty || mc.level == null) return
        ensureCurrentWorld()

        val key = cooldownKey(stack) ?: return
        val baseDuration = inferCooldownMillis(stack) ?: return
        val multiplier = if (applyMageReduction && inDungeons) {
            Dungeon.getMageCooldownMultiplier()
        } else {
            1.0
        }
        val durationNanos = (baseDuration * multiplier * NANOS_PER_MILLISECOND)
            .toLong()
            .coerceIn(MIN_DURATION_NANOS, MAX_DURATION_NANOS)
        val now = System.nanoTime()

        activeCooldowns.compute(key) { _, existing ->
            if (existing != null && existing.expiresAtNanos > now) existing
            else ActiveCooldown(now, now + durationNanos)
        }
    }

    private fun activeCooldownAt(slot: Int): Pair<ItemStack, ActiveCooldown>? {
        val stack = mc.player?.inventory?.getItem(slot) ?: return null
        if (stack.isEmpty) return null
        val key = cooldownKey(stack) ?: return null
        val cooldown = activeCooldown(key, System.nanoTime()) ?: return null
        return stack to cooldown
    }

    private fun activeCooldown(key: String, now: Long): ActiveCooldown? {
        val cooldown = activeCooldowns[key] ?: return null
        if (cooldown.expiresAtNanos > now) return cooldown
        activeCooldowns.remove(key, cooldown)
        return null
    }

    private fun hotbarContainsActiveCooldown(): Boolean {
        ensureCurrentWorld()
        return (0 until HOTBAR_SIZE).any { activeCooldownAt(it) != null }
    }

    private fun formatHotbarCooldown(slot: Int): String {
        val (stack, cooldown) = activeCooldownAt(slot) ?: return ""
        val now = System.nanoTime()
        val remaining = (cooldown.expiresAtNanos - now).coerceAtLeast(0L)
        val progress = if (cooldown.durationNanos <= 0L) 0.0 else remaining.toDouble() / cooldown.durationNanos
        val timerColour = when {
            progress > 2.0 / 3.0 -> "§c"
            progress > 1.0 / 3.0 -> "§6"
            else -> "§e"
        }
        val slotPrefix = if (showHotbarSlot) "§7${slot + 1}. §r" else ""
        val itemName = stack.hoverName.string.noControlCodes.trim().ifEmpty { "Ability" }
        val seconds = remaining / NANOS_PER_SECOND.toDouble()
        val formatted = String.format(Locale.ROOT, "%.${decimalPlaces}f", seconds)
        return "$slotPrefix$itemName: $timerColour${formatted}s"
    }

    private fun inferCooldownMillis(stack: ItemStack): Long? {
        var clickContext = ClickContext.UNSPECIFIED
        var firstCooldown: Long? = null
        var hasRightClickAbility = false

        stack.lore.orEmpty().forEach { rawLine ->
            val line = rawLine.noControlCodes
            when {
                RIGHT_CLICK_PATTERN.containsMatchIn(line) -> {
                    clickContext = ClickContext.RIGHT
                    hasRightClickAbility = true
                }
                LEFT_CLICK_PATTERN.containsMatchIn(line) -> clickContext = ClickContext.LEFT
            }

            COOLDOWN_PATTERN.findAll(line).forEach { match ->
                val millis = parseCooldownMillis(match) ?: return@forEach
                if (firstCooldown == null) firstCooldown = millis
                if (clickContext == ClickContext.RIGHT) return millis
            }
        }

        val id = stack.skyblockId?.uppercase(Locale.ROOT)
        return id?.let(KNOWN_COOLDOWNS_MILLIS::get)
            ?: firstCooldown.takeUnless { hasRightClickAbility }
    }

    private fun parseCooldownMillis(match: MatchResult): Long? {
        val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        if (!amount.isFinite() || amount <= 0.0) return null
        val millis = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "ms", "millisecond", "milliseconds" -> amount
            "m", "min", "mins", "minute", "minutes" -> amount * 60_000.0
            else -> amount * 1_000.0
        }
        return millis.takeIf { it in 1.0..MAX_DURATION_MILLIS.toDouble() }?.toLong()
    }

    private fun cooldownKey(stack: ItemStack): String? {
        stack.skyblockUuid?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "uuid:${it.lowercase(Locale.ROOT)}"
        }
        stack.skyblockId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return "id:${it.uppercase(Locale.ROOT)}"
        }
        return stack.hoverName.string.noControlCodes.trim()
            .takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
            ?.let { "name:$it" }
    }

    private fun removeExpired(now: Long) {
        activeCooldowns.entries.removeIf { it.value.expiresAtNanos <= now }
    }

    private fun ensureCurrentWorld() {
        val world = mc.level
        if (trackedWorld === world) return
        activeCooldowns.clear()
        trackedWorld = world
        cleanupTicks = 0
    }

    private fun clearCooldowns() {
        activeCooldowns.clear()
        trackedWorld = mc.level
        cleanupTicks = 0
    }

    private const val HOTBAR_SIZE = 9
    private const val CLEANUP_INTERVAL_TICKS = 20
    private const val NANOS_PER_MILLISECOND = 1_000_000.0
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MIN_DURATION_NANOS = 50_000_000L
    private const val MAX_DURATION_MILLIS = 10 * 60 * 1_000L
    private const val MAX_DURATION_NANOS = MAX_DURATION_MILLIS * 1_000_000L

    private val COOLDOWN_PATTERN = Regex(
        """(?i)\bcooldown\b\s*:?[\s-]*([0-9]+(?:[.,][0-9]+)?)\s*(ms|milliseconds?|s|secs?|seconds?|m|mins?|minutes?)\b""",
    )
    private val RIGHT_CLICK_PATTERN = Regex("""(?i)\bright[\s-]*click\b""")
    private val LEFT_CLICK_PATTERN = Regex("""(?i)\bleft[\s-]*click\b""")

    private enum class ClickContext { UNSPECIFIED, LEFT, RIGHT }

    private val KNOWN_COOLDOWNS_MILLIS = mapOf(
        "ICE_SPRAY_WAND" to 5_000L,
        "FIRE_FREEZE_STAFF" to 10_000L,
        "GYROKINETIC_WAND" to 10_000L,
        "RAGNAROCK_AXE" to 20_000L,
        "WEIRD_TUBA" to 20_000L,
        "WEIRDER_TUBA" to 20_000L,
        "INK_WAND" to 30_000L,
        "SOUL_ESOWARD" to 20_000L,
        "WITHER_CLOAK" to 10_000L,
    )

    private val PREVIEW_ROWS = listOf(
        "§71. §rIce Spray Wand: §c3.8s",
        "§74. §rGyrokinetic Wand: §68.4s",
        "§78. §rRagnarock Axe: §e2.1s",
    )
}
