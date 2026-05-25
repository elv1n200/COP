package cop.api.skyblock.croesus

import cop.utils.StringUtils.formattedString
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.PriceClient
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.AbstractContainerMenu

/**
 * Read-only parser for the Croesus NPC's GUIs.
 *
 *  - [inCroesusMenu]    — top-level run-selection screen (title "Croesus").
 *  - [inRunMenu]        — a single-run sub-screen ("Catacombs - Floor X" or
 *                         "Master Catacombs - Floor X").
 *  - [findUnclaimedRunSlots] — which slots on the run-selection screen still
 *                         have unopened chests, for the overlay highlight.
 *  - [parseChests]      — for the run sub-screen, read every tier's tooltip,
 *                         decode contents + cost, look prices up via
 *                         [PriceClient], and return a [ChestInfo] per tier.
 *
 * Adapted from the ChatTriggers AutoCroesus module
 * (github.com/UnclaimedBloom6/RandomStuff/tree/main/AutoCroesus). Lore-shape
 * regexes (chest tier names, "Cost"/"§5§o§aFREE" sentinels, the
 * `§5§o§cNo chests opened yet!` unclaimed marker) come straight from there;
 * the regex format is dictated by Hypixel's actual tooltip text.
 *
 * Item-ID resolution covers:
 *  - Enchanted Books (bazaar id `ENCHANTMENT_[ULTIMATE_]<NAME>_<LEVEL>`)
 *  - Essences        (bazaar id `ESSENCE_<TYPE>`)
 *  - Anything else with a registered display name in the Hypixel items registry.
 */
object CroesusParser {

    /** Chest tier icon slot indices on the run sub-screen, in the order they
     *  appear in the Hypixel GUI (top-left to bottom-right, skipping borders).
     *  These are stable across floors — only some are populated for any given run. */
    private val CHEST_SLOT_INDICES = intArrayOf(11, 12, 13, 14, 15)

    /** A run still has unopened chests if any of its tooltip lines (plain text,
     *  formatting stripped) contains this substring. The CT script compared
     *  against the exact legacy-encoded string `§5§o§cNo chests opened yet!`,
     *  but in modern MC the lore is sent as a proper Component with style on
     *  the node itself — so the matcher works on the plain `.string` instead
     *  and is immune to whatever colour codes Hypixel attaches. */
    const val LORE_UNCLAIMED_MARKER: String = "No chests opened yet"

    /** Chest tier names today: `§7Wood Chest`, `§6Gold Chest`, …; the legacy CT
     *  regex required no suffix. We match plain text (no colour code group) and
     *  recover the tier colour via [tierColourCode] from a hardcoded mapping. */
    private val CHEST_TITLE_REGEX = Regex("^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$")
    private val COST_REGEX = Regex("^([\\d,]+) Coins$")
    private const val COST_FREE = "FREE"

    /** Lore line for an enchanted book. We keep the §-codes here because we
     *  need the `§d§l` prefix to distinguish ultimate from regular enchants. */
    private val BOOK_REGEX_FORMATTED = Regex(
        "^(?:§.)*Enchanted Book \\((§d§l)?([\\w ]+) (\\w+)(?:§.)*\\)\$"
    )
    /** Plain-text essence line, e.g. `Wither Essence x16`. */
    private val ESSENCE_REGEX = Regex("^(\\w+) Essence x(\\d+)$")
    private val NUMERAL_VALUES = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    /** Run-sub-menu titles. Hypixel formats both regular and master mode this way. */
    private val RUN_TITLE_REGEX = Regex("^(?:Master )?Catacombs - .+$")

    /** Buy-confirm screen — opened by clicking a chest tier in the run sub-screen.
     *  Title is just the tier name (`Wood`, `Gold`, …, `Bedrock`). 6-row chest;
     *  the buy button sits at [BUY_CONFIRM_SLOT], go-back at [BUY_BACK_SLOT],
     *  kismet/reroll at [BUY_REROLL_SLOT]. Slot layout verified via debug dump. */
    private val BUY_CONFIRM_TITLE_REGEX =
        Regex("^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)$")

    /** "Open Reward Chest" — clicking this deducts the cost and drops the
     *  loot straight into the player's inventory (no separate reward GUI). */
    const val BUY_CONFIRM_SLOT: Int = 31
    /** "Go Back" button — returns to the run sub-screen. */
    const val BUY_BACK_SLOT: Int = 49
    /** "Reroll Chest" (kismet feather). Not used by Phase 3a; reserved for
     *  the reroll driver in Phase 4. */
    const val BUY_REROLL_SLOT: Int = 50

    /** "Go Back" button in the run sub-screen — returns to the Croesus list.
     *  4-row chest, slot 30. Verified via debug dump. */
    const val RUN_BACK_SLOT: Int = 30

    // -- GUI detection ----------------------------------------------------------

    fun inCroesusMenu(screen: Screen?): Boolean =
        screen is AbstractContainerScreen<*> && screen.title.string.trim() == "Croesus"

    fun inRunMenu(screen: Screen?): Boolean =
        screen is AbstractContainerScreen<*> && RUN_TITLE_REGEX.matches(screen.title.string.trim())

    fun inBuyConfirmMenu(screen: Screen?): Boolean =
        screen is AbstractContainerScreen<*> &&
            BUY_CONFIRM_TITLE_REGEX.matches(screen.title.string.trim())

    /** Parse the chest currently displayed in the buy-confirm screen.
     *
     *  Slot 31 ("Open Reward Chest") has the same lore structure as a chest
     *  tier icon on the run sub-screen — `Contents` / items / blank / `Cost` /
     *  value — so we can reuse [parseChestLore]. The buy-confirm title is the
     *  bare tier name ("Wood", "Gold", …, "Bedrock") which gives us the tier
     *  colour for the chat output.
     *
     *  Returns null when [title] isn't a recognised tier; otherwise either a
     *  Success or Failure ChestParseResult. The slot index in the returned
     *  ChestInfo is [BUY_CONFIRM_SLOT] — callers needing the original run
     *  sub-screen slot should remember it separately. */
    fun parseBuyConfirmChest(menu: AbstractContainerMenu, title: String): ChestParseResult? {
        val tierName = title.trim()
        val colourCode = TIER_COLOUR_CODE[tierName] ?: return null
        val lorePlain = lorePlain(menu, BUY_CONFIRM_SLOT)
            ?: return ChestParseResult.Failure(tierName, "buy-confirm slot $BUY_CONFIRM_SLOT empty")
        val loreFormatted = loreFormatted(menu, BUY_CONFIRM_SLOT)
            ?: return ChestParseResult.Failure(tierName, "buy-confirm slot $BUY_CONFIRM_SLOT empty")
        return parseChestLore(BUY_CONFIRM_SLOT, tierName, colourCode, lorePlain, loreFormatted)
    }

    // -- Run-selection screen ---------------------------------------------------

    /** Slot indices on the top-level Croesus screen whose tooltip contains
     *  "No chests opened yet". The overlay uses these to highlight runs the
     *  user hasn't claimed yet. */
    fun findUnclaimedRunSlots(menu: AbstractContainerMenu): List<Int> {
        val out = mutableListOf<Int>()
        // The run icons live in the upper portion of the chest GUI; iterate
        // every slot smaller than the player-inventory boundary so we still
        // catch runs on later pages without hardcoding a page layout.
        val end = (menu.slots.size - 36).coerceAtMost(54)
        for (i in 0 until end) {
            val lore = lorePlain(menu, i) ?: continue
            if (lore.any { LORE_UNCLAIMED_MARKER in it }) out += i
        }
        return out
    }

    /** Static lookup so the overlay can colour each tier the way Hypixel does
     *  even though we strip formatting when parsing the item name. */
    private val TIER_COLOUR_CODE = mapOf(
        "Wood" to "§7", "Gold" to "§6", "Diamond" to "§b",
        "Emerald" to "§a", "Obsidian" to "§5", "Bedrock" to "§c",
    )

    // -- Run sub-screen ---------------------------------------------------------

    /** Parse every chest tier icon on the current run sub-screen.
     *  Returns one entry per successfully-parsed tier; tiers whose tooltip
     *  failed to parse become a [ChestParseResult.Failure] so the overlay
     *  can show "?" instead of silently dropping them. */
    fun parseChests(menu: AbstractContainerMenu): List<ChestParseResult> {
        val results = mutableListOf<ChestParseResult>()
        // Iterate the top three rows (0..26) — chest tier icons can sit in
        // various positions across floors, so we filter by name match.
        val end = (menu.slots.size - 36).coerceAtMost(27)
        for (i in 0 until end) {
            val slot = menu.slots.getOrNull(i) ?: continue
            val stack = slot.item ?: continue
            if (stack.isEmpty) continue

            // Match on plain text — hover name with all formatting stripped.
            val plainName = stack.hoverName.string.trim()
            val titleMatch = CHEST_TITLE_REGEX.matchEntire(plainName) ?: continue
            val tierName = titleMatch.groupValues[1]
            val colourCode = TIER_COLOUR_CODE[tierName] ?: "§7"

            // Read lore both ways: plain for marker / item lookups, formatted
            // for the few places we need the §-code (ultimate book detection).
            val lorePlain = lorePlain(menu, i) ?: run {
                results += ChestParseResult.Failure(tierName, "no lore"); continue
            }
            val loreFormatted = loreFormatted(menu, i) ?: run {
                results += ChestParseResult.Failure(tierName, "no lore"); continue
            }
            results += parseChestLore(i, tierName, colourCode, lorePlain, loreFormatted)
        }
        return results
    }

    private fun parseChestLore(
        slot: Int,
        tierName: String,
        colourCode: String,
        lorePlain: List<String>,
        loreFormatted: List<String>,
    ): ChestParseResult {
        // Already-bought chests (from a prior session) keep their cosmetic
        // lore minus the "Cost" / "Click to open!" lines and gain an "already
        // bought / opened" note. Detect that explicitly so the overlay says
        // something meaningful instead of "no Cost marker in lore".
        if (lorePlain.any {
                val s = it.lowercase()
                "already bought" in s || "already opened" in s
            }) {
            return ChestParseResult.Failure(tierName, "already bought")
        }

        // Verified by /copdev croesusdump — Hypixel's chest tooltip is rigid:
        //   lore[0]            = "Contents"
        //   lore[1..N]         = one line per item
        //   lore[N+1]          = ""  (blank separator)
        //   lore[N+2]          = "Cost"
        //   lore[N+3]          = "<X> Coins"  or  "FREE"
        //   ... NOTE / footer lines after, ignored
        val costIdx = lorePlain.indexOfFirst { it.trim() == "Cost" }
        if (costIdx < 0 || costIdx + 1 >= lorePlain.size) {
            return ChestParseResult.Failure(tierName, "no Cost marker in lore")
        }
        val costLine = lorePlain[costIdx + 1].trim()
        val cost = parseCost(costLine)
            ?: return ChestParseResult.Failure(tierName, "unparseable cost: \"$costLine\"")

        // The blank line right before "Cost" separates loot from cost section.
        val blankIdx = costIdx - 1
        if (blankIdx < 1 || lorePlain[blankIdx].isNotBlank()) {
            return ChestParseResult.Failure(tierName, "no blank separator before Cost (idx=$blankIdx)")
        }
        val lastItem = blankIdx - 1
        // lore[0] is always "Contents" — items start at lore[1].
        val firstItem = 1
        if (firstItem > lastItem) {
            // Chest with no items (shouldn't happen but guard anyway).
            return ChestParseResult.Success(ChestInfo(slot, tierName, colourCode, cost, emptyList(), 0.0))
        }

        val items = mutableListOf<RewardItem>()
        var totalValue = 0.0
        for (i in firstItem..lastItem) {
            val plain = lorePlain.getOrNull(i)?.trim() ?: continue
            if (plain.isEmpty()) continue
            val formatted = loreFormatted.getOrNull(i) ?: plain
            val (id, qty) = tryParseLine(plain, formatted)
            val price = priceFor(id)
            items += RewardItem(id, qty, price, formatted)
            totalValue += price * qty
        }
        val sorted = items.sortedByDescending { it.unitValue * it.qty }
        return ChestParseResult.Success(
            ChestInfo(slot, tierName, colourCode, cost, sorted, totalValue)
        )
    }

    /** Single source of truth for converting an item id to its sell value.
     *  Enchant books use [PriceClient.getEnchantBookPrice] so the smart
     *  ULTIMATE_ fallback kicks in for cases like "Bank" / "Wisdom" where the
     *  plain-text lore doesn't mark them as ultimate but the bazaar id does.
     *
     *  Phase 6 hook: items the user flagged worthless via [CroesusLists] are
     *  treated as zero — the bazaar / AH still has a price but the player
     *  isn't going to bother selling it, so it shouldn't inflate chest
     *  profit calculations. */
    private fun priceFor(id: String): Double {
        if (CroesusLists.isWorthless(id)) return 0.0
        if (id.startsWith("ENCHANTMENT_")) {
            // Split "ENCHANTMENT_<NAME>_<LEVEL>" to feed into getEnchantBookPrice's
            // smart lookup — that handles both ENCHANTMENT_BANK_1 -> tries
            // ENCHANTMENT_ULTIMATE_BANK_1 and ENCHANTMENT_ULTIMATE_COMBO_5.
            val rest = id.removePrefix("ENCHANTMENT_").removePrefix("ULTIMATE_")
            val lastUnderscore = rest.lastIndexOf('_')
            if (lastUnderscore > 0) {
                val name = rest.substring(0, lastUnderscore)
                val lvl = rest.substring(lastUnderscore + 1).toIntOrNull() ?: 1
                PriceClient.getEnchantBookPrice(name, lvl)?.let { return it }
            }
        }
        // Bazaar sellPrice = what you'd actually get if you converted this item
        // to coins right now. If it's 0 the item is genuinely worthless for
        // instant-sale (no buyers) — we do NOT fall back to buyPrice because
        // that's the *seller* side and listing your own sell order at that
        // price is aspirational (could take days, price can move).
        PriceClient.getBazaarSell(id)?.let { return it }
        // Item isn't on bazaar at all — try AH lowest BIN instead.
        PriceClient.getLowestBin(id)?.let { return it }
        // Warm the per-item LBIN cache so subsequent overlay frames get a real
        // number — first call returns 0, the fetch finishes within ~200ms.
        PriceClient.ensureLowestBin(id)
        return 0.0
    }

    private fun parseCost(line: String): Double? {
        if (line.equals(COST_FREE, ignoreCase = true)) return 0.0
        val m = COST_REGEX.matchEntire(line) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    // -- Single-line parsing (book / essence / item) ----------------------------

    /** Returns (skyblockId, qty). Never null — unknown items get a synthetic
     *  upper-snake-case id (e.g. "Power Dragon Shard" → "POWER_DRAGON_SHARD")
     *  so the chest still parses; [priceFor] returns 0 if the bazaar/AH
     *  doesn't know that id, slightly under-estimating the chest's profit
     *  rather than declaring the whole chest unparseable.
     *
     *  Takes both [plain] (formatting stripped, used for most matches and the
     *  items-registry display-name lookup) and [formatted] (with § codes,
     *  needed only to distinguish ultimate from regular enchanted books). */
    private fun tryParseLine(plain: String, formatted: String): Pair<String, Int> {
        tryParseBook(formatted)?.let { return it }
        tryParseEssence(plain)?.let { return it }

        // Ask the items registry for the id by display name. Many regular
        // items have an "x N" suffix we have to peel off first.
        val qtyMatch = Regex("^(.+?) x(\\d+)$").matchEntire(plain)
        val (namePart, qty) = if (qtyMatch != null) {
            qtyMatch.groupValues[1] to qtyMatch.groupValues[2].toInt()
        } else plain to 1

        // Resolve via the registry; if that fails, synthesise a canonical-
        // looking id from the name itself. Many Hypixel items have IDs that
        // are just the display name uppercased with spaces replaced by
        // underscores (e.g. "Power Dragon Shard" → "POWER_DRAGON_SHARD"), so
        // this often hits the bazaar/AH anyway.
        val id = PriceClient.resolveItemId(namePart)
            ?: namePart.uppercase().replace(' ', '_').replace("'", "")
        return id to qty
    }

    private fun tryParseBook(formatted: String): Pair<String, Int>? {
        val m = BOOK_REGEX_FORMATTED.matchEntire(formatted) ?: return null
        val ultPrefix = m.groupValues[1]  // "§d§l" if ultimate, else empty
        val rawName = m.groupValues[2]     // "Ultimate Combo" or "Sharpness"
        val tierStr = m.groupValues[3]

        val tier = tierStr.toIntOrNull() ?: decodeRoman(tierStr) ?: return null
        val ultimate = ultPrefix.isNotEmpty()
        val nameUpper = rawName.uppercase().replace(' ', '_')
        // Avoid ULTIMATE_ULTIMATE_X if the name already includes "Ultimate".
        val id = ("ENCHANTMENT_" + (if (ultimate && !nameUpper.startsWith("ULTIMATE_")) "ULTIMATE_" else "") + nameUpper + "_$tier")
            .replace("ULTIMATE_ULTIMATE_", "ULTIMATE_")
        return id to 1
    }

    private fun tryParseEssence(plain: String): Pair<String, Int>? {
        val m = ESSENCE_REGEX.matchEntire(plain.trim()) ?: return null
        val type = m.groupValues[1].uppercase()
        val qty = m.groupValues[2].toIntOrNull() ?: 1
        return "ESSENCE_$type" to qty
    }

    private fun decodeRoman(numeral: String): Int? {
        if (numeral.isEmpty() || numeral.any { it !in NUMERAL_VALUES }) return null
        var sum = 0
        var i = 0
        while (i < numeral.length) {
            val curr = NUMERAL_VALUES[numeral[i]]!!
            val next = if (i + 1 < numeral.length) NUMERAL_VALUES[numeral[i + 1]] ?: 0 else 0
            if (curr < next) { sum += next - curr; i += 2 } else { sum += curr; i++ }
        }
        return sum
    }

    // -- Helpers ----------------------------------------------------------------

    /** Lore lines of a slot's item, with § formatting codes preserved.
     *  Needed only for the ultimate-book detection (`§d§l` prefix). */
    fun loreFormatted(menu: AbstractContainerMenu, slotIndex: Int): List<String>? {
        val stack = menu.slots.getOrNull(slotIndex)?.item ?: return null
        if (stack.isEmpty) return null
        val lore = stack.get(DataComponents.LORE) ?: return null
        return lore.lines.map { it.formattedString }
    }

    /** Lore lines of a slot's item with all formatting stripped — what we use
     *  for marker detection and item-name lookups. */
    fun lorePlain(menu: AbstractContainerMenu, slotIndex: Int): List<String>? {
        val stack = menu.slots.getOrNull(slotIndex)?.item ?: return null
        if (stack.isEmpty) return null
        val lore = stack.get(DataComponents.LORE) ?: return null
        return lore.lines.map { it.string }
    }
}
