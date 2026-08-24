package cop.utils.skyblock

import com.google.gson.JsonParser
import cop.CopMod
import cop.CopMod.scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads three public Hypixel-adjacent price/registry endpoints and caches the
 * results in memory for ~30 minutes. Pure read-only utility used by Auto Croesus
 * (and anything else that wants live Skyblock prices).
 *
 * Endpoints
 *  - Bazaar (instant-sell prices for bazaar items)
 *  - SkyCofl per-item lowest BIN (covers non-bazaar items)
 *  - Hypixel items registry (display-name -> item id, for parsing GUI lore back
 *    into Skyblock IDs)
 *
 * Every cache is concurrent; the refresh is mutex-guarded so a burst of callers
 * doesn't trigger duplicate network fetches. All HTTP work runs on Dispatchers.IO
 * via [CopMod.scope] — call sites get an async callback when the data is ready.
 */
object PriceClient {
    private const val URL_BAZAAR        = "https://api.hypixel.net/skyblock/bazaar"
    private const val URL_ITEMS         = "https://api.hypixel.net/v2/resources/skyblock/items"
    /** Per-item SkyCofl BIN endpoint — primary (and now sole) LBIN source.
     *  Moulberry's bulk lowestbin.json was dropped: it's been intermittently
     *  dead for a long time and the noise outweighed any benefit. SkyCofl is
     *  per-item (~200ms each) but caches per-id with a TTL, so a Croesus run's
     *  ~5 unique items per chest is well inside acceptable latency.
     *  Returns a JSON array of all active BIN auctions for the given tag;
     *  lowest BIN per unit = min(startingBid / count). */
    private const val URL_SKYCOFL_BIN_F = "https://sky.coflnet.com/api/auctions/tag/%s/active/bin"

    /** Default cache lifetime for the bulk sources (Bazaar + items registry).
     *  Auctions move faster than that — per-item LBIN uses its own shorter TTL. */
    const val DEFAULT_REFRESH_MS: Long = 30L * 60 * 1000
    /** Per-item LBIN TTL — auctions can change in minutes, so we don't trust
     *  a cached value much longer than this. */
    private const val LBIN_TTL_MS: Long = 10L * 60 * 1000
    private const val FAILURE_BACKOFF_MS: Long = 30L * 1000
    private const val MAX_PENDING_LBIN_REQUESTS = 64
    private val ITEM_ID_PATTERN = Regex("^[A-Za-z0-9_:-]{1,128}$")

    @Volatile private var bazaarSell = ConcurrentHashMap<String, Double>()  // ITEM_ID -> bazaar instant-sell
    @Volatile private var bazaarBuy  = ConcurrentHashMap<String, Double>()  // ITEM_ID -> bazaar instant-buy (price sellers list at)
    private val lowestBin  = ConcurrentHashMap<String, Double>()  // ITEM_ID -> LBIN (from any source)
    private val lowestBinFetchedAt = ConcurrentHashMap<String, Long>()  // per-id fetch time for TTL
    private val lbinInFlight = ConcurrentHashMap.newKeySet<String>()    // dedupe concurrent SkyCofl fetches
    private val lbinRetryAfter = ConcurrentHashMap<String, Long>()
    private val lbinSemaphore = Semaphore(4)
    private val lbinPendingSlots = Semaphore(MAX_PENDING_LBIN_REQUESTS)
    @Volatile private var nameToId   = ConcurrentHashMap<String, String>()  // lowercased display name -> ITEM_ID

    @Volatile private var bazaarRefreshedAt = 0L
    @Volatile private var itemRegistryRefreshedAt = 0L
    @Volatile private var refreshRetryAfter = 0L
    @Volatile var lastError: String? = null; private set
    private val refreshLock = Any()
    private var refreshInFlight = false
    private val refreshCallbacks = mutableListOf<() -> Unit>()

    val isLoaded: Boolean get() = bazaarRefreshedAt > 0L && itemRegistryRefreshedAt > 0L
    val lastRefresh: Long
        get() = if (isLoaded) minOf(bazaarRefreshedAt, itemRegistryRefreshedAt) else 0L
    /** Milliseconds since the last successful refresh (`Long.MAX_VALUE` if never). */
    val ageMs: Long get() = ageOf(lastRefresh)
    private val bazaarAgeMs: Long get() = ageOf(bazaarRefreshedAt)
    private val itemRegistryAgeMs: Long get() = ageOf(itemRegistryRefreshedAt)

    private fun ageOf(timestamp: Long): Long =
        if (timestamp == 0L) Long.MAX_VALUE else (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)

    /** Best of (bazaar instant-sell, lowest BIN) for a Skyblock item id, or null
     *  if neither source knows it. */
    fun getPrice(itemId: String): Double? {
        val bz = getBazaarSell(itemId)
        val lb = getLowestBin(itemId)
        return when {
            bz != null && lb != null -> maxOf(bz, lb)
            bz != null -> bz
            lb != null -> lb
            else -> null
        }
    }

    fun getBazaarSell(itemId: String): Double? =
        bazaarSell[itemId].takeIf { bazaarAgeMs < DEFAULT_REFRESH_MS }
    /** Bazaar **buy** price — what a sell-order is listed at, i.e. the price
     *  someone has to pay to instant-buy. Useful as a fallback when [getBazaarSell]
     *  is 0 (no active buy orders): the item still has a real market value, you
     *  just have to be patient and post a sell order yourself near this number. */
    fun getBazaarBuy(itemId: String): Double? =
        bazaarBuy[itemId].takeIf { bazaarAgeMs < DEFAULT_REFRESH_MS }
    fun getLowestBin(itemId: String): Double? {
        val fetchedAt = lowestBinFetchedAt[itemId] ?: return null
        if (System.currentTimeMillis() - fetchedAt >= LBIN_TTL_MS) return null
        return lowestBin[itemId]
    }

    /** "Hyperion" -> "HYPERION" (via the Hypixel items registry). Case- and
     *  whitespace-insensitive. Returns null if no exact match.
     *
     *  Note: the items registry only contains *base* items (Enchanted Book, Hyperion,
     *  Aspect of the Dragons, ...). Enchantment-book IDs like ENCHANTMENT_COMBO_6 or
     *  reforges aren't in the registry — callers that need those must build the
     *  id themselves from the Skyblock enchantments NBT. */
    fun resolveItemId(displayName: String): String? =
        nameToId[displayName.trim().lowercase()].takeIf { itemRegistryAgeMs < DEFAULT_REFRESH_MS }

    /** Map a Galatea / Hunting-Box shard display name to its bazaar id.
     *  `"Power Dragon Shard"` -> `"SHARD_POWER_DRAGON"`. The items-registry
     *  endpoint doesn't include these (it stops at the pre-Galatea era), so
     *  [resolveItemId] returns null for them and CroesusParser's generic
     *  uppercase synthesis ends up with `POWER_DRAGON_SHARD` (suffix instead
     *  of prefix) — not what's on the bazaar. We strip the literal "Shard"/
     *  "Shards" suffix, prefix with `SHARD_`, and only return the candidate
     *  if the bazaar actually lists it. Returns null otherwise so callers fall
     *  through to the next resolution strategy. */
    fun resolveShardId(displayName: String): String? {
        if (bazaarAgeMs >= DEFAULT_REFRESH_MS) return null
        val trimmed = displayName.trim()
        val withoutSuffix = when {
            trimmed.endsWith(" Shards", ignoreCase = true) -> trimmed.dropLast(7).trim()
            trimmed.endsWith(" Shard", ignoreCase = true)  -> trimmed.dropLast(6).trim()
            else -> return null
        }
        if (withoutSuffix.isEmpty()) return null
        val candidate = "SHARD_" + withoutSuffix.uppercase().replace(' ', '_').replace("'", "")
        return if (bazaarSell.containsKey(candidate) || bazaarBuy.containsKey(candidate)) candidate else null
    }

    /** Human-readable price, the way Hypixel itself shows coins. Comma-grouped
     *  integers for anything below 1M, then 1.23M / 1.23B / 1.23T for larger
     *  amounts so chest profits don't fill the screen with digits.
     *  Locked to Locale.US so German/locale-comma-as-decimal-separator doesn't
     *  turn "505.00M" into "505,00M".
     *  e.g. 1341.09 -> "1,341"; 505_000_000.0 -> "505.00M"; 12_345_678.0 -> "12.35M". */
    fun formatPrice(price: Double): String {
        val abs = kotlin.math.abs(price)
        val l = java.util.Locale.US
        return when {
            abs >= 1_000_000_000_000.0 -> "%.2fT".format(l, price / 1_000_000_000_000.0)
            abs >= 1_000_000_000.0     -> "%.2fB".format(l, price / 1_000_000_000.0)
            abs >= 1_000_000.0         -> "%.2fM".format(l, price / 1_000_000.0)
            else                       -> "%,d".format(l, price.toLong())
        }
    }

    // --- Enchant book pricing ----------------------------------------------
    // Enchant books are on the BAZAAR (not the AH) with ids of the shape
    // `ENCHANTMENT_<NAME>_<LEVEL>`. Ultimate enchants keep the `ULTIMATE_`
    // prefix baked into the name (e.g. ENCHANTMENT_ULTIMATE_COMBO_5). NBT keys
    // already include that prefix (`ultimate_combo: 5`), so building the bazaar
    // id is just `"ENCHANTMENT_${nbtKey.uppercase()}_$level"`. We don't need a
    // separate cache, separate fetch, or any AH path — the bulk bazaar refresh
    // already has all 762 of them populated as soon as it runs.

    /** Bazaar instant-sell price for the enchant book matching the given NBT
     *  enchant key (e.g. `sharpness`, `ultimate_combo`) at the given level.
     *  Returns 0 if the book is on bazaar but no one's currently buying
     *  (genuinely worthless for instant-sale), or null if the book isn't on
     *  bazaar at all (caller may want to try the AH).
     *
     *  Convenience: if the caller dropped the `ULTIMATE_` prefix (typing `combo`
     *  for an ultimate enchant), we try the ultimate id as a fallback so
     *  `getEnchantBookPrice("combo", 5)` still works. */
    fun getEnchantBookPrice(enchantName: String, level: Int): Double? {
        if (bazaarAgeMs >= DEFAULT_REFRESH_MS) return null
        val name = enchantName.uppercase()
        bazaarSell["ENCHANTMENT_${name}_$level"]?.let { return it }
        if (!name.startsWith("ULTIMATE_")) {
            bazaarSell["ENCHANTMENT_ULTIMATE_${name}_$level"]?.let { return it }
        }
        return null
    }

    /** Best-effort: ensure this item's LBIN is fresh in cache. Fire-and-forget;
     *  if data is older than [LBIN_TTL_MS] (or missing entirely), kicks off a
     *  SkyCofl fetch on a background coroutine. Designed to be called every
     *  frame by the overlay — cheap when cached, deduped when in flight. */
    fun ensureLowestBin(itemId: String) {
        if (!ITEM_ID_PATTERN.matches(itemId)) return
        val now = System.currentTimeMillis()
        val age = lowestBinFetchedAt[itemId] ?: 0L
        if (now - age < LBIN_TTL_MS || (lbinRetryAfter[itemId] ?: 0L) > now) return
        if (!lbinInFlight.add(itemId)) return  // a fetch is already in flight
        if (!lbinPendingSlots.tryAcquire()) {
            lbinInFlight.remove(itemId)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val price = fetchSkyCoflLowestBin(itemId)
                if (price == null) lowestBin.remove(itemId) else lowestBin[itemId] = price
                lowestBinFetchedAt[itemId] = System.currentTimeMillis()
                lbinRetryAfter.remove(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lbinRetryAfter[itemId] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                CopMod.logger.warn("[cop] SkyCofl LBIN fetch failed for $itemId: ${e.message}")
            } finally {
                lbinInFlight.remove(itemId)
                lbinPendingSlots.release()
            }
        }
    }

    /** Synchronous-ish per-item LBIN fetch with a callback. Honours the TTL —
     *  if the cached value is still fresh, [onDone] fires immediately with it.
     *  Used by /copdev pricetest. */
    fun fetchLowestBin(itemId: String, force: Boolean = false, onDone: (Double?) -> Unit) {
        if (!ITEM_ID_PATTERN.matches(itemId)) {
            onDone(null)
            return
        }
        val cached = lowestBin[itemId]
        val now = System.currentTimeMillis()
        val fetchedAt = lowestBinFetchedAt[itemId] ?: 0L
        if (!force && now - fetchedAt < LBIN_TTL_MS) {
            onDone(cached)
            return
        }
        if (!force && (lbinRetryAfter[itemId] ?: 0L) > now) {
            onDone(null)
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = try {
                val price = fetchSkyCoflLowestBin(itemId)
                if (price == null) lowestBin.remove(itemId) else lowestBin[itemId] = price
                lowestBinFetchedAt[itemId] = System.currentTimeMillis()
                lbinRetryAfter.remove(itemId)
                price
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lbinRetryAfter[itemId] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                CopMod.logger.warn("[cop] SkyCofl LBIN fetch failed for $itemId: ${e.message}")
                cached
            }
            onDone(result)
        }
    }

    /** Kick off an async refresh if the cache is older than [maxAgeMs] (set 0 to
     *  force). [onDone] fires after the refresh finishes (or immediately if the
     *  cache is still fresh). Callback runs on Dispatchers.IO — schedule UI/chat
     *  work via `mc.execute { ... }` if you need to touch the main thread. */
    fun refreshIfStale(maxAgeMs: Long = DEFAULT_REFRESH_MS, onDone: (() -> Unit)? = null) {
        var completeImmediately = false
        val shouldStart = synchronized(refreshLock) {
            if (ageMs < maxAgeMs || System.currentTimeMillis() < refreshRetryAfter) {
                completeImmediately = true
                false
            } else {
                onDone?.let(refreshCallbacks::add)
                if (refreshInFlight) {
                    false
                } else {
                    refreshInFlight = true
                    true
                }
            }
        }
        if (completeImmediately) {
            onDone?.invoke()
            return
        }
        if (!shouldStart) return

        scope.launch(Dispatchers.IO) {
            try {
                if (ageMs < maxAgeMs) return@launch
                // Bulk sources: bazaar prices + the items registry (display-name
                // -> id). LBIN is handled per-item via SkyCofl on demand; there
                // is no bulk LBIN source any more. Each fetch is isolated so a
                // single slow/failing endpoint doesn't blow away the other.
                val errors = mutableListOf<String>()
                if (bazaarAgeMs >= maxAgeMs) {
                    try {
                        fetchBazaar()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors += "bazaar: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                if (itemRegistryAgeMs >= maxAgeMs) {
                    try {
                        fetchItemRegistry()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors += "items: ${e.message ?: e.javaClass.simpleName}"
                    }
                }

                refreshRetryAfter = if (errors.isEmpty()) 0L
                    else System.currentTimeMillis() + FAILURE_BACKOFF_MS
                lastError = if (errors.isEmpty()) null else errors.joinToString("; ")
                if (errors.isNotEmpty()) CopMod.logger.warn("[cop] PriceClient partial: $lastError")
            } finally {
                val callbacks = synchronized(refreshLock) {
                    refreshInFlight = false
                    refreshCallbacks.toList().also { refreshCallbacks.clear() }
                }
                for (callback in callbacks) {
                    try {
                        callback()
                    } catch (e: Exception) {
                        CopMod.logger.warn("[cop] PriceClient callback failed", e)
                    }
                }
            }
        }
    }

    /** Opens a GET with a 20s timeout and a friendly User-Agent — some APIs
     *  (and CDN edge nodes) reject the default Java/JRE user agent. Avoids the
     *  shared WebUtils helper because that one defaults to 5s and sets
     *  setDoOutput(true), which can confuse strict servers about GET vs POST. */
    private fun openJsonGet(url: String): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.doOutput = false
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "COP-Mod/1.2.0 (+github.com/elv1n200/COP)")
        return conn
    }

    private fun fetchBazaar() {
        val conn = openJsonGet(URL_BAZAAR)
        try {
            conn.inputStream.use { stream ->
                val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
                check(root.get("success")?.asBoolean == true) { "success=false" }
                val products = root.getAsJsonObject("products") ?: error("products missing")
                val newSell = HashMap<String, Double>()
                val newBuy = HashMap<String, Double>()
                for ((id, json) in products.entrySet()) {
                    val qs = json.asJsonObject.getAsJsonObject("quick_status") ?: continue
                    // Store BOTH prices even when 0 — for getBazaarSell the
                    // presence-in-map (vs absent) means "is on bazaar at all"
                    // and 0 means "on bazaar, but no buyers right now". That
                    // distinction matters because chest profit should NOT pretend
                    // a book is worth its listed buyPrice (you'd have to wait days
                    // for that, and the price can move) — sellPrice is what you'd
                    // actually get if you converted the item to coins now.
                    qs.get("sellPrice")?.asDouble?.takeIf { it.isFinite() && it >= 0.0 }
                        ?.let { newSell[id] = it }
                    qs.get("buyPrice")?.asDouble?.takeIf { it.isFinite() && it >= 0.0 }
                        ?.let { newBuy[id] = it }
                }
                check(newSell.isNotEmpty() && newBuy.isNotEmpty()) { "empty price response" }
                bazaarSell = ConcurrentHashMap(newSell)
                bazaarBuy = ConcurrentHashMap(newBuy)
                bazaarRefreshedAt = System.currentTimeMillis()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Per-item SkyCofl BIN lookup. Returns the lowest BIN per unit, or null
     *  if the item has no active BIN auctions (or the request failed). */
    private suspend fun fetchSkyCoflLowestBin(itemId: String): Double? = lbinSemaphore.withPermit {
        check(ITEM_ID_PATTERN.matches(itemId)) { "invalid item id" }
        val encodedId = URLEncoder.encode(itemId, StandardCharsets.UTF_8).replace("+", "%20")
        val conn = openJsonGet(URL_SKYCOFL_BIN_F.format(encodedId))
        try {
            conn.inputStream.use { stream ->
                val root = JsonParser.parseReader(InputStreamReader(stream))
                check(root.isJsonArray) { "expected JSON array" }
                var minPerItem = Double.MAX_VALUE
                for (el in root.asJsonArray) {
                    val obj = el.asJsonObject
                    val count = obj.get("count")?.asInt ?: 1
                    val bid = obj.get("startingBid")?.asDouble ?: continue
                    if (count <= 0 || !bid.isFinite() || bid <= 0) continue
                    val perItem = bid / count
                    if (perItem.isFinite() && perItem < minPerItem) minPerItem = perItem
                }
                if (minPerItem != Double.MAX_VALUE) minPerItem else null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchItemRegistry() {
        val conn = openJsonGet(URL_ITEMS)
        try {
            conn.inputStream.use { stream ->
                val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
                check(root.get("success")?.asBoolean == true) { "success=false" }
                val items = root.getAsJsonArray("items") ?: error("items missing")
                val newNameToId = HashMap<String, String>()
                for (el in items) {
                    val obj = el.asJsonObject
                    val id = obj.get("id")?.asString ?: continue
                    val name = obj.get("name")?.asString ?: continue
                    if (ITEM_ID_PATTERN.matches(id)) newNameToId[name.trim().lowercase()] = id
                }
                check(newNameToId.isNotEmpty()) { "empty item registry" }
                nameToId = ConcurrentHashMap(newNameToId)
                itemRegistryRefreshedAt = System.currentTimeMillis()
            }
        } finally {
            conn.disconnect()
        }
    }
}
