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

    /** A run still has unopened chests if any of its tooltip lines is this exact
     *  marker. Hypixel always sends it (with the legacy color prefix). */
    const val LORE_UNCLAIMED_MARKER: String = "§5§o§cNo chests opened yet!"

    private val CHEST_TITLE_REGEX = Regex("^(§.)(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)$")
    /** Hypixel chest cost line: "§5§o§61,234 Coins" (the §5§o is the lore-prefix
     *  Minecraft adds to all enchanted-item lore lines). */
    private val COST_REGEX = Regex("^§5§o§6([\\d,]+) Coins$")

    private val BOOK_REGEX = Regex(
        // §5§oEnchanted Book (§d§lUltimate Combo VI§a)  /  §5§oEnchanted Book (Sharpness VII§a)
        "^(?:§.)*Enchanted Book \\((§d§l)?([\\w ]+) (\\w+)(?:§.)*\\)\$"
    )
    private val ESSENCE_REGEX = Regex("^§5§o§d(\\w+) Essence §8x(\\d+)$")
    private val NUMERAL_VALUES = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    /** Run-sub-menu titles. Hypixel formats both regular and master mode this way. */
    private val RUN_TITLE_REGEX = Regex("^(?:Master )?Catacombs - .+$")

    // -- GUI detection ----------------------------------------------------------

    fun inCroesusMenu(screen: Screen?): Boolean =
        screen is AbstractContainerScreen<*> && screen.title.string.trim() == "Croesus"

    fun inRunMenu(screen: Screen?): Boolean =
        screen is AbstractContainerScreen<*> && RUN_TITLE_REGEX.matches(screen.title.string.trim())

    // -- Run-selection screen ---------------------------------------------------

    /** Slot indices on the top-level Croesus screen whose tooltip says
     *  "No chests opened yet!". The overlay uses these to highlight runs
     *  the user hasn't claimed yet. */
    fun findUnclaimedRunSlots(menu: AbstractContainerMenu): List<Int> {
        val out = mutableListOf<Int>()
        // The run icons live in the upper portion of the chest GUI; iterate
        // every slot smaller than the player-inventory boundary so we still
        // catch runs on later pages without hardcoding a page layout.
        val end = (menu.slots.size - 36).coerceAtMost(54)
        for (i in 0 until end) {
            val lore = loreFormatted(menu, i) ?: continue
            if (lore.any { it == LORE_UNCLAIMED_MARKER }) out += i
        }
        return out
    }

    // -- Run sub-screen ---------------------------------------------------------

    /** Parse every chest tier icon on the current run sub-screen.
     *  Returns one entry per successfully-parsed tier; tiers whose tooltip
     *  failed to parse become a [ChestParseResult.Failure] so the overlay
     *  can show "?" instead of silently dropping them. */
    fun parseChests(menu: AbstractContainerMenu): List<ChestParseResult> {
        val results = mutableListOf<ChestParseResult>()
        // Iterate the top three rows (0..26) — chest tier icons can sit in
        // various positions across floors, so we filter by regex on the name.
        val end = (menu.slots.size - 36).coerceAtMost(27)
        for (i in 0 until end) {
            val slot = menu.slots.getOrNull(i) ?: continue
            val stack = slot.item ?: continue
            if (stack.isEmpty) continue

            val nameFormatted = stack.hoverName.formattedString
            val titleMatch = CHEST_TITLE_REGEX.matchEntire(nameFormatted) ?: continue
            val (_, colourCode, tierName) = titleMatch.destructured

            val lore = loreFormatted(menu, i) ?: run {
                results += ChestParseResult.Failure(tierName, "no lore"); continue
            }
            results += parseChestLore(i, tierName, colourCode, lore)
        }
        return results
    }

    private fun parseChestLore(
        slot: Int,
        tierName: String,
        colourCode: String,
        lore: List<String>,
    ): ChestParseResult {
        // Hypixel's chest tooltip layout, in order:
        //   [0] §5§o(blank or header)
        //   [1] §5§o (separator)
        //   [2..lootEnd-1]  one line per item (formatted)
        //   [lootEnd]       §5§o (blank separator marking end of loot)
        //   ...later...     §5§o§7Cost
        //                   <cost line>
        val lootEnd = lore.indexOf("§5§o")
        val costIdx = lore.indexOf("§5§o§7Cost")
        if (lootEnd < 0 || costIdx < 0 || costIdx + 1 >= lore.size) {
            return ChestParseResult.Failure(tierName, "no loot end / no cost marker")
        }

        val costLine = lore[costIdx + 1]
        val cost = parseCost(costLine)
            ?: return ChestParseResult.Failure(tierName, "unparseable cost: $costLine")

        val itemLines = lore.subList(2, lootEnd)
        val items = mutableListOf<RewardItem>()
        var totalValue = 0.0
        for (line in itemLines) {
            val (id, qty) = tryParseLine(line)
                ?: return ChestParseResult.Failure(tierName, "unparseable line: ${line.noControlCodes}")
            val price = PriceClient.getBazaarSell(id)
                ?: PriceClient.getLowestBin(id)
                ?: 0.0  // unknown -> treat as 0 (we still warm the LBIN cache below)
            if (PriceClient.getLowestBin(id) == null) PriceClient.ensureLowestBin(id)
            items += RewardItem(id, qty, price, line)
            totalValue += price * qty
        }
        val sorted = items.sortedByDescending { it.unitValue * it.qty }
        return ChestParseResult.Success(
            ChestInfo(slot, tierName, colourCode, cost, sorted, totalValue)
        )
    }

    private fun parseCost(line: String): Double? {
        if (line == "§5§o§aFREE") return 0.0
        val m = COST_REGEX.matchEntire(line) ?: return null
        return m.groupValues[1].replace(",", "").toDoubleOrNull()
    }

    // -- Single-line parsing (book / essence / item) ----------------------------

    /** Returns (skyblockId, qty) or null if the line is something we don't
     *  recognise (the overlay will show a "?" for that chest). */
    private fun tryParseLine(line: String): Pair<String, Int>? {
        tryParseBook(line)?.let { return it }
        tryParseEssence(line)?.let { return it }

        // Last resort: strip formatting and ask the items registry for the id.
        // Many regular items have a "x N" suffix we have to peel off first.
        val plain = line.noControlCodes.trim()
        val qtyMatch = Regex("^(.+?) x(\\d+)$").matchEntire(plain)
        val (namePart, qty) = if (qtyMatch != null) {
            qtyMatch.groupValues[1] to qtyMatch.groupValues[2].toInt()
        } else plain to 1

        val id = PriceClient.resolveItemId(namePart) ?: return null
        return id to qty
    }

    private fun tryParseBook(line: String): Pair<String, Int>? {
        val m = BOOK_REGEX.matchEntire(line) ?: return null
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

    private fun tryParseEssence(line: String): Pair<String, Int>? {
        val m = ESSENCE_REGEX.matchEntire(line) ?: return null
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

    /** Lore lines of a slot's item, with § formatting codes preserved (so the
     *  Hypixel-shaped regexes here can match them). */
    private fun loreFormatted(menu: AbstractContainerMenu, slotIndex: Int): List<String>? {
        val stack = menu.slots.getOrNull(slotIndex)?.item ?: return null
        if (stack.isEmpty) return null
        val lore = stack.get(DataComponents.LORE) ?: return null
        return lore.lines.map { it.formattedString }
    }
}
