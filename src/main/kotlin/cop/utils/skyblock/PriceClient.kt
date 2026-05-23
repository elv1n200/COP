package cop.utils.skyblock

import com.google.gson.JsonParser
import cop.CopMod
import cop.CopMod.scope
import cop.utils.WebUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads three public Hypixel-adjacent price/registry endpoints and caches the
 * results in memory for ~30 minutes. Pure read-only utility used by Auto Croesus
 * (and anything else that wants live Skyblock prices).
 *
 * Endpoints
 *  - Bazaar (instant-sell prices for bazaar items)
 *  - Moulberry lowestbin.json (lowest BIN per item id, covers non-bazaar items)
 *  - Hypixel items registry (display-name -> item id, for parsing GUI lore back
 *    into Skyblock IDs)
 *
 * Every cache is concurrent; the refresh is mutex-guarded so a burst of callers
 * doesn't trigger duplicate network fetches. All HTTP work runs on Dispatchers.IO
 * via [CopMod.scope] — call sites get an async callback when the data is ready.
 */
object PriceClient {
    private const val URL_BAZAAR    = "https://api.hypixel.net/skyblock/bazaar"
    private const val URL_LOWESTBIN = "https://moulberry.codes/lowestbin.json"
    private const val URL_ITEMS     = "https://api.hypixel.net/v2/resources/skyblock/items"

    /** Default cache lifetime — matches what AutoCroesus (the CT module we're
     *  porting) uses. Long enough that we don't hammer Hypixel; short enough
     *  that prices stay roughly current across a play session. */
    const val DEFAULT_REFRESH_MS: Long = 30L * 60 * 1000

    private val bazaarSell = ConcurrentHashMap<String, Double>()  // ITEM_ID -> bazaar instant-sell
    private val lowestBin  = ConcurrentHashMap<String, Double>()  // ITEM_ID -> LBIN
    private val nameToId   = ConcurrentHashMap<String, String>()  // lowercased display name -> ITEM_ID

    @Volatile private var lastRefreshedAt = 0L
    @Volatile var lastError: String? = null; private set
    private val refreshMutex = Mutex()

    val isLoaded: Boolean get() = lastRefreshedAt > 0L
    val lastRefresh: Long get() = lastRefreshedAt
    /** Milliseconds since the last successful refresh (`Long.MAX_VALUE` if never). */
    val ageMs: Long get() = if (lastRefreshedAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastRefreshedAt

    /** Best of (bazaar instant-sell, lowest BIN) for a Skyblock item id, or null
     *  if neither source knows it. */
    fun getPrice(itemId: String): Double? {
        val bz = bazaarSell[itemId]
        val lb = lowestBin[itemId]
        return when {
            bz != null && lb != null -> maxOf(bz, lb)
            bz != null -> bz
            lb != null -> lb
            else -> null
        }
    }

    fun getBazaarSell(itemId: String): Double? = bazaarSell[itemId]
    fun getLowestBin(itemId: String): Double? = lowestBin[itemId]

    /** "Hyperion" -> "HYPERION" (via the Hypixel items registry). Case- and
     *  whitespace-insensitive. Returns null if no exact match. */
    fun resolveItemId(displayName: String): String? =
        nameToId[displayName.trim().lowercase()]

    /** Kick off an async refresh if the cache is older than [maxAgeMs]. [onDone]
     *  fires after the refresh finishes (or immediately if the cache is still
     *  fresh). Callback runs on Dispatchers.IO — schedule UI/chat work via
     *  `mc.execute { ... }` if you need to touch the main thread. */
    fun refreshIfStale(maxAgeMs: Long = DEFAULT_REFRESH_MS, onDone: (() -> Unit)? = null) {
        if (ageMs < maxAgeMs) { onDone?.invoke(); return }
        scope.launch(Dispatchers.IO) {
            refreshMutex.withLock {
                if (ageMs < maxAgeMs) return@withLock  // someone else refreshed while we waited
                runCatching {
                    fetchBazaar()
                    fetchLowestBin()
                    fetchItemRegistry()
                    lastRefreshedAt = System.currentTimeMillis()
                    lastError = null
                }.onFailure {
                    lastError = it.message ?: it.javaClass.simpleName
                    CopMod.logger.error("[cop] PriceClient refresh failed", it)
                }
            }
            onDone?.invoke()
        }
    }

    private fun fetchBazaar() {
        WebUtils.setupConnection(URL_BAZAAR).use { stream ->
            val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
            check(root.get("success")?.asBoolean == true) { "bazaar: success=false" }
            val products = root.getAsJsonObject("products") ?: return
            bazaarSell.clear()
            for ((id, json) in products.entrySet()) {
                val qs = json.asJsonObject.getAsJsonObject("quick_status") ?: continue
                val sell = qs.get("sellPrice")?.asDouble ?: continue
                if (sell > 0) bazaarSell[id] = sell
            }
        }
    }

    private fun fetchLowestBin() {
        WebUtils.setupConnection(URL_LOWESTBIN).use { stream ->
            val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
            lowestBin.clear()
            for ((id, json) in root.entrySet()) {
                val price = json.asDouble
                if (price > 0) lowestBin[id] = price
            }
        }
    }

    private fun fetchItemRegistry() {
        WebUtils.setupConnection(URL_ITEMS).use { stream ->
            val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
            check(root.get("success")?.asBoolean == true) { "items: success=false" }
            val items = root.getAsJsonArray("items") ?: return
            nameToId.clear()
            for (el in items) {
                val obj = el.asJsonObject
                val id = obj.get("id")?.asString ?: continue
                val name = obj.get("name")?.asString ?: continue
                nameToId[name.trim().lowercase()] = id
            }
        }
    }
}
