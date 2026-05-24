package cop.utils.skyblock

import com.google.gson.JsonParser
import cop.CopMod
import cop.CopMod.scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
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

    /** Kick off an async refresh if the cache is older than [maxAgeMs] (set 0 to
     *  force). [onDone] fires after the refresh finishes (or immediately if the
     *  cache is still fresh). Callback runs on Dispatchers.IO — schedule UI/chat
     *  work via `mc.execute { ... }` if you need to touch the main thread. */
    fun refreshIfStale(maxAgeMs: Long = DEFAULT_REFRESH_MS, onDone: (() -> Unit)? = null) {
        if (ageMs < maxAgeMs) { onDone?.invoke(); return }
        scope.launch(Dispatchers.IO) {
            refreshMutex.withLock {
                if (ageMs < maxAgeMs) return@withLock  // someone else refreshed while we waited
                // Each fetch is isolated so one slow/failing endpoint doesn't
                // wipe out the other two — the cache simply keeps whatever
                // succeeded. lastError surfaces the most recent failure for diagnosis.
                val errors = mutableListOf<String>()
                runCatching { fetchBazaar() }      .onFailure { errors += "bazaar: ${it.message ?: it.javaClass.simpleName}" }
                runCatching { fetchLowestBin() }   .onFailure { errors += "lbin: ${it.message ?: it.javaClass.simpleName}" }
                runCatching { fetchItemRegistry() }.onFailure { errors += "items: ${it.message ?: it.javaClass.simpleName}" }
                val anySucceeded = bazaarSell.isNotEmpty() || lowestBin.isNotEmpty() || nameToId.isNotEmpty()
                if (anySucceeded) lastRefreshedAt = System.currentTimeMillis()
                lastError = if (errors.isEmpty()) null else errors.joinToString("; ")
                if (errors.isNotEmpty()) CopMod.logger.warn("[cop] PriceClient partial: $lastError")
            }
            onDone?.invoke()
        }
    }

    /** Opens a GET with a 20s timeout and a friendly User-Agent — some APIs
     *  (and CDN edge nodes) reject the default Java/JRE user agent. Avoids the
     *  shared WebUtils helper because that one defaults to 5s and sets
     *  setDoOutput(true), which can confuse strict servers about GET vs POST. */
    private fun openJsonGet(url: String): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "COP-Mod/1.2.0 (+github.com/elv1n200/COP)")
        return conn
    }

    private fun fetchBazaar() {
        val conn = openJsonGet(URL_BAZAAR)
        conn.inputStream.use { stream ->
            val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
            check(root.get("success")?.asBoolean == true) { "success=false" }
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
        val conn = openJsonGet(URL_LOWESTBIN)
        conn.inputStream.use { stream ->
            val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
            lowestBin.clear()
            for ((id, json) in root.entrySet()) {
                val price = json.asDouble
                if (price > 0) lowestBin[id] = price
            }
        }
    }

    private fun fetchItemRegistry() {
        val conn = openJsonGet(URL_ITEMS)
        conn.inputStream.use { stream ->
            val root = JsonParser.parseReader(InputStreamReader(stream)).asJsonObject
            check(root.get("success")?.asBoolean == true) { "success=false" }
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
