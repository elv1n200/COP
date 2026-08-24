package cop.api.skyblock.croesus

import cop.utils.StringUtils.formattedString
import cop.utils.skyblock.PriceClient
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.AbstractContainerMenu
import java.text.Normalizer
import java.util.Locale

/** Reads the Croesus menus without relying on fixed chest positions. */
object CroesusParser {
    const val LORE_UNCLAIMED_MARKER = "No chests opened yet"
    const val BUY_CONFIRM_SLOT = 31
    const val BUY_BACK_SLOT = 49
    const val BUY_REROLL_SLOT = 50
    const val RUN_BACK_SLOT = 30

    private val tiers = listOf("Wood", "Gold", "Diamond", "Emerald", "Obsidian", "Bedrock")
    private val tierColours = mapOf(
        "Wood" to "§7",
        "Gold" to "§6",
        "Diamond" to "§b",
        "Emerald" to "§a",
        "Obsidian" to "§5",
        "Bedrock" to "§c",
    )

    private enum class LoreState {
        FIND_CONTENTS,
        READ_CONTENTS,
        READ_COST,
        COMPLETE,
    }

    private data class TextToken(
        val value: String,
        val start: Int,
        val endExclusive: Int,
    )

    private data class RewardSource(
        val plain: String,
        val formatted: String,
    )

    private sealed interface ReadResult<out T> {
        data class Value<T>(val value: T) : ReadResult<T>
        data class Problem(val reason: String) : ReadResult<Nothing>
    }

    fun inCroesusMenu(screen: Screen?): Boolean =
        containerTitle(screen)?.equals("Croesus", ignoreCase = true) == true

    fun inRunMenu(screen: Screen?): Boolean {
        val title = containerTitle(screen) ?: return false
        return hasNonEmptySuffix(title, "Catacombs - ") ||
            hasNonEmptySuffix(title, "Master Catacombs - ")
    }

    fun inBuyConfirmMenu(screen: Screen?): Boolean {
        val title = containerTitle(screen) ?: return false
        return exactTier(title) != null
    }

    /**
     * The confirm item arrives after its screen. A missing/empty slot therefore
     * means "not ready"; a populated but invalid item is a real parse failure.
     */
    fun parseBuyConfirmChest(
        menu: AbstractContainerMenu,
        title: String,
    ): ChestParseResult? {
        val tier = exactTier(title.trim())
            ?: return ChestParseResult.Failure(
                title.trim().ifEmpty { "Unknown" },
                "unexpected buy-confirm title '${title.trim()}'",
            )
        val stack = menu.slots.getOrNull(BUY_CONFIRM_SLOT)?.item ?: return null
        if (stack.isEmpty) return null

        val plainLore = lorePlain(menu, BUY_CONFIRM_SLOT)
            ?: return ChestParseResult.Failure(tier, "confirmation item has no lore")
        val formattedLore = loreFormatted(menu, BUY_CONFIRM_SLOT) ?: plainLore
        val itemName = stack.hoverName.string
        claimedReason(listOf(itemName) + plainLore)?.let { reason ->
            return ChestParseResult.Failure(tier, reason)
        }

        return parseChestLore(
            slot = BUY_CONFIRM_SLOT,
            tier = tier,
            colour = colourForTier(stack.hoverName.formattedString, tier),
            plainLore = plainLore,
            formattedLore = formattedLore,
        )
    }

    /** Returns only top-inventory slots whose run lore is explicitly unopened. */
    fun findUnclaimedRunSlots(menu: AbstractContainerMenu): List<Int> = buildList {
        for (slot in 0 until topInventorySize(menu)) {
            val stack = menu.slots.getOrNull(slot)?.item ?: continue
            if (stack.isEmpty) continue
            val lines = lorePlain(menu, slot) ?: continue
            if (lines.any { LORE_UNCLAIMED_MARKER in it }) add(slot)
        }
    }

    /** Parses every recognised chest icon in slot order. */
    fun parseChests(menu: AbstractContainerMenu): List<ChestParseResult> = buildList {
        for (slot in 0 until topInventorySize(menu)) {
            val stack = menu.slots.getOrNull(slot)?.item ?: continue
            if (stack.isEmpty) continue

            val itemName = stack.hoverName.string
            val tier = tierFromChestIcon(itemName) ?: continue
            val plainLore = lorePlain(menu, slot)
            if (plainLore == null) {
                add(ChestParseResult.Failure(tier, "chest icon has no lore"))
                continue
            }
            claimedReason(listOf(itemName) + plainLore)?.let { reason ->
                add(ChestParseResult.Failure(tier, reason))
                continue
            }
            val formattedLore = loreFormatted(menu, slot) ?: plainLore
            add(
                parseChestLore(
                    slot = slot,
                    tier = tier,
                    colour = colourForTier(stack.hoverName.formattedString, tier),
                    plainLore = plainLore,
                    formattedLore = formattedLore,
                )
            )
        }
    }

    fun loreFormatted(menu: AbstractContainerMenu, slot: Int): List<String>? {
        val stack = menu.slots.getOrNull(slot)?.item ?: return null
        if (stack.isEmpty) return null
        return stack.get(DataComponents.LORE)?.lines?.map { it.formattedString }
    }

    fun lorePlain(menu: AbstractContainerMenu, slot: Int): List<String>? {
        val stack = menu.slots.getOrNull(slot)?.item ?: return null
        if (stack.isEmpty) return null
        return stack.get(DataComponents.LORE)?.lines?.map { it.string }
    }

    private fun parseChestLore(
        slot: Int,
        tier: String,
        colour: String,
        plainLore: List<String>,
        formattedLore: List<String>,
    ): ChestParseResult {
        var state = LoreState.FIND_CONTENTS
        val rewards = mutableListOf<RewardSource>()
        var costLine: String? = null

        for ((index, rawLine) in plainLore.withIndex()) {
            val line = rawLine.trim()
            when (state) {
                LoreState.FIND_CONTENTS -> {
                    if (isSectionHeader(line, "Contents")) state = LoreState.READ_CONTENTS
                }

                LoreState.READ_CONTENTS -> {
                    when {
                        isSectionHeader(line, "Cost") -> state = LoreState.READ_COST
                        line.isNotEmpty() -> rewards += RewardSource(
                            plain = line,
                            formatted = formattedLore.getOrNull(index) ?: rawLine,
                        )
                    }
                }

                LoreState.READ_COST -> {
                    if (line.isNotEmpty()) {
                        costLine = line
                        state = LoreState.COMPLETE
                    }
                }

                LoreState.COMPLETE -> Unit
            }
        }

        when (state) {
            LoreState.FIND_CONTENTS -> return failure(tier, "missing Contents section")
            LoreState.READ_CONTENTS -> return failure(tier, "missing Cost section after Contents")
            LoreState.READ_COST -> return failure(tier, "missing amount after Cost")
            LoreState.COMPLETE -> Unit
        }
        if (rewards.isEmpty()) return failure(tier, "Contents section is empty")

        val cost = when (val parsed = readCost(costLine.orEmpty())) {
            is ReadResult.Value -> parsed.value
            is ReadResult.Problem -> return failure(tier, parsed.reason)
        }

        val parsedRewards = ArrayList<RewardItem>(rewards.size)
        for (source in rewards) {
            when (val parsed = readReward(source)) {
                is ReadResult.Value -> parsedRewards += parsed.value
                is ReadResult.Problem -> return failure(tier, parsed.reason)
            }
        }

        val sortedRewards = parsedRewards.sortedWith(
            compareByDescending<RewardItem> { it.unitValue * it.qty }
                .thenBy { it.skyblockId }
                .thenBy { it.displayName }
        )
        val totalValue = sortedRewards.sumOf { it.unitValue * it.qty }
        return ChestParseResult.Success(
            ChestInfo(
                slot = slot,
                tierName = tier,
                tierColourCode = colour,
                cost = cost,
                items = sortedRewards,
                totalValue = totalValue,
            )
        )
    }

    private fun readCost(line: String): ReadResult<Double> {
        val tokens = whitespaceTokens(line)
        if (tokens.size == 1 && tokens[0].value.equals("FREE", ignoreCase = true)) {
            return ReadResult.Value(0.0)
        }
        if (tokens.size != 2 || !tokens[1].value.equals("Coins", ignoreCase = true)) {
            return ReadResult.Problem("invalid cost '$line'; expected FREE or '<number> Coins'")
        }
        val coins = commaGroupedInteger(tokens[0].value)
            ?: return ReadResult.Problem("invalid coin amount '${tokens[0].value}'")
        return ReadResult.Value(coins.toDouble())
    }

    private fun readReward(source: RewardSource): ReadResult<RewardItem> {
        val tokens = whitespaceTokens(source.plain)
        if (tokens.isEmpty()) return ReadResult.Problem("empty reward line")

        var quantity = 1
        var name = source.plain.trim()
        val suffix = tokens.last()
        if (suffix.value.length > 1 &&
            suffix.value[0].equals('x', ignoreCase = true) &&
            suffix.value.substring(1).all(Char::isDigit)
        ) {
            quantity = suffix.value.substring(1).toIntOrNull()
                ?: return ReadResult.Problem("reward quantity is too large in '${source.plain}'")
            if (quantity <= 0) {
                return ReadResult.Problem("reward quantity must be positive in '${source.plain}'")
            }
            name = source.plain.substring(0, suffix.start).trimEnd()
            if (name.isEmpty()) return ReadResult.Problem("reward name is missing in '${source.plain}'")
        }

        return if (containsIgnoreCase(name, "Enchanted Book")) {
            readEnchantedBook(name, quantity, source.formatted)
        } else {
            readOrdinaryReward(name, quantity, source.formatted)
        }
    }

    private fun readEnchantedBook(
        rewardName: String,
        quantity: Int,
        formatted: String,
    ): ReadResult<RewardItem> {
        val markerAt = rewardName.indexOf("Enchanted Book", ignoreCase = true)
        val openAt = rewardName.indexOf('(', markerAt + "Enchanted Book".length)
        val closeAt = rewardName.indexOfLast { !it.isWhitespace() }
        if (openAt < 0 || closeAt <= openAt || rewardName[closeAt] != ')' ||
            matchingCloseParenthesis(rewardName, openAt) != closeAt
        ) {
            return ReadResult.Problem(
                "enchanted book lacks a parenthesized enchant descriptor in '$rewardName'"
            )
        }

        val descriptor = rewardName.substring(openAt + 1, closeAt).trim()
        val descriptorTokens = whitespaceTokens(descriptor)
        if (descriptorTokens.size < 2) {
            return ReadResult.Problem("invalid enchanted-book descriptor '$descriptor'")
        }

        val rankToken = descriptorTokens.last().value
        val rank = readRank(rankToken)
            ?: return ReadResult.Problem("invalid enchant rank '$rankToken' in '$descriptor'")
        val enchantName = descriptor.substring(0, descriptorTokens.last().start).trimEnd()
        val enchantKey = stableUpperId(enchantName)
        if (enchantKey.isEmpty()) {
            return ReadResult.Problem("enchant name is missing in '$descriptor'")
        }

        val directId = "ENCHANTMENT_${enchantKey}_$rank"
        val ultimateId = if (enchantKey.startsWith("ULTIMATE_")) {
            directId
        } else {
            "ENCHANTMENT_ULTIMATE_${enchantKey}_$rank"
        }
        val itemId = when {
            isBazaarProduct(directId) -> directId
            ultimateId != directId && isBazaarProduct(ultimateId) -> ultimateId
            else -> directId
        }
        val unitValue = if (CroesusLists.isWorthless(itemId)) {
            0.0
        } else {
            val enchantPrice = PriceClient.getEnchantBookPrice(enchantKey, rank)
            if (enchantPrice != null) {
                validPrice(enchantPrice)
            } else {
                marketValue(itemId)
            }
        }
        return ReadResult.Value(
            RewardItem(
                skyblockId = itemId,
                qty = quantity,
                unitValue = unitValue,
                displayName = formatted,
            )
        )
    }

    private fun readOrdinaryReward(
        rewardName: String,
        quantity: Int,
        formatted: String,
    ): ReadResult<RewardItem> {
        val itemId = essenceId(rewardName)
            ?: PriceClient.resolveShardId(rewardName)
            ?: PriceClient.resolveItemId(rewardName)
            ?: stableUpperId(rewardName).ifEmpty { "UNKNOWN_ITEM" }

        val unitValue = if (CroesusLists.isWorthless(itemId)) {
            0.0
        } else {
            marketValue(itemId)
        }
        return ReadResult.Value(
            RewardItem(
                skyblockId = itemId,
                qty = quantity,
                unitValue = unitValue,
                displayName = formatted,
            )
        )
    }

    /** Essence bazaar identifiers put ESSENCE before the visible essence type. */
    private fun essenceId(rewardName: String): String? {
        val tokens = whitespaceTokens(rewardName)
        if (tokens.size < 2 || !tokens.last().value.equals("Essence", ignoreCase = true)) return null
        val type = rewardName.substring(0, tokens.last().start).trimEnd()
        if (type.isEmpty()) return null
        return PriceClient.resolveItemId(rewardName)
            ?: stableUpperId(type).takeIf { it.isNotEmpty() }?.let { "ESSENCE_$it" }
    }

    private fun readRank(token: String): Int? {
        if (token.isNotEmpty() && token.all(Char::isDigit)) {
            return token.toIntOrNull()?.takeIf { it > 0 }
        }
        return romanRank(token)
    }

    /**
     * A reverse fold computes the value; regenerating the canonical spelling
     * rejects malformed subtractive forms such as IIX or VX.
     */
    private fun romanRank(token: String): Int? {
        val roman = token.uppercase(Locale.ROOT)
        if (roman.isEmpty()) return null
        var previous = 0
        var total = 0
        for (index in roman.indices.reversed()) {
            val value = when (roman[index]) {
                'I' -> 1
                'V' -> 5
                'X' -> 10
                'L' -> 50
                'C' -> 100
                'D' -> 500
                'M' -> 1000
                else -> return null
            }
            if (value < previous) total -= value else {
                total += value
                previous = value
            }
            if (total !in 1..3999) return null
        }
        return total.takeIf { canonicalRoman(it) == roman }
    }

    private fun canonicalRoman(number: Int): String {
        var remaining = number
        val result = StringBuilder()
        val symbols = arrayOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD",
            100 to "C", 90 to "XC", 50 to "L", 40 to "XL",
            10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )
        for ((value, symbol) in symbols) {
            while (remaining >= value) {
                result.append(symbol)
                remaining -= value
            }
        }
        return result.toString()
    }

    private fun commaGroupedInteger(text: String): Long? {
        if (text.isEmpty()) return null
        val groups = text.split(',')
        if (groups.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        if (groups.size > 1) {
            if (groups.first().length !in 1..3) return null
            if (groups.drop(1).any { it.length != 3 }) return null
        }
        return groups.joinToString("").toLongOrNull()
    }

    private fun whitespaceTokens(text: String): List<TextToken> = buildList {
        var cursor = 0
        while (cursor < text.length) {
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor >= text.length) break
            val start = cursor
            while (cursor < text.length && !text[cursor].isWhitespace()) cursor++
            add(TextToken(text.substring(start, cursor), start, cursor))
        }
    }

    private fun matchingCloseParenthesis(text: String, openAt: Int): Int {
        var depth = 0
        for (index in openAt until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                    if (depth < 0) return -1
                }
            }
        }
        return -1
    }

    private fun stableUpperId(displayName: String): String {
        val decomposed = Normalizer.normalize(displayName, Normalizer.Form.NFKD)
        val result = StringBuilder(decomposed.length)
        var separatorPending = false
        for (raw in decomposed) {
            val character = raw.uppercaseChar()
            when {
                character in 'A'..'Z' || character in '0'..'9' -> {
                    if (separatorPending && result.isNotEmpty() && result.last() != '_') result.append('_')
                    result.append(character)
                    separatorPending = false
                }
                raw == '\'' || raw == '’' -> Unit
                else -> separatorPending = result.isNotEmpty()
            }
        }
        return result.toString().trimEnd('_')
    }

    private fun validPrice(price: Double?): Double =
        price?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    /** Profit decisions use immediately realisable Bazaar value first. */
    private fun marketValue(itemId: String): Double {
        PriceClient.getBazaarSell(itemId)?.let { return validPrice(it) }
        PriceClient.getLowestBin(itemId)?.let { return validPrice(it) }
        PriceClient.ensureLowestBin(itemId)
        return 0.0
    }

    private fun isBazaarProduct(itemId: String): Boolean =
        PriceClient.getBazaarSell(itemId) != null || PriceClient.getBazaarBuy(itemId) != null

    private fun claimedReason(lines: List<String>): String? {
        for (raw in lines) {
            val line = raw.trim().lowercase(Locale.ROOT)
            val explicitlyAlready = line.contains("already opened") ||
                line.contains("already purchased") ||
                line.contains("already bought") ||
                line.contains("already claimed")
            val chestMarkedComplete = line.contains("chest") &&
                (line.contains("has been opened") ||
                    line.contains("was opened") ||
                    line.contains("purchased") ||
                    line.contains("bought") ||
                    line.contains("claimed"))
            if (explicitlyAlready || chestMarkedComplete ||
                line == "opened" || line == "purchased" || line == "bought" || line == "claimed"
            ) {
                return "chest already opened or purchased"
            }
        }
        return null
    }

    private fun colourForTier(formattedName: String, tier: String): String {
        val tierAt = formattedName.indexOf(tier, ignoreCase = true)
        if (tierAt >= 0) {
            for (index in tierAt - 2 downTo 0) {
                if (formattedName[index] != '§' || index + 1 >= formattedName.length) continue
                val code = formattedName[index + 1].lowercaseChar()
                if (code in "0123456789abcdef") return "§$code"
            }
        }
        return tierColours.getValue(tier)
    }

    private fun tierFromChestIcon(itemName: String): String? {
        exactTier(itemName.trim())?.let { return it }
        val words = alphaNumericWords(itemName)
        if (words.none { it.equals("Chest", ignoreCase = true) }) return null
        return tiers.firstOrNull { tier -> words.any { it.equals(tier, ignoreCase = true) } }
    }

    private fun alphaNumericWords(text: String): List<String> = buildList {
        var cursor = 0
        while (cursor < text.length) {
            while (cursor < text.length && !text[cursor].isLetterOrDigit()) cursor++
            if (cursor >= text.length) break
            val start = cursor
            while (cursor < text.length && text[cursor].isLetterOrDigit()) cursor++
            add(text.substring(start, cursor))
        }
    }

    private fun failure(tier: String, reason: String): ChestParseResult.Failure =
        ChestParseResult.Failure(tier, reason)

    private fun isSectionHeader(line: String, expected: String): Boolean {
        val withoutColon = if (line.endsWith(':')) line.dropLast(1).trimEnd() else line
        return withoutColon.equals(expected, ignoreCase = true)
    }

    private fun exactTier(title: String): String? =
        tiers.firstOrNull { it.equals(title.trim(), ignoreCase = true) }

    private fun containsIgnoreCase(text: String, needle: String): Boolean =
        text.indexOf(needle, ignoreCase = true) >= 0

    private fun hasNonEmptySuffix(title: String, prefix: String): Boolean =
        title.startsWith(prefix, ignoreCase = true) && title.drop(prefix.length).isNotBlank()

    private fun containerTitle(screen: Screen?): String? =
        (screen as? AbstractContainerScreen<*>)?.title?.string?.trim()

    private fun topInventorySize(menu: AbstractContainerMenu): Int =
        (menu.slots.size - 36).coerceAtLeast(0)
}
