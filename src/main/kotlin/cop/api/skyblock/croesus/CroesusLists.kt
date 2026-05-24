package cop.api.skyblock.croesus

import cop.config.configList

/**
 * Two persisted item-id lists that shape the Auto Croesus driver's decisions:
 *
 *  - [alwaysBuy]: skyblock IDs whose presence in a chest **forces a claim**
 *    even when the chest's profit is below [cop.module.impl.dungeon.AutoCroesus]'s
 *    Min profit threshold. Use for hand-picked drops you want regardless
 *    of cost (e.g. "WITHER_BLADE", "FIFTH_MASTER_STAR").
 *  - [worthless]: skyblock IDs the price model should value as **zero** when
 *    computing chest profit. Use for items that the bazaar/AH says are
 *    "worth" something but you personally won't bother selling or using
 *    (e.g. "HOT_POTATO_BOOK" if you've got 50 in storage already).
 *
 * Persisted via the existing [cop.config.configList] system —
 * `config/cop/croesus-alwaysbuy.json` and `config/cop/croesus-worthless.json`,
 * auto-saved on mutation, loaded at startup.
 *
 * Stored as uppercase skyblock IDs (Hypixel's canonical form), matched
 * exactly. The `/cop alwaysbuy add` / `/cop worthless add` commands
 * uppercase + trim user input before insertion to avoid case-sensitivity
 * traps.
 */
object CroesusLists {
    val alwaysBuy: MutableList<String> by configList("croesus-alwaysbuy.json")
    val worthless: MutableList<String> by configList("croesus-worthless.json")

    fun isAlwaysBuy(id: String): Boolean = id in alwaysBuy
    fun isWorthless(id: String): Boolean = id in worthless

    /** Does this chest contain at least one item the player flagged always-buy? */
    fun chestHasAlwaysBuy(items: List<RewardItem>): Boolean =
        items.any { it.skyblockId in alwaysBuy }
}
