package cop.module.impl.dungeon.huds

import cop.api.events.MouseEvent
import cop.api.events.RenderEvent
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
import net.minecraft.world.item.ItemStack
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Drives Minecraft's vanilla item-cooldown overlay (the grey-sweep animation)
 * for Hypixel ability items based on their tooltip text.
 *
 * Hypixel ships ability items with lore like
 *
 *     Ability: Throw  RIGHT CLICK
 *     Cooldown: 6s
 *
 * but the client never sees Hypixel's own cooldown bookkeeping — you only
 * get told you used the ability via chat. This module fakes a local cooldown
 * the moment you click, so the hotbar slot greys out for the duration. Pure
 * UI; no server interaction.
 *
 * **Public API** (used by other modules that synthesise clicks themselves —
 * AutoRCM, AutoLCM, M3AutoFF):
 *  - [startRightClickCooldown] — start the right-click cooldown on a stack
 *    you just sent a click for. Returns whether the cooldown actually started
 *    (false if the item has no parseable cooldown or one is already running).
 *  - [isOnCooldown] — non-throwing peek at the vanilla cooldown table.
 *
 * Re-implemented from scratch (May 2026). No code shared with the previous
 * port; only the behavioural spec carried over.
 */
object CooldownDisplay : Module(
    "Cooldown Display",
    area = Island.Dungeon,
    desc = "Visualises ability cooldowns from a 'Cooldown: Xs' tooltip line — greys out the hotbar slot and/or overlays the remaining seconds as a number.",
) {

    // ---- Settings ---------------------------------------------------------

    private val dungeonsOnly by switch(
        "Only in dungeons", true,
        desc = "When on, the overlay only fires inside a Catacombs run. Off = anywhere in Skyblock.",
    )
    private val showGreySweep by switch(
        "Grey sweep overlay", true,
        desc = "Vanilla-style grey sweep animation on the hotbar slot. Same look as eating food or throwing an ender pearl.",
    )
    private val showNumberCountdown by switch(
        "Numeric countdown", true,
        desc = "Draw the remaining seconds as a small number on the hotbar slot. Sub-10s shows one decimal, above 10s shows whole seconds.",
    )
    private val coverRightClick by switch(
        "Right click", true,
        desc = "Apply the overlay when you right-click an item with a right-click ability.",
    )
    private val coverLeftClick by switch(
        "Left click", true,
        desc = "Apply the overlay when you left-click an item with a left-click ability.",
    )
    private val fallbackSeconds by slider(
        "Fallback cooldown", 0f, 0f, 10f, 0.1f, unit = "s",
        desc = "Used when the lore mentions the matching click ability but doesn't spell out the duration. " +
            "Set to 0 to disable — no fallback, no overlay for those items.",
    )
    private val applyMageReduction by switch(
        "Mage CD reduction", true,
        desc = "Scale the duration by the Mage class's cooldown-reduction formula when the player is a Mage " +
            "in a dungeon (25% base / 50% solo + 0.5% per class level, capped at 75%).",
    )
    private val debounceMillis by slider(
        "Min retrigger", 120, 0, 1000, 10, unit = "ms",
        desc = "Dead-time between two click triggers — guards against double-clicks generating two cooldowns.",
    )

    // ---- Internal state ---------------------------------------------------

    /** Parsed lore data per item identity — keyed by the most specific id we
     *  can compute (skyblock UUID > skyblock id > item type + name). */
    private val parseCache = HashMap<String, ItemCooldownInfo>()
    /** Last accepted trigger, in wall-clock millis. Used to apply [debounceMillis]. */
    private var lastFireWallMs = 0L
    /** Active cooldowns, keyed by item identity → wall-clock millis at which
     *  the cooldown expires. Maintained in parallel with the vanilla cooldown
     *  table so the numeric overlay can compute remaining time without parsing
     *  the vanilla `ItemCooldowns` internals. Lazy-evicted on read. */
    private val activeEndTimes = HashMap<String, Long>()

    /** What we managed to extract from one item's tooltip. */
    private data class ItemCooldownInfo(
        val rightSeconds: Double?,
        val leftSeconds: Double?,
        val mentionsRightClick: Boolean,
        val mentionsLeftClick: Boolean,
    )

    /** Distinguishes which click side a request applies to. Internal — the
     *  external API exposes only the right-click variant since that's the
     *  only one downstream callers ever needed. */
    private enum class Side { Right, Left }

    // ---- Lore parsing -----------------------------------------------------

    private val COOLDOWN_LINE = Regex("""(?i)cooldown[:\s]+([\d]+(?:[.,][\d]+)?)\s*s\b""")
    private val FORMAT_CODE = Regex("""§[0-9A-FK-ORa-fk-or]""")

    private fun parseLore(stack: ItemStack): ItemCooldownInfo {
        val lines = stack.lore ?: return ItemCooldownInfo(null, null, false, false)

        // Single forward sweep through the lore. Each line either:
        //   (a) declares which click side the next ability section is for
        //       ("Ability: X  RIGHT CLICK") — toggles `currentSides`,
        //   (b) declares a cooldown ("Cooldown: 6s") — attributed to whatever
        //       sides are currently active,
        //   (c) is neither and we ignore it.
        var currentSides: Set<Side> = emptySet()
        var sawRight = false
        var sawLeft = false
        var rightSeconds: Double? = null
        var leftSeconds: Double? = null

        for (rawLine in lines) {
            val clean = rawLine.replace(FORMAT_CODE, "")
            val upper = clean.uppercase()

            // (a) ability declaration line
            if ("ABILITY" in upper) {
                val sides = mutableSetOf<Side>()
                if ("RIGHT CLICK" in upper) { sides += Side.Right; sawRight = true }
                if ("LEFT CLICK" in upper)  { sides += Side.Left;  sawLeft  = true }
                if (sides.isNotEmpty()) currentSides = sides
                continue
            }

            // (b) cooldown line — only meaningful if we know which sides
            val match = COOLDOWN_LINE.find(clean) ?: continue
            val secs = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue

            val attribTo = if (currentSides.isNotEmpty()) currentSides else inferDefaultSides(sawRight, sawLeft)
            if (Side.Right in attribTo && rightSeconds == null) rightSeconds = secs
            if (Side.Left  in attribTo && leftSeconds  == null) leftSeconds  = secs
        }

        return ItemCooldownInfo(rightSeconds, leftSeconds, sawRight, sawLeft)
    }

    /** When a "Cooldown:" line appears without an immediately-preceding
     *  ability tag, attribute it to whichever side was mentioned anywhere in
     *  the tooltip — or both if neither/both did. Mirrors how Hypixel items
     *  with one ability list the cooldown unscoped. */
    private fun inferDefaultSides(sawRight: Boolean, sawLeft: Boolean): Set<Side> = when {
        sawRight && !sawLeft -> setOf(Side.Right)
        sawLeft && !sawRight -> setOf(Side.Left)
        else -> setOf(Side.Right, Side.Left)
    }

    // ---- Cooldown apply ---------------------------------------------------

    /** True iff the vanilla cooldown table currently has [stack] greyed. */
    fun isOnCooldown(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        val cd = mc.player?.cooldowns ?: return false
        return cd.isOnCooldown(stack)
    }

    /** External hook for sibling automation modules: same as a real right-click
     *  trigger but bypasses the [coverRightClick] toggle (the caller already
     *  decided to fire). Returns true if a cooldown was actually started. */
    fun startRightClickCooldown(stack: ItemStack?): Boolean {
        if (stack == null) return false
        return applyClientCooldown(stack, Side.Right, fromUserInput = false)
    }

    /** Internal: run the full pipeline for a user click. */
    private fun handleUserClick(stack: ItemStack?, side: Side) {
        if (dungeonsOnly && !inDungeons) return
        if (side == Side.Right && !coverRightClick) return
        if (side == Side.Left  && !coverLeftClick)  return
        if (stack == null || stack.isEmpty) return

        val now = System.currentTimeMillis()
        if (now - lastFireWallMs < debounceMillis.toLong()) return

        if (applyClientCooldown(stack, side, fromUserInput = true)) {
            lastFireWallMs = now
        }
    }

    /** Returns true if a cooldown was newly applied; false if no parseable
     *  duration for this side, or if the cooldown is already active. */
    private fun applyClientCooldown(stack: ItemStack, side: Side, fromUserInput: Boolean): Boolean {
        if (stack.isEmpty) return false
        val player = mc.player ?: return false
        val cooldownTable = player.cooldowns
        if (cooldownTable.isOnCooldown(stack)) return false
        val key = identityKey(stack)
        // Also bail out if our own numeric-countdown table already has the
        // item active. Vanilla cooldowns can be cleared by interactions we
        // don't track, so we need both checks.
        val now = System.currentTimeMillis()
        activeEndTimes[key]?.let { end -> if (end > now) return false }

        val rawSeconds = lookupSeconds(stack, side) ?: return false
        if (rawSeconds <= 0.0) return false

        val scaledSeconds = rawSeconds * mageReductionFactor()
        val ticks = max(1, (scaledSeconds * 20.0).roundToInt())

        // Vanilla grey-sweep overlay — only registered if the user wants it,
        // so disabling the sweep cleanly hides it. The numeric countdown
        // (drawn from activeEndTimes in the RenderEvent.Overlay handler) is
        // unaffected.
        if (showGreySweep) cooldownTable.addCooldown(stack, ticks)
        activeEndTimes[key] = now + (ticks * 50L)
        // fromUserInput is unused at the moment, but kept as a hook in case
        // we ever want to emit a different chat message / sfx for user vs.
        // synthetic triggers.
        return true
    }

    private fun lookupSeconds(stack: ItemStack, side: Side): Double? {
        val key = identityKey(stack)
        val info = parseCache.getOrPut(key) { parseLore(stack) }

        val explicit = when (side) {
            Side.Right -> info.rightSeconds
            Side.Left  -> info.leftSeconds
        }
        if (explicit != null) return explicit

        // No explicit duration. Fall back if (a) the user enabled it and (b)
        // the matching side at least exists in the lore.
        val sideMentioned = when (side) {
            Side.Right -> info.mentionsRightClick
            Side.Left  -> info.mentionsLeftClick
        }
        if (!sideMentioned) return null
        val fb = fallbackSeconds.toDouble()
        return if (fb > 0.0) fb else null
    }

    private fun identityKey(stack: ItemStack): String {
        stack.skyblockUuid?.trim().takeUnless { it.isNullOrEmpty() }?.let { return "u:$it" }
        stack.skyblockId  ?.trim().takeUnless { it.isNullOrEmpty() }?.let { return "i:$it" }
        return "t:${stack.item}|${stack.hoverName.string.replace(FORMAT_CODE, "")}"
    }

    // ---- Mage cooldown reduction -----------------------------------------

    private const val MAGE_GROUP_BASE = 0.25
    private const val MAGE_SOLO_BASE = 0.50
    private const val MAGE_PER_LEVEL = 0.005
    private const val MAGE_MAX_REDUCTION = 0.75

    /** Multiplier in [0.25, 1.0] to apply to the parsed seconds. */
    private fun mageReductionFactor(): Double {
        if (!applyMageReduction || !inDungeons) return 1.0
        val me = currentDungeonPlayer
        if (me.clazz != DungeonClass.Mage) return 1.0

        val baseReduction = if (otherMagesInParty()) MAGE_GROUP_BASE else MAGE_SOLO_BASE
        val total = (baseReduction + me.clazzLvl * MAGE_PER_LEVEL).coerceAtMost(MAGE_MAX_REDUCTION)
        return (1.0 - total).coerceIn(0.10, 1.0)
    }

    private fun otherMagesInParty(): Boolean {
        // toList() snapshot — dungeonTeammates is mutated from the network
        // thread (party / leap updates), iterating directly would race.
        val mates = dungeonTeammates.toList()
        if (mates.isEmpty()) return false  // solo run = no party = use solo base
        val myName = currentDungeonPlayer.name
        return mates.any {
            it.clazz == DungeonClass.Mage && !it.name.equals(myName, ignoreCase = true)
        }
    }

    // ---- Event wiring -----------------------------------------------------

    // ---- Numeric countdown render ----------------------------------------

    /** Vanilla hotbar geometry (matches the values inside Gui.renderHotbar):
     *  centered horizontally, 22px tall image at the bottom, 9 slots × 20px
     *  starting 91px left of centre + 3px slot inset. */
    private const val HOTBAR_HALF_WIDTH = 91
    private const val HOTBAR_SLOT_WIDTH = 20
    private const val HOTBAR_SLOT_INSET = 3
    private const val HOTBAR_BOTTOM_OFFSET = 22

    private fun formatCountdown(remainingMs: Long): String {
        val seconds = remainingMs / 1000.0
        return when {
            seconds >= 10.0 -> seconds.toInt().toString()
            seconds >= 1.0  -> "%.1f".format(seconds)
            else            -> "%.1f".format(seconds.coerceAtLeast(0.1))
        }
    }

    init {
        on<MouseEvent.Click> {
            if (!state) return@on
            // state == true is "button pressed" (vs. release). Ignore clicks
            // while a GUI is open — those go to slot logic, not item use.
            if (mc.screen != null) return@on
            val side = when (button) {
                0 -> Side.Left
                1 -> Side.Right
                else -> return@on
            }
            handleUserClick(mc.player?.mainHandItem, side)
        }

        on<WorldEvent.Change> {
            parseCache.clear()
            activeEndTimes.clear()
            lastFireWallMs = 0L
        }

        // Numeric countdown — per-frame HUD overlay. We avoid drawing while
        // a screen is open (inventory, chest GUI, etc.) since the player can't
        // act on the cooldown info there anyway and the number would land in
        // a weird spot relative to the screen-overlay hotbar.
        on<RenderEvent.Overlay> {
            if (!showNumberCountdown) return@on
            val player = mc.player ?: return@on
            if (mc.screen != null) return@on
            if (dungeonsOnly && !inDungeons) return@on
            if (activeEndTimes.isEmpty()) return@on

            val window = mc.window
            val w = window.guiScaledWidth
            val h = window.guiScaledHeight
            val hotbarLeft = (w / 2) - HOTBAR_HALF_WIDTH + HOTBAR_SLOT_INSET
            val slotY = h - HOTBAR_BOTTOM_OFFSET + HOTBAR_SLOT_INSET

            val font = mc.font
            val now = System.currentTimeMillis()

            for (slotIndex in 0 until 9) {
                val stack = player.inventory.getItem(slotIndex)
                if (stack.isEmpty) continue
                val key = identityKey(stack)
                val end = activeEndTimes[key] ?: continue
                val remaining = end - now
                if (remaining <= 0) {
                    activeEndTimes.remove(key)
                    continue
                }
                val label = formatCountdown(remaining)
                val labelWidth = font.width(label)
                val slotX = hotbarLeft + slotIndex * HOTBAR_SLOT_WIDTH
                // Centered horizontally over the 16px-wide slot icon. Vertically
                // a couple px below the centre so it doesn't sit on top of the
                // vanilla item-count text (lower-right).
                val drawX = slotX + (16 - labelWidth) / 2
                val drawY = slotY + 4
                ctx.drawString(font, label, drawX, drawY, 0xFFFFFFFF.toInt(), true)
            }
        }
    }
}
