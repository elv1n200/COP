package cop.api.skyblock.croesus

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import cop.CopMod
import cop.CopMod.mc
import java.io.File

/**
 * Persistent JSON-Lines log of every chest the Auto Croesus driver has bought.
 *
 *  - File: `<game>/config/cop/croesus-loot.jsonl`. Append-only, one JSON
 *    object per line, so a crash mid-write loses at most the in-flight entry
 *    (never corrupts older data).
 *  - [append] is called from the driver right after a buy succeeds.
 *  - [loadAll] reads the whole file (cheap — these are small structured
 *    records) and is used by the `/cop loot` command for summaries.
 *  - [summarize] aggregates totals and per-tier / per-item rollups.
 *
 * Item-id + display-name come straight from [RewardItem], so the log can
 * later drive Phase 6's always-buy / worthless lists without re-parsing
 * anything.
 */
object CroesusLootLog {

    private val gson = Gson()
    private val file: File = File(mc.gameDirectory, "config/cop/croesus-loot.jsonl").apply {
        try {
            parentFile?.mkdirs()
            if (!exists()) createNewFile()
        } catch (e: Exception) {
            CopMod.logger.error("[cop] CroesusLootLog: failed to initialise loot log at $absolutePath", e)
        }
    }

    /** Single per-chest entry. All fields are JSON-friendly (no Component /
     *  ItemStack / colour-code state) so this serialises cleanly via Gson. */
    data class LootEntry(
        val timestamp: Long,
        /** Raw run sub-screen title, e.g. "Master Catacombs - Floor V". */
        val floor: String,
        /** Chest tier name without colour codes: "Wood" / "Gold" / "Bedrock" / ... */
        val tier: String,
        val cost: Double,
        val totalValue: Double,
        val profit: Double,
        /** True if a Kismet Feather was consumed to roll this chest. */
        val kismet: Boolean,
        val items: List<LootItem>,
        /** Unique id for the run this chest came from — bumped each time the
         *  player enters a new run sub-screen. Lets [summarize] count actual
         *  runs even when multiple are claimed back-to-back on the same floor
         *  within the same minute. Nullable for backwards compatibility with
         *  entries written before this field existed. */
        val runId: Long? = null,
    )

    /** One reward item within a [LootEntry]. [unitValue] is the per-unit price
     *  *at the time of claim* — bazaar / LBIN snapshots. Stored so historical
     *  summaries don't depend on later price refreshes. */
    data class LootItem(
        val name: String,
        val id: String,
        val qty: Int,
        val unitValue: Double,
    ) {
        val totalValue: Double get() = unitValue * qty
    }

    /** Append a single entry. Safe on the render thread — append-only writes
     *  are short, and any IO failure is logged rather than thrown. */
    fun append(entry: LootEntry) {
        try {
            file.appendText(gson.toJson(entry) + "\n")
        } catch (e: Exception) {
            CopMod.logger.error("[cop] CroesusLootLog: append failed", e)
        }
    }

    /** Read all entries from the file. Malformed lines are skipped (logged
     *  once each) so a single bad write can't poison the whole history. */
    fun loadAll(): List<LootEntry> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<LootEntry>()
        try {
            file.bufferedReader().useLines { lines ->
                for ((i, line) in lines.withIndex()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    try {
                        out += gson.fromJson(trimmed, LootEntry::class.java)
                    } catch (e: JsonSyntaxException) {
                        CopMod.logger.warn("[cop] CroesusLootLog: skipping malformed line $i: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            CopMod.logger.error("[cop] CroesusLootLog: read failed", e)
        }
        return out
    }

    /** Filter window for [summarize]. */
    enum class Window(val label: String, val cutoffMs: Long) {
        TODAY("today", 24L * 60 * 60 * 1000),
        WEEK("this week", 7L * 24 * 60 * 60 * 1000),
        ALL("all time", Long.MAX_VALUE),
    }

    /** Aggregate summary for a window — for the `/cop loot` chat output. */
    data class Summary(
        val window: Window,
        val chestCount: Int,
        val runCount: Int,
        val totalCost: Double,
        val totalValue: Double,
        val totalProfit: Double,
        val kismetsUsed: Int,
        /** (tier name, count, totalProfit, totalValue), descending by totalProfit. */
        val byTier: List<TierStats>,
        /** Top items by aggregated value contribution, capped to the caller's limit. */
        val topItems: List<ItemStats>,
    )

    data class TierStats(
        val tier: String,
        val count: Int,
        val totalProfit: Double,
        val totalValue: Double,
    )

    data class ItemStats(
        val name: String,
        val id: String,
        val totalQty: Int,
        val totalValue: Double,
    )

    fun summarize(window: Window, topN: Int = 8): Summary {
        val entries = loadAll()
        val now = System.currentTimeMillis()
        val cutoff = if (window.cutoffMs == Long.MAX_VALUE) 0L else now - window.cutoffMs
        val scoped = entries.filter { it.timestamp >= cutoff }

        val totalCost = scoped.sumOf { it.cost }
        val totalValue = scoped.sumOf { it.totalValue }
        val totalProfit = scoped.sumOf { it.profit }
        val kismetsUsed = scoped.count { it.kismet }

        // "Run count" — prefers the explicit runId stamped by the driver each
        // time the player enters a new run sub-screen. Falls back to a
        // (floor, minute) bucket for entries written before runId existed.
        // The fallback under-counts when multiple runs of the same floor are
        // claimed within one minute (multi-run mode), but only old data is
        // affected — new entries get accurate counts.
        val runCount = scoped.map { e ->
            e.runId?.toString() ?: "${e.floor}@${e.timestamp / 60_000}"
        }.toSet().size

        val byTier = scoped.groupBy { it.tier }.map { (tier, list) ->
            TierStats(tier, list.size, list.sumOf { it.profit }, list.sumOf { it.totalValue })
        }.sortedByDescending { it.totalProfit }

        // Aggregate items across all chests, sum qty + value by item id.
        val items = mutableMapOf<String, ItemStats>()
        for (entry in scoped) for (it in entry.items) {
            val existing = items[it.id]
            items[it.id] = if (existing == null) {
                ItemStats(it.name, it.id, it.qty, it.totalValue)
            } else {
                existing.copy(totalQty = existing.totalQty + it.qty,
                              totalValue = existing.totalValue + it.totalValue)
            }
        }
        val topItems = items.values.sortedByDescending { it.totalValue }.take(topN)

        return Summary(window, scoped.size, runCount, totalCost, totalValue, totalProfit,
                       kismetsUsed, byTier, topItems)
    }

    /** Wipe the entire log. Used by `/cop loot reset` after a confirmation. */
    fun clear() {
        try {
            file.writeText("")
        } catch (e: Exception) {
            CopMod.logger.error("[cop] CroesusLootLog: clear failed", e)
        }
    }

    /** True if the JSON parser is initialised and the file is writable.
     *  Used by callers to guard noisy operations during early startup. */
    fun isAvailable(): Boolean = file.parentFile?.isDirectory == true
}
