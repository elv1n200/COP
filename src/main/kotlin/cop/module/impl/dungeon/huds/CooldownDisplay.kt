package cop.module.impl.dungeon.huds

import cop.api.events.ChatEvent
import cop.api.events.MouseEvent
import cop.api.events.RenderEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon.currentDungeonPlayer
import cop.api.skyblock.dungeon.Dungeon.dungeonTeammates
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.dungeon.DungeonClass
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
    desc = "Visualises ability cooldowns from a 'Cooldown: Xs' tooltip line — greys out the hotbar slot and/or overlays the remaining seconds as a number. Works in dungeons by default; toggle Only in dungeons off to use it anywhere in Skyblock.",
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
    /** Active per-item state, keyed by item identity. Tracks both the "ability
     *  is currently doing its thing" phase (no overlay) and the "actually on
     *  cooldown" phase (overlay visible). The TickEvent handler transitions
     *  vanillaSweepStarted false → true at the active/cooldown boundary, so
     *  the grey-sweep animation kicks in only for the cooldown half. Lazy-
     *  evicted as entries expire. */
    private val tracked = HashMap<String, ActiveCooldown>()
    /** Item key + cooldown seconds remembered from the last click — needed to
     *  recover from duration abilities where the lore alone can't predict
     *  when the cooldown starts. Cleared when used or when world changes. */
    private var lastClick: LastClickInfo? = null
    /** A duration ability we've seen the "Activated!" chat for and are now
     *  waiting on the "De-activated!" chat to start the real cooldown. Null
     *  outside that window. */
    private var awaitingDeactivation: DurationActivation? = null

    /** Snapshot of the most recent click — item key, scaled cooldown seconds
     *  for that side, wall-clock timestamp. */
    private data class LastClickInfo(
        val itemKey: String,
        val stack: ItemStack,
        val scaledCooldownSeconds: Double,
        val clickedAtMs: Long,
    )

    /** Bookkeeping for the activated→deactivated chat handshake. */
    private data class DurationActivation(
        val itemKey: String,
        val stack: ItemStack,
        val scaledCooldownSeconds: Double,
        val activatedAtMs: Long,
    )

    /** What we managed to extract from one item's tooltip. [durationSeconds]
     *  is the "ability active for X seconds" Hypixel pattern; items without
     *  a duration phase leave it null. */
    private data class ItemCooldownInfo(
        val rightSeconds: Double?,
        val leftSeconds: Double?,
        val durationSeconds: Double?,
        val mentionsRightClick: Boolean,
        val mentionsLeftClick: Boolean,
    )

    /** Per-active-cooldown state. For instant-trigger abilities,
     *  activeUntilMs == triggeredAtMs (no active phase) and the cooldown
     *  starts immediately. For duration abilities, the cooldown phase only
     *  starts at activeUntilMs. */
    private data class ActiveCooldown(
        val stackForVanillaCall: ItemStack,
        val activeUntilMs: Long,
        val cooldownEndsAtMs: Long,
        var vanillaSweepStarted: Boolean,
    )

    /** Distinguishes which click side a request applies to. Internal — the
     *  external API exposes only the right-click variant since that's the
     *  only one downstream callers ever needed. */
    private enum class Side { Right, Left }

    // ---- Lore parsing -----------------------------------------------------

    private val COOLDOWN_LINE = Regex("""(?i)cooldown[:\s]+([\d]+(?:[.,][\d]+)?)\s*s\b""")
    private val DURATION_LINE = Regex("""(?i)duration[:\s]+([\d]+(?:[.,][\d]+)?)\s*s\b""")
    private val FORMAT_CODE = Regex("""§[0-9A-FK-ORa-fk-or]""")
    /** Hypixel announces duration-ability state in chat. We use the activated→
     *  deactivated pair to drive the real cooldown for items whose lore
     *  doesn't include a `Duration:` line (most of them — "for 10 seconds"
     *  is more common phrasing, hard to parse cleanly). */
    private val ABILITY_ACTIVATED = Regex("""^[A-Z][A-Za-z' ]+ Activated!$""")
    private val ABILITY_DEACTIVATED = Regex("""^[A-Z][A-Za-z' ]+ De-activated!""")
    /** Max age of a click that an "Activated!" chat can attribute itself to. */
    private const val CLICK_TO_ACTIVATION_WINDOW_MS = 2_500L
    /** Max age of an "Activated!" before we stop expecting "De-activated!". */
    private const val ACTIVATION_TTL_MS = 120_000L

    private fun parseLore(stack: ItemStack): ItemCooldownInfo {
        val lines = stack.lore ?: return ItemCooldownInfo(null, null, null, false, false)

        // Single forward sweep through the lore. Each line either:
        //   (a) declares which click side the next ability section is for
        //       ("Ability: X  RIGHT CLICK") — toggles `currentSides`,
        //   (b) declares a cooldown ("Cooldown: 6s") — attributed to whatever
        //       sides are currently active,
        //   (c) declares a duration ("Duration: 10s") — applies item-wide
        //       (no item we've seen has different durations per click side),
        //   (d) is none of the above and we ignore it.
        var currentSides: Set<Side> = emptySet()
        var sawRight = false
        var sawLeft = false
        var rightSeconds: Double? = null
        var leftSeconds: Double? = null
        var durationSeconds: Double? = null

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

            // (c) duration line — checked BEFORE cooldown so the substring
            //     "Duration: 10s\nCooldown: 6s" assigns 10s to the duration
            //     field and 6s to cooldown, not the other way around. Match
            //     once per item (first occurrence wins).
            if (durationSeconds == null) {
                DURATION_LINE.find(clean)?.let { m ->
                    m.groupValues[1].replace(',', '.').toDoubleOrNull()?.let { durationSeconds = it }
                    return@let
                }
            }

            // (b) cooldown line — only meaningful if we know which sides
            val match = COOLDOWN_LINE.find(clean) ?: continue
            val secs = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue

            val attribTo = if (currentSides.isNotEmpty()) currentSides else inferDefaultSides(sawRight, sawLeft)
            if (Side.Right in attribTo && rightSeconds == null) rightSeconds = secs
            if (Side.Left  in attribTo && leftSeconds  == null) leftSeconds  = secs
        }

        return ItemCooldownInfo(rightSeconds, leftSeconds, durationSeconds, sawRight, sawLeft)
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
        // Also bail out if our own tracker has the item active. Vanilla
        // cooldowns can be cleared by interactions we don't track, so we
        // need both checks.
        val now = System.currentTimeMillis()
        tracked[key]?.let { existing -> if (existing.cooldownEndsAtMs > now) return false }

        val info = parseCache.getOrPut(key) { parseLore(stack) }
        val rawSeconds = lookupSeconds(info, side) ?: return false
        if (rawSeconds <= 0.0) return false

        val scaledSeconds = rawSeconds * mageReductionFactor()
        val cooldownTicks = max(1, (scaledSeconds * 20.0).roundToInt())

        // Items with "Duration: Xs" in lore have an active phase first (during
        // which the cooldown HASN'T started yet on Hypixel's side). The overlay
        // would be misleading there — show nothing until the active phase ends.
        val durationMs = ((info.durationSeconds ?: 0.0) * 1000.0).toLong()
        val activeUntilMs = now + durationMs
        val cooldownEndsAtMs = activeUntilMs + (cooldownTicks * 50L)

        val entry = ActiveCooldown(
            stackForVanillaCall = stack,
            activeUntilMs = activeUntilMs,
            cooldownEndsAtMs = cooldownEndsAtMs,
            vanillaSweepStarted = false,
        )
        tracked[key] = entry

        // Instant abilities (no Duration line in lore): start the vanilla
        // sweep right now. Duration abilities: deferred — TickEvent will
        // flip vanillaSweepStarted true and call addCooldown when the active
        // phase ends.
        if (durationMs == 0L && showGreySweep) {
            cooldownTable.addCooldown(stack, cooldownTicks)
            entry.vanillaSweepStarted = true
        }
        // Remember this click — the chat-based override (Activated/De-activated
        // pair) only knows which item the message belongs to via "was just
        // clicked". 60-second TTL is enforced at use time.
        if (fromUserInput) {
            lastClick = LastClickInfo(key, stack, scaledSeconds, now)
        }
        return true
    }

    private fun lookupSeconds(info: ItemCooldownInfo, side: Side): Double? {
        val explicit = when (side) {
            Side.Right -> info.rightSeconds
            Side.Left  -> info.leftSeconds
        }
        if (explicit != null) return explicit

        // No explicit cooldown. Fall back if (a) the user enabled a fallback
        // and (b) the matching click side at least exists in the lore — we
        // shouldn't slap a cooldown on items that have no ability of that
        // side at all.
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
            tracked.clear()
            lastClick = null
            awaitingDeactivation = null
            lastFireWallMs = 0L
        }

        // Chat-driven override for duration abilities (Wither Cloak, Creeper
        // Veil, etc.). Hypixel announces these as
        //   "<Ability> Activated!"
        //   "<Ability> De-activated! (Expired)"   (or "(Cancelled)" on early end)
        // The lore-based prediction we kicked off on the click ran the
        // countdown DURING the active phase, which is wrong — the real
        // cooldown only starts once Hypixel says De-activated. So: on
        // Activated within a few seconds of our most recent click, evict
        // the predicted entry; on De-activated, install a fresh entry that
        // starts the cooldown right NOW with the lore-parsed duration.
        on<ChatEvent.Receive> {
            val plain = message.replace(FORMAT_CODE, "")
            val now = System.currentTimeMillis()

            // Sweep stale activation state — if an ability somehow never
            // sends its De-activated message, we shouldn't sit on stale
            // bookkeeping forever.
            awaitingDeactivation?.let { da ->
                if (now - da.activatedAtMs > ACTIVATION_TTL_MS) awaitingDeactivation = null
            }

            if (ABILITY_ACTIVATED.matches(plain)) {
                val click = lastClick ?: return@on
                if (now - click.clickedAtMs > CLICK_TO_ACTIVATION_WINDOW_MS) return@on
                // This is a duration ability — the predicted countdown was
                // wrong. Drop it from our tracker so the numeric overlay
                // stops showing. The vanilla grey-sweep we already armed
                // would need a ResourceLocation to cancel which isn't
                // straightforward across MC versions — but the predicted
                // duration usually expires around the same time as the
                // De-activated chat fires for items where active ≈ cooldown
                // length (the common case), so it self-corrects visually.
                tracked.remove(click.itemKey)
                awaitingDeactivation = DurationActivation(
                    itemKey = click.itemKey,
                    stack = click.stack,
                    scaledCooldownSeconds = click.scaledCooldownSeconds,
                    activatedAtMs = now,
                )
                lastClick = null
                return@on
            }

            if (ABILITY_DEACTIVATED.containsMatchIn(plain)) {
                val pending = awaitingDeactivation ?: return@on
                awaitingDeactivation = null
                val ticks = max(1, (pending.scaledCooldownSeconds * 20.0).roundToInt())
                val entry = ActiveCooldown(
                    stackForVanillaCall = pending.stack,
                    activeUntilMs = now,
                    cooldownEndsAtMs = now + (ticks * 50L),
                    vanillaSweepStarted = false,
                )
                tracked[pending.itemKey] = entry
                if (showGreySweep) {
                    mc.player?.cooldowns?.addCooldown(pending.stack, ticks)
                    entry.vanillaSweepStarted = true
                }
            }
        }

        // Phase transition + GC: walk tracked entries each tick, evict expired
        // ones, and on the active→cooldown boundary kick off the vanilla
        // grey-sweep with the remaining ticks. Cheap — tracked is usually
        // 0..2 entries.
        on<TickEvent.End> {
            if (tracked.isEmpty()) return@on
            val now = System.currentTimeMillis()
            val player = mc.player
            val it = tracked.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next().value
                if (now >= entry.cooldownEndsAtMs) {
                    it.remove()
                    continue
                }
                if (!entry.vanillaSweepStarted && now >= entry.activeUntilMs) {
                    if (showGreySweep && player != null) {
                        val remainingMs = entry.cooldownEndsAtMs - now
                        val ticks = max(1, ((remainingMs / 1000.0) * 20.0).roundToInt())
                        player.cooldowns.addCooldown(entry.stackForVanillaCall, ticks)
                    }
                    entry.vanillaSweepStarted = true
                }
            }
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
            if (tracked.isEmpty()) return@on

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
                val entry = tracked[key] ?: continue
                // Active phase: ability is doing its thing on Hypixel's side,
                // the cooldown hasn't actually started. Drawing a countdown
                // here would be misleading — leave the slot clean.
                if (now < entry.activeUntilMs) continue
                val remaining = entry.cooldownEndsAtMs - now
                if (remaining <= 0) continue  // TickEvent will evict shortly
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
