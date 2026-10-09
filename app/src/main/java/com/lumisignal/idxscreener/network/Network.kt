package com.lumisignal.idxscreener.network

import com.lumisignal.idxscreener.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.lumisignal.idxscreener.engine.BrokerFlowEngine
import com.lumisignal.idxscreener.security.SecureStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP 429, or a Cloudflare/WAF challenge page (HTTP 403/503 with an HTML body or
 * `cf-mitigated: challenge`). Both mean "slow down", not "this account lacks access",
 * so they are retried with a cooldown instead of being reported as a PRO entitlement failure.
 */
class RateLimitException(val retryAfterMs: Long = 0L, val challenge: Boolean = false) :
    IOException(if (challenge) "Stockbit/Cloudflare meminta jeda (challenge)" else "Rate limit reached")
class HttpStatusException(val status: Int, val responseBody: String? = null) : IOException(
    buildString {
        append("HTTP $status")
        responseBody?.trim()?.take(240)?.takeIf { it.isNotBlank() }?.let { append(": $it") }
    }
)

/**
 * Adaptive request gate (AIMD). Requests start no closer than [spacingMs] apart and at most
 * [maxConcurrent] are in flight. A 429/challenge doubles the spacing and pauses the whole lane;
 * every success shrinks the spacing a little, so the lane converges on the fastest rate the
 * server tolerates instead of a fixed conservative guess. The permit is never held while a
 * caller sleeps after a failure, so one throttled ticker cannot starve the rest.
 */
internal class AdaptiveRateGate(
    private val maxConcurrent: Int,
    private val minSpacingMs: Long,
    initialSpacingMs: Long,
    private val maxSpacingMs: Long
) {
    private val slots = Semaphore(maxConcurrent)
    private val startMutex = Mutex()
    private var spacingMs = initialSpacingMs.toDouble()
    private var lastStart = 0L
    private var pausedUntil = 0L
    private var lastBackoffAt = 0L
    val rateLimitHits = AtomicInteger(0)
    val challengeHits = AtomicInteger(0)
    val completed = AtomicInteger(0)

    suspend fun <T> run(block: suspend () -> T): T = slots.withPermit {
        startMutex.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                val wait = synchronized(this) { maxOf(pausedUntil - now, lastStart + spacingMs.toLong() - now) }
                if (wait <= 0) break
                delay(wait.coerceAtMost(2_000L))
            }
            synchronized(this) { lastStart = System.currentTimeMillis() }
        }
        block()
    }

    fun onSuccess() {
        completed.incrementAndGet()
        synchronized(this) { spacingMs = (spacingMs * 0.95).coerceAtLeast(minSpacingMs.toDouble()) }
    }

    fun onRateLimited(retryAfterMs: Long, challenge: Boolean) {
        if (challenge) challengeHits.incrementAndGet() else rateLimitHits.incrementAndGet()
        synchronized(this) {
            val now = System.currentTimeMillis()
            // Several in-flight requests usually fail together; back off once per burst so a
            // single throttling episode does not ratchet the lane down to its slowest speed.
            if (now - lastBackoffAt > 3_000L) {
                spacingMs = (spacingMs * 1.6).coerceIn(minSpacingMs * 2.0, maxSpacingMs.toDouble())
                lastBackoffAt = now
            }
            val pause = maxOf(retryAfterMs, if (challenge) 8_000L else 1_000L, spacingMs.toLong())
            pausedUntil = maxOf(pausedUntil, now + pause.coerceAtMost(60_000L))
        }
    }

    private var lastSoftAt = 0L

    /** Milder signal for silent throttling (HTTP 200 with an empty payload): no lane pause. */
    fun onSoftThrottle() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now - lastSoftAt > 5_000L) {
                spacingMs = (spacingMs * 1.25).coerceAtMost(maxSpacingMs.toDouble())
                lastSoftAt = now
            }
        }
    }

    fun currentSpacingMs(): Long = synchronized(this) { spacingMs.toLong() }
}

internal object StockbitSessionPolicy {
    fun marketFinished(now: java.time.ZonedDateTime): Boolean =
        now.hour > 16 || (now.hour == 16 && now.minute >= 15)

    fun completedDataBucket(now: java.time.ZonedDateTime): LocalDate =
        if (marketFinished(now)) now.toLocalDate() else now.toLocalDate().minusDays(1)
}

open class HttpClient {
    open suspend fun get(url: String, headers: Map<String, String> = emptyMap(), attempts: Int = 3): String = request("GET", url, null, headers, attempts)
    open suspend fun postEmpty(url: String, headers: Map<String, String> = emptyMap(), attempts: Int = 1): String = request("POST", url, null, headers, attempts)
    open suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), attempts: Int = 2): String =
        request("POST", url, json, mapOf("Content-Type" to "application/json; charset=utf-8") + headers, attempts)
    suspend fun postForm(url: String, fields: Map<String, String>, attempts: Int = 2): String {
        val body = fields.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return request("POST", url, body, mapOf("Content-Type" to "application/x-www-form-urlencoded"), attempts)
    }
    private suspend fun request(method: String, url: String, body: String?, headers: Map<String,String>, attempts: Int): String = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        repeat(attempts) { index ->
            try {
                val connection = URI(url).toURL().openConnection() as HttpURLConnection
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "LumiSignal/1.0 Android")
                headers.forEach { (k,v) -> connection.setRequestProperty(k,v) }
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = connection.responseCode
                val retryMs = connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1_000L) ?: 0L
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = runCatching { stream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                if (code == 429) throw RateLimitException(retryMs)
                if (code !in 200..299) {
                    val mitigated = connection.getHeaderField("cf-mitigated")
                    if ((code == 403 || code == 503) && (mitigated.equals("challenge", true) || looksLikeHtmlChallenge(text))) {
                        throw RateLimitException(maxOf(retryMs, 8_000L), challenge = true)
                    }
                    throw HttpStatusException(code, text)
                }
                return@withContext text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                val retryable = e is RateLimitException || (e is HttpStatusException && e.status >= 500)
                if (index < attempts - 1 && retryable) {
                    val exponential = (750L shl index) + kotlin.random.Random.nextLong(150L, 650L)
                    val serverDelay = (e as? RateLimitException)?.retryAfterMs ?: 0L
                    delay(maxOf(exponential, serverDelay).coerceAtMost(30_000L))
                } else if (index < attempts - 1 && e !is HttpStatusException) {
                    delay((500L shl index) + kotlin.random.Random.nextLong(100L, 400L))
                }
            }
        }
        throw last ?: IOException("Network request failed")
    }
    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    companion object {
        /** Stockbit API errors are JSON; an HTML 403/503 page is a WAF/Cloudflare interstitial. */
        internal fun looksLikeHtmlChallenge(body: String?): Boolean {
            val text = body?.trimStart()?.take(4_000).orEmpty()
            if (text.isEmpty()) return false
            if (text.startsWith("{") || text.startsWith("[")) return false
            return text.startsWith("<") || text.contains("cloudflare", true) ||
                text.contains("Just a moment", true) || text.contains("cf-chl", true) ||
                text.contains("Attention Required", true)
        }
    }
}

/**
 * Retrieves the current IDX equity catalogue from TradingView's Indonesia
 * scanner. The catalogue is discovery-only; authenticated Stockbit history is
 * authoritative for every strategy calculation and audit.
 */
class IdxUniverseRepository(private val http: HttpClient = HttpClient()) {
    suspend fun fetch(): DataResult<UniverseSnapshot> {
        val request = JSONObject()
            .put("filter", JSONArray()
                .put(JSONObject().put("left", "type").put("operation", "equal").put("right", "stock"))
                .put(JSONObject().put("left", "exchange").put("operation", "equal").put("right", "IDX")))
            .put("options", JSONObject().put("lang", "en"))
            .put("symbols", JSONObject()
                .put("query", JSONObject().put("types", JSONArray()))
                .put("tickers", JSONArray()))
            .put("columns", JSONArray(listOf("name", "description", "close", "average_volume_10d_calc", "market_cap_basic")))
            .put("sort", JSONObject().put("sortBy", "average_volume_10d_calc").put("sortOrder", "desc"))
            .put("range", JSONArray(listOf(0, 1000)))

        val failures = mutableListOf<Throwable>()
        for (endpoint in TRADINGVIEW_SCAN_URLS) {
            try {
                val snapshot = parseTradingView(
                    http.postJson(endpoint, request.toString(), tradingViewHeaders(), attempts = 2),
                    if (endpoint.contains("/indonesia/")) "TRADINGVIEW_IDX_LIVE" else "TRADINGVIEW_GLOBAL_IDX_LIVE"
                )
                if (snapshot.tickers.size >= MINIMUM_COMPLETE_UNIVERSE) return DataResult.Success(snapshot)
                failures += IOException("respons $endpoint hanya memuat ${snapshot.tickers.size} ticker")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += e
            }
        }

        // The official IDX directory is a second independent live source. It
        // supplies symbols and company names; Stockbit validates every ticker
        // and supplies all price, value and frequency data used by the screener.
        try {
            val snapshot = parseIdxOfficial(
                http.get(IDX_COMPANY_PROFILE_URL, idxHeaders(), attempts = 2)
            )
            if (snapshot.tickers.size >= MINIMUM_COMPLETE_UNIVERSE) return DataResult.Success(snapshot)
            failures += IOException("respons IDX hanya memuat ${snapshot.tickers.size} ticker")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failures += e
        }

        val last = failures.lastOrNull()
        val rateLimited = failures.any { it is RateLimitException || (it is HttpStatusException && it.status == 429) }
        return if (rateLimited) {
            DataResult.Error("Rate limit sumber IDX universe tercapai. Silakan coba kembali nanti.", last)
        } else {
            DataResult.Error("IDX universe live tidak tersedia setelah mencoba TradingView Indonesia, TradingView Global, dan IDX.", last)
        }
    }

    companion object {
        private val TRADINGVIEW_SCAN_URLS = listOf(
            "https://scanner.tradingview.com/indonesia/scan",
            "https://scanner.tradingview.com/global/scan"
        )
        private const val IDX_COMPANY_PROFILE_URL = "https://www.idx.co.id/primary/ListedCompany/GetCompanyProfiles?start=0&length=9999&code=&language=id-id"
        private const val MINIMUM_COMPLETE_UNIVERSE = 100
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

        private fun tradingViewHeaders() = mapOf(
            "User-Agent" to BROWSER_UA,
            "Origin" to "https://www.tradingview.com",
            "Referer" to "https://www.tradingview.com/markets/stocks-indonesia/market-movers-active/",
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8"
        )

        private fun idxHeaders() = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "https://www.idx.co.id/id/perusahaan-tercatat/profil-perusahaan-tercatat/",
            "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
            "X-Requested-With" to "XMLHttpRequest"
        )

        fun parseTradingView(payload: String, source: String = "TRADINGVIEW_IDX_LIVE"): UniverseSnapshot {
            val root = JSONObject(payload)
            val rows = root.optJSONArray("data") ?: throw IOException("response missing data")
            val found = linkedMapOf<String, UniverseTicker>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val wireSymbol = row.optString("s")
                if (!wireSymbol.startsWith("IDX:")) continue
                val values = row.optJSONArray("d") ?: continue
                val symbol = values.optString(0).trim().uppercase()
                if (!symbol.matches(Regex("^[A-Z][A-Z0-9]{0,11}$"))) continue
                val close = values.optDoubleOrNull(2)
                val averageVolume = values.optDoubleOrNull(3)
                val averageValue = if (close != null && averageVolume != null && close > 0 && averageVolume >= 0) {
                    close * averageVolume
                } else null
                found["$symbol.JK"] = UniverseTicker(
                    ticker = "$symbol.JK",
                    companyName = values.optString(1).ifBlank { symbol },
                    averageVolume10d = averageVolume,
                    estimatedAverageValue10d = averageValue,
                    lastPrice = close
                )
            }
            val sorted = found.values.sortedWith(
                compareByDescending<UniverseTicker> { it.estimatedAverageValue10d ?: -1.0 }
                    .thenBy { it.ticker }
            )
            return UniverseSnapshot(
                tickers = sorted,
                totalCount = root.optInt("totalCount", sorted.size).coerceAtLeast(sorted.size),
                source = source
            )
        }

        fun parseIdxOfficial(payload: String): UniverseSnapshot {
            val root = JSONObject(payload)
            val rows = root.optJSONArray("data") ?: throw IOException("IDX response missing data")
            val found = linkedMapOf<String, UniverseTicker>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val symbol = row.optString("KodeEmiten").trim().uppercase()
                if (!symbol.matches(Regex("^[A-Z][A-Z0-9]{0,11}$"))) continue
                found["$symbol.JK"] = UniverseTicker(
                    ticker = "$symbol.JK",
                    companyName = row.optString("NamaEmiten").ifBlank { symbol },
                    averageVolume10d = null,
                    estimatedAverageValue10d = null
                )
            }
            return UniverseSnapshot(
                tickers = found.values.sortedBy { it.ticker },
                totalCount = root.optInt("recordsTotal", found.size).coerceAtLeast(found.size),
                source = "IDX_OFFICIAL_LIVE"
            )
        }

        private fun JSONArray.optDoubleOrNull(index: Int): Double? {
            if (index >= length() || isNull(index)) return null
            return optDouble(index).takeIf { it.isFinite() }
        }
    }
}

data class TelegramSendResult(val messageId: String)

class TelegramRepository(private val http: HttpClient = HttpClient()) {
    suspend fun validate(botToken: String, chatId: String): DataResult<Unit> {
        return try {
            if (botToken.isBlank() || chatId.isBlank()) return DataResult.Error("Bot Token dan Chat ID wajib diisi.")
            val me = JSONObject(http.get("https://api.telegram.org/bot$botToken/getMe", attempts = 1))
            if (!me.optBoolean("ok")) return DataResult.Error(me.optString("description", "Telegram unauthorized."))
            val chat = JSONObject(http.postForm("https://api.telegram.org/bot$botToken/getChat", mapOf("chat_id" to chatId), attempts = 1))
            if (!chat.optBoolean("ok")) DataResult.Error(chat.optString("description", "Chat ID tidak dapat diakses oleh bot."))
            else DataResult.Success(Unit)
        } catch (e: HttpStatusException) {
            DataResult.Error(telegramError(e.status), e)
        } catch (e: Throwable) {
            DataResult.Error("Telegram unavailable: ${e.message ?: "network error"}", e)
        }
    }

    suspend fun send(botToken: String, chatId: String, message: String): DataResult<TelegramSendResult> {
      return try {
        if (botToken.isBlank() || chatId.isBlank()) return DataResult.Error("Bot Token dan Chat ID wajib diisi.")
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val root = JSONObject(http.postForm(url, mapOf("chat_id" to chatId, "text" to message, "parse_mode" to "HTML", "disable_web_page_preview" to "true")))
        if (!root.optBoolean("ok")) DataResult.Error(root.optString("description", "Telegram request failed."))
        else DataResult.Success(TelegramSendResult(root.getJSONObject("result").optLong("message_id").toString()))
      } catch (e: Throwable) {
        val safe = if (e is HttpStatusException) telegramError(e.status)
            else "Telegram unavailable: ${e.message ?: "network error"}"
        DataResult.Error(safe, e)
      }
    }

    private fun telegramError(status: Int) = when (status) {
        401 -> "Telegram unauthorized. Periksa Bot Token."
        400, 403 -> "Chat ID salah atau bot belum dapat mengirim ke chat tersebut."
        429 -> "Rate limit Telegram tercapai. Silakan coba kembali nanti."
        else -> "Telegram unavailable (HTTP $status)."
    }
}

data class CapturedStockbitSession(val refreshToken: String, val accessToken: String?, val accessExpiresAt: Long?)

internal data class ParsedCorporateAction(
    val active: Boolean?,
    val descriptions: List<String>
)

internal object StockbitBrokerQuery {
    /** Stockbit date ranges are inclusive. A single session is from == to. */
    fun build(from: LocalDate, to: LocalDate, investorType: String): String {
        require(!from.isAfter(to)) { "Broker range start must not be after end" }
        return "transaction_type=TRANSACTION_TYPE_NET&market_board=MARKET_BOARD_REGULER" +
            "&investor_type=INVESTOR_TYPE_$investorType&limit=50&from=$from&to=$to"
    }
}

private enum class StockbitRequestPriority { AUDIT, USER, SCREENING, BROKER_HISTORY }

/**
 * Persistent store for single broker-summary windows. A window whose end date is a completed
 * session never changes, so it is kept for weeks: the next daily screening then needs only the
 * newest session instead of re-downloading twenty days for every candidate.
 */
interface BrokerWindowStore {
    suspend fun read(key: String): String?
    suspend fun write(key: String, ticker: String, payload: String, ttlMs: Long)
}

/** Snapshot of the adaptive request lanes, written to the activity log after a screening run. */
data class StockbitTrafficStats(
    val generalCompleted: Int,
    val brokerCompleted: Int,
    val rateLimitHits: Int,
    val challengeHits: Int,
    val brokerSpacingMs: Long,
    val generalSpacingMs: Long,
    val emptyResponses: Int = 0,
    val emptyRecovered: Int = 0,
    val lastEmptySample: String? = null
)

/** Experimental read-only connector for Stockbit's private web API. */
class StockbitRepository(
    private val secrets: SecureStore,
    private val http: HttpClient = HttpClient(),
    private val windowStore: BrokerWindowStore? = null
) {
    private val refreshMutex = Mutex()
    private val urgentWaiters = AtomicInteger(0)
    private val generalGate = AdaptiveRateGate(maxConcurrent = 4, minSpacingMs = 140L, initialSpacingMs = 175L, maxSpacingMs = 5_000L)
    private val brokerGate = AdaptiveRateGate(maxConcurrent = 2, minSpacingMs = 350L, initialSpacingMs = 600L, maxSpacingMs = 5_000L)
    private val windowMemo = object : LinkedHashMap<String, MemoWindow>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MemoWindow>?): Boolean = size > 8_000
    }
    private val windowInFlight = ConcurrentHashMap<String, CompletableDeferred<BrokerWindow?>>()
    private val jakarta = ZoneId.of("Asia/Jakarta")

    /** Decrypting through AndroidKeyStore on every request is slow and serialised; keep a RAM copy. */
    @Volatile private var cachedAccess: Pair<String, Long>? = null

    private data class MemoWindow(val window: BrokerWindow?, val expiresAt: Long)

    fun hasSession(): Boolean = cachedAccess != null || secrets.get(REFRESH_KEY) != null || secrets.get(ACCESS_KEY) != null
    fun hasSecuritiesSession(): Boolean = secrets.get(SECURITIES_REFRESH_KEY) != null || secrets.get(SECURITIES_ACCESS_KEY) != null

    fun trafficStats(): StockbitTrafficStats = StockbitTrafficStats(
        generalCompleted = generalGate.completed.get(),
        brokerCompleted = brokerGate.completed.get(),
        rateLimitHits = generalGate.rateLimitHits.get() + brokerGate.rateLimitHits.get(),
        challengeHits = generalGate.challengeHits.get() + brokerGate.challengeHits.get(),
        brokerSpacingMs = brokerGate.currentSpacingMs(),
        generalSpacingMs = generalGate.currentSpacingMs(),
        emptyResponses = emptyResponses.get(),
        emptyRecovered = emptyRecovered.get(),
        lastEmptySample = lastEmptySample
    )

    fun storeCapturedSession(session: CapturedStockbitSession): DataResult<Unit> {
        if (!StockbitTokenPolicy.looksLikeJwt(session.refreshToken)) return DataResult.Error("Respons login tidak memuat refresh token Stockbit yang valid.")
        cachedAccess = null
        secrets.put(REFRESH_KEY, session.refreshToken)
        if (session.accessToken != null && StockbitTokenPolicy.looksLikeJwt(session.accessToken)) {
            secrets.put(ACCESS_KEY, session.accessToken)
            val expiry = session.accessExpiresAt ?: StockbitTokenPolicy.jwtExpiry(session.accessToken) ?: (System.currentTimeMillis() / 1000 + 3600)
            secrets.put(ACCESS_EXPIRY_KEY, expiry.toString())
        }
        return DataResult.Success(Unit)
    }

    suspend fun saveManualRefreshToken(token: String): DataResult<Unit> {
        if (!StockbitTokenPolicy.looksLikeJwt(token)) return DataResult.Error("Refresh token Stockbit tidak valid.")
        cachedAccess = null
        secrets.put(REFRESH_KEY, token.trim())
        secrets.remove(ACCESS_KEY); secrets.remove(ACCESS_EXPIRY_KEY)
        return testConnection()
    }

    suspend fun testConnection(): DataResult<Unit> = try {
        // A normal market-data route validates login independently of PRO access.
        authenticatedGet("https://exodus.stockbit.com/company-price-feed/historical/summary/BBCA?page=1", StockbitRequestPriority.USER)
        DataResult.Success(Unit)
    } catch (e: RateLimitException) {
        DataResult.Error("Rate limit Stockbit tercapai. Silakan coba kembali nanti.", e)
    } catch (e: HttpStatusException) {
        if (e.status == 401 || e.status == 403) DataResult.Error("Stockbit session expired.", e)
        else DataResult.Error("Stockbit data source is currently unavailable (HTTP ${e.status}).", e)
    } catch (e: Throwable) {
        DataResult.Error("Stockbit data source is currently unavailable: ${e.message ?: "network error"}", e)
    }

    /**
     * Official Stockbit daily summary. The private endpoint is fixed at twelve
     * rows per page, so Lumi fetches only the pages needed by the 60-session
     * strategy baseline. Rows are returned oldest first.
     */
    suspend fun fetchSeries(ticker: String, companyName: String = ticker, bars: Int = 84): DataResult<MarketSeries> {
        if (!hasSession()) return DataResult.Error("Stockbit wajib. Login terlebih dahulu.")
        return try {
            val symbol = ticker.removeSuffix(".JK").uppercase()
            val rows = mutableListOf<Candle>()
            val pages = ((bars + HISTORICAL_PAGE_SIZE - 1) / HISTORICAL_PAGE_SIZE).coerceIn(1, MAX_HISTORICAL_PAGES)
            val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())
            suspend fun page(number: Int) = parseHistoricalPage(
                authenticatedGet("https://exodus.stockbit.com/company-price-feed/historical/summary/$encoded?page=$number")
            )
            // Page one tells whether the listing is long enough; the remaining pages are
            // independent and are fetched concurrently (the request lane keeps the pacing).
            val first = page(1)
            rows += first
            if (first.size >= HISTORICAL_PAGE_SIZE && pages > 1) {
                val rest = coroutineScope { (2..pages).map { number -> async { page(number) } }.awaitAll() }
                for (parsed in rest) {
                    rows += parsed
                    if (parsed.size < HISTORICAL_PAGE_SIZE) break
                }
            }
            val completed = excludeIncompleteToday(rows.distinctBy { it.epochSeconds }.sortedBy { it.epochSeconds })
                .takeLast(bars)
            if (completed.size < 60) DataResult.Error("Riwayat Stockbit untuk $symbol hanya memuat ${completed.size} sesi; minimal 60 sesi diperlukan.")
            else if (completed.count { it.frequency != null && it.tradedValue != null } < 55) DataResult.Error("Riwayat Stockbit $symbol tidak memuat frekuensi/nilai transaksi yang cukup.")
            else DataResult.Success(MarketSeries("$symbol.JK", companyName.ifBlank { symbol }, "IDR", completed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            DataResult.Error(if (e.status == 401 || e.status == 403) "Stockbit session expired." else "Stockbit historical data unavailable (HTTP ${e.status}).", e)
        } catch (e: Throwable) {
            DataResult.Error("Stockbit historical data unavailable: ${e.message ?: "network error"}", e)
        }
    }

    /**
     * Refreshes an already-complete daily series with Stockbit page one only.
     * The first synchronization still backfills all required pages; following
     * sessions merge only the newest twelve rows into the stored baseline.
     */
    suspend fun refreshSeries(ticker: String, companyName: String, previous: MarketSeries, bars: Int = 72): DataResult<MarketSeries> {
        if (!hasSession()) return DataResult.Error("Stockbit wajib. Login terlebih dahulu.")
        return try {
            val symbol = ticker.removeSuffix(".JK").uppercase()
            val body = authenticatedGet("https://exodus.stockbit.com/company-price-feed/historical/summary/${URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())}?page=1")
            val newest = parseHistoricalPage(body)
            val completed = excludeIncompleteToday((previous.candles + newest)
                .distinctBy { it.epochSeconds }.sortedBy { it.epochSeconds }).takeLast(bars)
            if (completed.size < 60) fetchSeries(ticker, companyName, bars)
            else if (completed.count { it.frequency != null && it.tradedValue != null } < 55) {
                DataResult.Error("Riwayat Stockbit $symbol tidak memuat frekuensi/nilai transaksi yang cukup.")
            } else DataResult.Success(MarketSeries("$symbol.JK", companyName.ifBlank { symbol }, "IDR", completed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            DataResult.Error(if (e.status == 401 || e.status == 403) "Stockbit session/izin data ditolak: ${e.message}" else "Stockbit historical data unavailable: ${e.message}", e)
        } catch (e: Throwable) {
            DataResult.Error("Stockbit historical data unavailable: ${e.message ?: "network error"}", e)
        }
    }

    /** Current-session OHLC snapshot from Stockbit orderbook, used by the one-minute audit. */
    suspend fun fetchSessionCandle(ticker: String): DataResult<Candle> {
        if (!hasSession()) return DataResult.Error("Stockbit wajib. Login terlebih dahulu.")
        return try {
            val symbol = ticker.removeSuffix(".JK").uppercase()
            val body = JSONObject(authenticatedGet("https://exodus.stockbit.com/company-price-feed/v2/orderbook/companies/${URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())}", StockbitRequestPriority.AUDIT))
            val row = body.optJSONObject("data") ?: body
            val close = wireNumber(row.opt("lastprice")) ?: wireNumber(row.opt("close"))
                ?: return DataResult.Error("Harga berjalan Stockbit untuk $symbol tidak tersedia.")
            val open = wireNumber(row.opt("open")) ?: close
            val high = wireNumber(row.opt("high")) ?: close
            val low = wireNumber(row.opt("low")) ?: close
            val volume = wireNumber(row.opt("volume"))?.toLong() ?: 0L
            val value = wireNumber(row.opt("value"))
            val frequency = wireNumber(row.opt("frequency"))?.toLong()
            val now = java.time.ZonedDateTime.now(jakarta)
            val epoch = now.toLocalDate().atStartOfDay(jakarta).toEpochSecond()
            DataResult.Success(Candle(epoch, open, high, low, close, null, volume, value, frequency))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DataResult.Error("Harga berjalan Stockbit tidak tersedia: ${e.message ?: "network error"}", e)
        }
    }

    /** Recent ordered Stockbit prints. Session OHLC remains the fail-safe when this route is empty. */
    suspend fun fetchRecentTrades(ticker: String, limit: Int = 200): DataResult<List<TradeTick>> {
        if (!hasSession()) return DataResult.Error("Stockbit wajib. Login terlebih dahulu.")
        return try {
            val symbol = ticker.removeSuffix(".JK").uppercase()
            val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())
            val url = "https://exodus.stockbit.com/order-trade/running-trade?limit=${limit.coerceIn(20, 500)}&symbols=$encoded&market_board=MARKET_BOARD_REGULER&order_by=1"
            val payload = JSONObject(authenticatedGet(url, StockbitRequestPriority.AUDIT))
            val ticks = parseRunningTrades(payload, symbol)
            if (ticks.isEmpty()) DataResult.Error("Running trade Stockbit $symbol kosong atau belum dapat dipetakan.")
            else DataResult.Success(ticks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            DataResult.Error("Running trade Stockbit tidak tersedia: ${e.message ?: "network error"}", e)
        }
    }

    /** Capability probe distinguishes login success from broker entitlement and live-tape access. */
    suspend fun checkCapabilities(probeTicker: String = "BBCA"): StockbitCapabilitySnapshot {
        val symbol = probeTicker.removeSuffix(".JK").uppercase()
        val connection = testConnection()
        val market = when (connection) {
            is DataResult.Success -> "Connected"
            is DataResult.Error -> "Unavailable"
        }
        if (connection is DataResult.Error) {
            return StockbitCapabilitySnapshot(
                marketData = market,
                brokerSummary = "Unknown",
                runningTrade = "Unknown",
                accountTier = StockbitAccountTier.UNKNOWN,
                accountMessage = connection.userMessage
            )
        }
        val entitlement = probeBrokerEntitlement(symbol)
        val running = try {
            val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())
            authenticatedGet("https://exodus.stockbit.com/order-trade/running-trade?limit=1&symbols=$encoded&order_by=1", StockbitRequestPriority.USER)
            "Available"
        } catch (e: HttpStatusException) { "HTTP ${e.status}" } catch (_: Throwable) { "Unavailable" }
        return StockbitCapabilitySnapshot(
            marketData = market,
            brokerSummary = entitlement.brokerSummary,
            runningTrade = running,
            accountTier = entitlement.tier,
            accountMessage = entitlement.message
        )
    }

    private data class BrokerEntitlement(
        val tier: StockbitAccountTier,
        val brokerSummary: String,
        val message: String
    )

    private suspend fun probeBrokerEntitlement(symbol: String): BrokerEntitlement {
        val query = "transaction_type=TRANSACTION_TYPE_NET&market_board=MARKET_BOARD_REGULER&investor_type=INVESTOR_TYPE_ALL&limit=5&period=BROKER_SUMMARY_PERIOD_LATEST"
        return try {
            val payload = authenticatedGet("https://exodus.stockbit.com/marketdetectors/$symbol?$query", StockbitRequestPriority.USER)
            val summary = JSONObject(payload).optJSONObject("data")?.optJSONObject("broker_summary")
            val hasRows = (summary?.optJSONArray("brokers_buy")?.length() ?: 0) > 0 ||
                (summary?.optJSONArray("brokers_sell")?.length() ?: 0) > 0
            when (StockbitEntitlementPolicy.classify(200, payload, hasRows)) {
                StockbitAccountTier.PRO -> BrokerEntitlement(StockbitAccountTier.PRO, "Available", "Stockbit PRO terverifikasi. Semua 10 strategi terbuka.")
                StockbitAccountTier.NON_PRO -> BrokerEntitlement(StockbitAccountTier.NON_PRO, "PRO required", "Stockbit PRO diperlukan karena 10 strategi memakai konfirmasi broker/bandar.")
                StockbitAccountTier.UNKNOWN -> BrokerEntitlement(StockbitAccountTier.UNKNOWN, "Empty", "Akses PRO belum dapat dipastikan karena Broker Summary BBCA kosong. Coba saat data pasar tersedia.")
            }
        } catch (e: HttpStatusException) {
            when (StockbitEntitlementPolicy.classify(e.status, e.responseBody, false)) {
                StockbitAccountTier.NON_PRO -> BrokerEntitlement(StockbitAccountTier.NON_PRO, "PRO required", "Akun Stockbit ini belum memiliki akses PRO. Upgrade PRO untuk membuka 10 strategi berbasis pergerakan broker/bandar.")
                else -> BrokerEntitlement(StockbitAccountTier.UNKNOWN, "HTTP ${e.status}", "Akses PRO belum dapat diverifikasi (HTTP ${e.status}).")
            }
        } catch (e: Throwable) {
            BrokerEntitlement(StockbitAccountTier.UNKNOWN, "Unavailable", "Akses PRO belum dapat diverifikasi: ${e.message ?: "gangguan jaringan"}.")
        }
    }

    /**
     * Unlocks the separate Stockbit Sekuritas read session. The PIN is used for
     * this single request only and is never persisted or logged by Lumi.
     */
    suspend fun unlockSecurities(pin: String): DataResult<Unit> {
        if (!hasSession()) return DataResult.Error("Login Stockbit utama diperlukan sebelum membuka Portfolio.")
        if (!pin.matches(Regex("^\\d{4,8}$"))) return DataResult.Error("PIN sekuritas harus 4–8 digit.")
        var stage = "meminta grant Sekuritas"
        return try {
            val grantRoot = JSONObject(authenticatedGet("https://exodus.stockbit.com/sekuritas/auth/token"))
            val grantData = grantRoot.optJSONObject("data")
            val target = grantData?.opt("target")?.toString() ?: grantRoot.opt("target")?.toString()
            if (target != null && target != "2") return DataResult.Error("Akun Stockbit memilih backend sekuritas yang belum didukung (target $target).")
            val loginToken = grantData?.optString("token")?.takeIf { it.isNotBlank() }
                ?: findString(grantRoot, setOf("login_token", "logintoken"))
                ?: return DataResult.Error("Stockbit tidak memberikan token pembuka sesi sekuritas.")
            stage = "menukar PIN dengan sesi baca-saja"
            val body = JSONObject().put("login_token", loginToken).put("pin", pin)
            val response = JSONObject(http.postJson("https://carina.stockbit.com/auth/v2/login", body.toString(), carinaPublicHeaders(), 1))
            val access = findCredential(response, "access")
            val refresh = findCredential(response, "refresh")
            if (access == null || refresh == null) return DataResult.Error("Format sesi sekuritas Stockbit belum dikenali; tidak ada credential yang disimpan.")
            secrets.put(SECURITIES_ACCESS_KEY, access)
            secrets.put(SECURITIES_REFRESH_KEY, refresh)
            secrets.put(SECURITIES_ACCESS_EXPIRY_KEY, (StockbitTokenPolicy.jwtExpiry(access) ?: (System.currentTimeMillis() / 1000 + 3600)).toString())
            // `/account` is not a valid session probe for every Stockbit account. The portfolio
            // list is the read actually needed by Lumi and is the route Stockbit's client uses.
            stage = "memvalidasi akses Portfolio"
            try {
                authenticatedSecuritiesGet("https://carina.stockbit.com/portfolio/v2/list")
            } catch (e: HttpStatusException) {
                if (e.status == 401 || e.status == 403) throw e
                // Keep a successfully issued session. Individual portfolio routes report their
                // own availability; a 404 here must not turn a valid token back into "Locked".
            }
            DataResult.Success(Unit)
        } catch (e: HttpStatusException) {
            if (e.status == 401 || e.status == 403) forgetSecurities()
            DataResult.Error(if (e.status == 401 || e.status == 403) "Stockbit menolak saat $stage. Periksa PIN atau buka ulang sesi Stockbit." else "Stockbit gagal saat $stage (HTTP ${e.status}).", e)
        } catch (e: Throwable) {
            forgetSecurities()
            DataResult.Error("Portfolio Stockbit tidak dapat dibuka saat $stage: ${e.message ?: "network error"}", e)
        }
    }

    /** Fetches only read endpoints; Lumi does not contain buy/sell/amend/cancel routes. */
    suspend fun fetchPortfolio(): DataResult<PortfolioSnapshot> {
        if (!hasSecuritiesSession()) return DataResult.Error("Portfolio terkunci. Masukkan PIN sekuritas untuk membuka sesi baca-saja.")
        return try {
            val warnings = mutableListOf<String>()
            suspend fun optional(path: String, label: String): JSONObject? = try {
                JSONObject(authenticatedSecuritiesGet("https://carina.stockbit.com$path"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                warnings += "$label belum tersedia dari sesi ini"
                null
            }
            val summary = optional("/portfolio/v2/summary", "Ringkasan equity")
            val positions = optional("/portfolio/v2/list", "Daftar posisi")
            val cash = optional("/balance/cash", "Saldo kas")
            val cashInfo = optional("/balance/cash/info", "Settlement T+0/T+1/T+2")
            val orders = optional("/order/v2/list", "Open order")
            val history = optional("/history/v3", "Transaction history")
            val realized = optional("/history/realized", "Realized P/L")
            val performance = optional("/history/performance/trade", "Trading performance")

            val combined = listOfNotNull(summary, cash, cashInfo)
            fun number(vararg aliases: String): Double? = combined.firstNotNullOfOrNull { findNumber(it, aliases.toSet()) }
            val parsedHoldings = positions?.let(::parsePortfolioHoldings).orEmpty()
            val snapshot = PortfolioSnapshot(
                totalEquity = number("total_equity", "equity", "total_asset", "net_asset", "net_asset_value", "portfolio_value"),
                cashOnHand = number("available_cash_on_hand", "cash_on_hand", "cash_balance", "available_cash", "cash"),
                buyingPower = number("buying_power", "buying_power_amount", "trading_limit", "available_limit", "trade_limit", "day_trade_buying_power"),
                withdrawableBalance = number("withdrawable_balance", "withdrawable_cash", "cash_withdrawable", "withdrawable", "available_withdrawal", "withdrawal_limit"),
                settlementT0 = number("t0", "t_0", "t_plus_0", "settlement_t0", "cash_t0", "t0_amount"),
                settlementT1 = number("t1", "t_1", "t_plus_1", "settlement_t1", "cash_t1", "t1_amount"),
                settlementT2 = number("t2", "t_2", "t_plus_2", "settlement_t2", "cash_t2", "t2_amount"),
                realizedProfitLoss = realized?.let { findNumbers(it, setOf("realized_profit_loss", "realized_pl", "realized_pnl", "realized_gain", "realised", "profit_loss", "gain_loss", "pl")).takeIf(List<Double>::isNotEmpty)?.sum() },
                tradingPerformancePercent = performance?.let { findNumbers(it, setOf("performance_percent", "return_percent", "gain_loss_percent", "percentage", "percent", "return", "gain", "value")).lastOrNull() },
                holdings = parsedHoldings,
                openOrders = orders?.let(::parsePortfolioOrders).orEmpty(),
                transactions = history?.let(::parsePortfolioTransactions).orEmpty(),
                warnings = buildList {
                    addAll(warnings)
                    if (parsedHoldings.isEmpty() && positions != null) add("Respons posisi diterima, tetapi tidak ada baris kepemilikan yang dapat dipetakan dengan aman")
                    if (combined.all { findNumber(it, setOf("total_equity", "equity", "total_asset", "net_asset", "portfolio_value")) == null }) add("Nama field total equity belum dikenali; nilai tidak ditebak")
                }.distinct()
            )
            DataResult.Success(snapshot)
        } catch (e: HttpStatusException) {
            DataResult.Error(if (e.status == 401 || e.status == 403) "Sesi Portfolio kedaluwarsa. Buka kembali dengan PIN sekuritas." else "Portfolio Stockbit unavailable (HTTP ${e.status}).", e)
        } catch (e: Throwable) {
            DataResult.Error("Portfolio Stockbit unavailable: ${e.message ?: "network error"}", e)
        }
    }

    /**
     * Stockbit market gates used by all ten strategies. Extended detail adds shareholder and
     * trade-book reads, but ranking itself only needs the two fast Stockbit snapshots.
     */
    suspend fun fetchMarketContext(ticker: String, includeExtended: Boolean = true): StockbitMarketContext {
        val symbol = ticker.removeSuffix(".JK").uppercase()
        val warnings = mutableListOf<String>()
        var last: Double? = null; var bid: Double? = null; var offer: Double? = null
        var uma: Boolean? = null; var tradable: Boolean? = null; var marketStatus: String? = null
        var blockingCorporateAction: Boolean? = null
        val notations = mutableListOf<String>()
        val corporate = mutableListOf<String>(); val shareholders = mutableListOf<String>(); val tape = mutableListOf<String>()
        val encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())
        // The three gate reads are independent; request them together and parse in order.
        val (orderbookBody, overviewBody, statusBody) = coroutineScope {
            listOf(
                "https://exodus.stockbit.com/company-price-feed/v2/orderbook/companies/$encodedSymbol",
                "https://exodus.stockbit.com/emitten/$encodedSymbol/info",
                "https://exodus.stockbit.com/corpaction/status?symbol=$encodedSymbol"
            ).map { url ->
                async {
                    try { Result.success(authenticatedGet(url)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Throwable) { Result.failure<String>(e) }
                }
            }.awaitAll()
        }
        try {
            val orderbook = JSONObject(orderbookBody.getOrThrow())
            val row = orderbook.optJSONObject("data") ?: orderbook
            last = findNumber(row, setOf("lastprice", "last_price", "close"))
            bid = firstLadderPrice(row, "bid") ?: findNumber(row, setOf("best_bid", "bestbid", "bid_price"))
            offer = firstLadderPrice(row, "offer") ?: findNumber(row, setOf("best_offer", "bestoffer", "offer_price", "ask_price"))
            uma = findBoolean(row, setOf("uma", "is_uma", "unusual_market_activity"))
            tradable = findBoolean(row, setOf("tradable", "is_tradable"))
            marketStatus = findString(row, setOf("status", "market_status"))
            findStrings(orderbook, setOf("notation", "notations", "special_notation", "special_notations", "company_status"))
                .filter { it.isNotBlank() }.forEach(notations::add)
        } catch (_: Throwable) { warnings += "Orderbook/spread live tidak tersedia" }
        try {
            val overview = JSONObject(overviewBody.getOrThrow())
            val row = overview.optJSONObject("data") ?: overview
            last = last ?: findNumber(row, setOf("price", "lastprice", "last_price", "close"))
            val overviewBook = row.optJSONObject("orderbook")
            bid = bid ?: overviewBook?.optJSONObject("bid")?.let { wireNumber(it.opt("price")) }
            offer = offer ?: overviewBook?.optJSONObject("offer")?.let { wireNumber(it.opt("price")) }
            uma = uma ?: findBoolean(row, setOf("uma", "is_uma"))
            findStrings(row, setOf("notation", "notations", "special_notation", "special_notations"))
                .filter { it.isNotBlank() }.forEach(notations::add)
            if (row.has("corp_action")) {
                val parsed = parseCorporateAction(row.opt("corp_action"))
                blockingCorporateAction = parsed.active
                parsed.descriptions.take(6).forEach(corporate::add)
            }
        } catch (_: Throwable) { warnings += "Company overview/corporate action tidak tersedia" }

        try {
            val status = JSONObject(statusBody.getOrThrow())
            val matchingRows = allObjects(status).filter { row ->
                row.keys().asSequence().any { key ->
                    row.opt(key)?.toString()?.trim()?.uppercase()?.removeSuffix(".JK") == symbol
                }
            }
            matchingRows.forEach { row ->
                uma = uma ?: findBoolean(row, setOf("uma", "is_uma", "unusual_market_activity"))
                tradable = tradable ?: findBoolean(row, setOf("tradable", "is_tradable", "active"))
                findStrings(row, setOf("notation", "notations", "special_notation", "special_notations", "status"))
                    .filter { it.isNotBlank() && !it.equals(symbol, true) }.forEach(notations::add)
            }
        } catch (_: Throwable) { warnings += "Status UMA/notasi Stockbit tidak tersedia" }

        if (includeExtended) {
            try {
                val minted = JSONObject(authenticatedPostEmpty("https://exodus.stockbit.com/emitten-metadata/shareholders/token"))
                val token = minted.optJSONObject("data")?.optString("value")?.takeIf { it.isNotBlank() }
                    ?: throw IOException("shareholder token missing")
                val encoded = URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())
                val url = "https://exodus.stockbit.com/emitten-metadata/shareholders/$encoded/chart?symbol=$encoded&value_year=12&shareholder_type=all"
                val holders = JSONObject(http.get(url, stockbitHeaders(token) + ("Authorization" to token), 1))
                findStrings(holders, setOf("name", "holder", "shareholder_type", "type", "label"))
                    .filter { it.length in 2..80 }.distinct().take(6).forEach(shareholders::add)
            } catch (_: Throwable) { warnings += "Komposisi shareholder tidak tersedia" }
            try {
                val book = JSONObject(authenticatedGet("https://exodus.stockbit.com/order-trade/trade-book?symbol=${URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())}&group_by=1"))
                val rows = allObjects(book).filter { directNumber(it, "price") != null }.take(5)
                rows.forEach { row ->
                    val price = directNumber(row, "price")
                    val volume = directNumber(row, "volume", "lot", "lots", "frequency")
                    if (price != null) tape += "Rp${price.toLong()}${volume?.let { " • ${it.toLong()}" }.orEmpty()}"
                }
            } catch (_: Throwable) { warnings += "Trade book live tidak tersedia" }
        }
        val spread = if (bid != null && offer != null && bid!! > 0 && offer!! >= bid!!) (offer!! - bid!!) / bid!! * 100 else null
        return StockbitMarketContext(
            ticker = "$symbol.JK", lastPrice = last, bestBid = bid, bestOffer = offer,
            spreadPercent = spread, specialNotations = notations.distinct(), uma = uma,
            corporateActions = corporate.distinct(), shareholderSummary = shareholders.distinct(),
            tradeBookSummary = tape.distinct(), warnings = warnings.distinct(),
            spreadTicks = if (bid != null && offer != null) spreadTicks(bid!!, offer!!) else null,
            blockingCorporateAction = blockingCorporateAction, tradable = tradable, marketStatus = marketStatus
        )
    }

    /** One true 10-session aggregate, used to rank broker-heavy candidates before detailed enrichment. */
    suspend fun brokerSeedAnalysis(ticker: String, fromEpochSeconds: Long, toEpochSeconds: Long): BrokerAnalysis {
        if (!hasSession()) return BrokerAnalysis(false, explanation = listOf("Stockbit belum terhubung"), failureKind = BrokerFailureKind.AUTH)
        val symbol = ticker.removeSuffix(".JK").uppercase()
        return try {
            val from = Instant.ofEpochSecond(fromEpochSeconds).atZone(jakarta).toLocalDate()
            val to = Instant.ofEpochSecond(toEpochSeconds).atZone(jakarta).toLocalDate()
            val window = fetchBrokerWindow(symbol, from, to, "ALL")
                ?: return BrokerAnalysis(false, explanation = listOf("Broker summary 10 sesi kosong untuk $symbol ($from s.d. $to)"), failureKind = BrokerFailureKind.EMPTY)
            BrokerFlowEngine.analyzePeriods(
                mapOf(10 to window.day), mapOf(10 to window.buyers), mapOf(10 to window.sellers), null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            brokerFailure(symbol, "Broker seed 10 sesi", e)
        }
    }

    /**
     * Fetches only the aggregate horizons needed by strategies that do not
     * require per-session broker persistence. Horizons are requested in parallel
     * through the broker lane; identical windows are served from the window cache.
     */
    suspend fun brokerPeriodAnalysis(
        ticker: String,
        referenceEpochSeconds: Long,
        periodsRequested: Set<Int> = setOf(1, 3, 5, 10),
        knownSessionEpochSeconds: List<Long> = emptyList()
    ): BrokerAnalysis {
        if (!hasSession()) return BrokerAnalysis(false, explanation = listOf("Stockbit belum terhubung"), failureKind = BrokerFailureKind.AUTH)
        val symbol = ticker.removeSuffix(".JK").uppercase()
        return try {
            val reference = Instant.ofEpochSecond(referenceEpochSeconds).atZone(jakarta).toLocalDate()
            val provenDates = knownSessionEpochSeconds.asSequence()
                .map { Instant.ofEpochSecond(it).atZone(jakarta).toLocalDate() }
                .filter { !it.isAfter(reference) }.distinct().sortedDescending().take(10).toList()
            val sessionDates = if (provenDates.size >= 10) provenDates else previousWeekdays(reference, 10)
            val wanted = periodsRequested.filter { it in setOf(1, 3, 5, 10) }.sorted()
            val periods = fetchPeriods(symbol, sessionDates, wanted, "ALL")
            if (periods.isEmpty()) return BrokerAnalysis(false, explanation = listOf("Broker summary agregat Stockbit kosong untuk $symbol"), failureKind = BrokerFailureKind.EMPTY)
            BrokerFlowEngine.analyzePeriods(
                periods.mapValues { it.value.day },
                periods.mapValues { it.value.buyers },
                periods.mapValues { it.value.sellers },
                null
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            brokerFailure(symbol, "Broker summary agregat", e)
        }
    }

    suspend fun brokerAnalysis(
        ticker: String,
        referenceEpochSeconds: Long,
        dailySessions: Int = 20,
        knownSessionEpochSeconds: List<Long> = emptyList()
    ): BrokerAnalysis {
        if (!hasSession()) return BrokerAnalysis(false, explanation = listOf("Broker Analysis Unavailable — Stockbit belum terhubung"), failureKind = BrokerFailureKind.AUTH)
        val symbol = ticker.removeSuffix(".JK").uppercase()
        return try {
            val reference = Instant.ofEpochSecond(referenceEpochSeconds).atZone(jakarta).toLocalDate()
            // Ten sessions cover persistence/divergence. Event-driven Absorption
            // and Spring candidates request twenty sessions from the worker.
            val requestedSessions = dailySessions.coerceIn(10, 20)
            // Prefer dates proven by Stockbit OHLCVF. A few extra proven sessions are kept
            // in reserve so a genuinely empty regular-board day does not fail the ticker.
            val provenDates = knownSessionEpochSeconds.asSequence()
                .map { Instant.ofEpochSecond(it).atZone(jakarta).toLocalDate() }
                .filter { !it.isAfter(reference) }.distinct().sortedDescending()
                .take(requestedSessions + DAILY_RESERVE_SESSIONS).toList()
            val candidateDates = if (provenDates.size >= requestedSessions) provenDates
                else previousWeekdays(reference, requestedSessions + 15)

            val windowsByDate = java.util.TreeMap<LocalDate, BrokerWindow>(Comparator.reverseOrder())
            val emptyDates = mutableListOf<LocalDate>()
            var cursor = 0
            while (windowsByDate.size < requestedSessions && cursor < candidateDates.size) {
                currentCoroutineContext().ensureActive()
                val batch = candidateDates.subList(cursor, minOf(candidateDates.size, cursor + (requestedSessions - windowsByDate.size)))
                cursor += batch.size
                // All days of a batch are requested concurrently; the broker lane enforces pacing.
                val results = coroutineScope {
                    batch.map { date -> async { date to fetchWindowWithSecondChance(symbol, date, date, "ALL") } }.awaitAll()
                }
                results.forEach { (date, window) -> if (window != null) windowsByDate[date] = window else emptyDates += date }
            }
            val dailyWindows = windowsByDate.values.take(requestedSessions)
            if (dailyWindows.size < requestedSessions) {
                val okDates = windowsByDate.keys.sortedDescending()
                val pattern = if (okDates.isNotEmpty() && emptyDates.isNotEmpty() && emptyDates.all { it.isBefore(okDates.last()) })
                    "pola: hanya tanggal terbaru yang terisi (kemungkinan batas riwayat broker akun Stockbit)"
                    else "pola: tanggal kosong acak (kemungkinan pembatasan request Stockbit)"
                return BrokerAnalysis(false, explanation = listOf(
                    "Broker summary harian Stockbit hanya tersedia ${dailyWindows.size}/$requestedSessions sesi untuk $symbol",
                    "Terisi: ${okDates.joinToString { it.toString().substring(5) }}",
                    "Kosong: ${emptyDates.sortedDescending().joinToString { it.toString().substring(5) }}",
                    pattern
                ), failureKind = BrokerFailureKind.INCOMPLETE)
            }
            val sessionDates = dailyWindows.map { LocalDate.ofEpochDay(it.day.epochDay) }
            val (periods, foreignWindows) = coroutineScope {
                val all = async { fetchPeriods(symbol, sessionDates, listOf(1, 3, 5, 10), "ALL") }
                // Foreign flow is descriptive only (no strategy gate uses it), so it never fails the ticker.
                val foreign = async {
                    runCatching { fetchPeriods(symbol, sessionDates, listOf(1, 10), "FOREIGN") }
                        .getOrElse { if (it is CancellationException) throw it else emptyMap() }
                }
                all.await() to foreign.await()
            }
            val longest = periods[10] ?: periods.maxByOrNull { it.key }?.value
            if (longest == null) {
                BrokerAnalysis(false, explanation = listOf("Broker summary Stockbit kosong untuk $symbol"), failureKind = BrokerFailureKind.EMPTY)
            } else {
                val foreignPeriods = linkedMapOf<Int, Double>()
                listOf(1, 10).forEach { period -> foreignWindows[period]?.day?.netBuy?.let { foreignPeriods[period] = it } }
                val base = BrokerFlowEngine.analyzePeriods(
                    periods.mapValues { it.value.day },
                    periods.mapValues { it.value.buyers },
                    periods.mapValues { it.value.sellers },
                    foreignPeriods[10],
                    foreignPeriods
                )
                val topGroup = longest.buyers.take(3).map { it.code }.toSet()
                val persistenceWindow = dailyWindows.take(10)
                val persistence = persistenceWindow.count { day ->
                    val buy = day.day.topBuyers.filterKeys(topGroup::contains).values.sum()
                    val sell = day.day.topSellers.filterKeys(topGroup::contains).values.sum()
                    buy - sell > 0
                }
                base.copy(
                    dailyFlows = dailyWindows.map { it.day },
                    topBuyerPersistenceDays = if (persistenceWindow.size == 10) persistence else null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            brokerFailure(symbol, "Broker summary harian", e)
        }
    }

    /** Requests every horizon concurrently. A failing required horizon fails the call. */
    private suspend fun fetchPeriods(
        symbol: String,
        sessionDates: List<LocalDate>,
        periods: List<Int>,
        investorType: String
    ): Map<Int, BrokerWindow> = coroutineScope {
        periods.filter { it - 1 in sessionDates.indices }
            .map { period ->
                async { period to fetchWindowWithSecondChance(symbol, sessionDates[period - 1], sessionDates.first(), investorType) }
            }
            .awaitAll()
            .mapNotNull { (period, window) -> window?.let { period to it } }
            .toMap(linkedMapOf())
    }

    /**
     * The broker lane already retries throttling and transient failures. A window that still
     * fails gets one more chance after a short pause, because a single dropped day used to fail
     * an entire twenty-session analysis.
     */
    private suspend fun fetchWindowWithSecondChance(symbol: String, from: LocalDate, to: LocalDate, investorType: String): BrokerWindow? {
        return try {
            fetchBrokerWindow(symbol, from, to, investorType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpStatusException) {
            if (e.status in setOf(400, 401, 402, 403, 404)) throw e
            delay(1_500L + kotlin.random.Random.nextLong(0L, 1_000L))
            fetchBrokerWindow(symbol, from, to, investorType)
        } catch (e: IOException) {
            delay(1_500L + kotlin.random.Random.nextLong(0L, 1_000L))
            fetchBrokerWindow(symbol, from, to, investorType)
        }
    }

    private fun brokerFailure(symbol: String, label: String, error: Throwable): BrokerAnalysis = when (error) {
        is RateLimitException -> BrokerAnalysis(
            false,
            explanation = listOf(if (error.challenge) "$label $symbol: Stockbit meminta jeda (challenge Cloudflare); coba lagi beberapa menit lagi"
                else "$label $symbol: rate limit Stockbit masih aktif setelah beberapa percobaan"),
            failureKind = BrokerFailureKind.RATE_LIMIT
        )
        is HttpStatusException -> BrokerAnalysis(
            false,
            explanation = listOf(when (error.status) {
                401 -> "$label $symbol: sesi Stockbit kedaluwarsa (${error.message})"
                402, 403 -> "$label $symbol: Stockbit menolak akses broker summary (cek Stockbit PRO/izin akun): ${error.message}"
                else -> "$label $symbol tidak tersedia: ${error.message}"
            }),
            failureKind = httpBrokerFailure(error.status)
        )
        is java.net.SocketTimeoutException -> BrokerAnalysis(false, explanation = listOf("$label $symbol: koneksi Stockbit timeout"), failureKind = BrokerFailureKind.TIMEOUT)
        is IOException -> BrokerAnalysis(false, explanation = listOf("$label $symbol gagal: ${error.message ?: "gangguan jaringan"}"), failureKind = BrokerFailureKind.NETWORK)
        is org.json.JSONException -> BrokerAnalysis(false, explanation = listOf("$label $symbol: format respons Stockbit tidak dikenali"), failureKind = BrokerFailureKind.UNKNOWN)
        else -> BrokerAnalysis(false, explanation = listOf("$label $symbol gagal: ${error.message ?: error.javaClass.simpleName}"), failureKind = BrokerFailureKind.UNKNOWN)
    }

    fun forgetSecurities() {
        secrets.remove(SECURITIES_REFRESH_KEY)
        secrets.remove(SECURITIES_ACCESS_KEY)
        secrets.remove(SECURITIES_ACCESS_EXPIRY_KEY)
    }

    fun logout() {
        cachedAccess = null
        synchronized(windowMemo) { windowMemo.clear() }
        secrets.remove(REFRESH_KEY); secrets.remove(ACCESS_KEY); secrets.remove(ACCESS_EXPIRY_KEY)
        forgetSecurities()
    }

    private data class BrokerWindow(
        val day: BrokerDay,
        val buyers: List<BrokerFlowItem>,
        val sellers: List<BrokerFlowItem>
    )

    /**
     * Cached, de-duplicated broker window. Identical windows requested by the seed, the
     * period horizons and the daily loop (for example 1D == the newest daily session, and
     * the 10D seed == the 10D horizon) hit Stockbit only once. Completed sessions are also
     * persisted, so repeat screenings fetch only what is new.
     */
    private suspend fun fetchBrokerWindow(symbol: String, from: LocalDate, to: LocalDate, investorType: String): BrokerWindow? {
        val key = "BW3:$symbol:$from:$to:$investorType"
        val now = System.currentTimeMillis()
        synchronized(windowMemo) { windowMemo[key] }?.let { memo ->
            if (memo.expiresAt > now) return memo.window
        }
        while (true) {
            val mine = CompletableDeferred<BrokerWindow?>()
            val existing = windowInFlight.putIfAbsent(key, mine)
            if (existing != null) {
                // Someone else is already downloading this exact window; share the result.
                val shared = runCatching { existing.await() }
                if (shared.isSuccess) return shared.getOrNull()
                currentCoroutineContext().ensureActive()
                val failure = shared.exceptionOrNull()
                if (failure != null && failure !is CancellationException) throw failure
                continue
            }
            try {
                val window = loadBrokerWindow(symbol, key, from, to, investorType)
                mine.complete(window)
                return window
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                windowInFlight.remove(key, mine)
            }
        }
    }

    private suspend fun loadBrokerWindow(symbol: String, key: String, from: LocalDate, to: LocalDate, investorType: String): BrokerWindow? {
        val settled = !to.isAfter(StockbitSessionPolicy.completedDataBucket(java.time.ZonedDateTime.now(jakarta)))
        if (settled) {
            windowStore?.let { store ->
                runCatching { store.read(key) }.getOrNull()?.let { payload ->
                    val decoded = runCatching { decodeWindow(JSONObject(payload)) }
                    val window = decoded.getOrNull()
                    if (window != null) {
                        remember(key, window, SETTLED_WINDOW_TTL_MS)
                        return window
                    }
                }
            }
        }
        val window = requestNonEmptyBrokerWindow(symbol, from, to, investorType)
        if (window == null) {
            // Never persist an empty answer: on Stockbit it is usually a silent throttle,
            // not a genuinely empty session (every date requested is proven by OHLCVF volume).
            remember(key, null, EMPTY_WINDOW_TTL_MS)
            return null
        }
        remember(key, window, if (settled) SETTLED_WINDOW_TTL_MS else LIVE_WINDOW_TTL_MS)
        if (settled) {
            runCatching { windowStore?.write(key, "$symbol.JK", encodeWindow(window).toString(), SETTLED_WINDOW_TTL_MS) }
        }
        return window
    }

    private val brokerOutcomes = ArrayDeque<Boolean>()

    private fun recordBrokerOutcome(ok: Boolean) = synchronized(brokerOutcomes) {
        brokerOutcomes.addLast(ok)
        while (brokerOutcomes.size > BROKER_HEALTH_WINDOW) brokerOutcomes.removeFirst()
    }

    /**
     * Circuit breaker: Stockbit throttles the broker endpoint per account, first silently (HTTP 200
     * with an empty list) and then with 429. When most recent answers are bad, more requests only
     * make the run slower, so the screening worker stops asking for broker history.
     */
    fun brokerDegraded(): Boolean = synchronized(brokerOutcomes) {
        brokerOutcomes.size >= BROKER_HEALTH_MIN_SAMPLES &&
            brokerOutcomes.count { !it } >= brokerOutcomes.size * BROKER_HEALTH_BAD_RATIO
    }

    fun resetBrokerHealth() = synchronized(brokerOutcomes) { brokerOutcomes.clear() }

    private val emptyResponses = AtomicInteger(0)
    private val emptyRecovered = AtomicInteger(0)
    @Volatile private var lastEmptySample: String? = null

    private fun remember(key: String, window: BrokerWindow?, ttlMs: Long) {
        val memoTtl = ttlMs.coerceAtMost(6 * 60 * 60_000L)
        synchronized(windowMemo) { windowMemo[key] = MemoWindow(window, System.currentTimeMillis() + memoTtl) }
    }

    /**
     * Stockbit sometimes answers HTTP 200 with an empty broker list when it is throttling
     * silently. Every date Lumi asks for is a session with traded volume, so an empty answer
     * is treated like a soft rate limit: the broker lane slows down and the window is retried.
     */
    private suspend fun requestNonEmptyBrokerWindow(symbol: String, from: LocalDate, to: LocalDate, investorType: String): BrokerWindow? {
        repeat(EMPTY_RETRY_DELAYS_MS.size + 1) { attempt ->
            val (window, raw) = requestBrokerWindow(symbol, from, to, investorType)
            if (window != null) {
                if (attempt > 0) emptyRecovered.incrementAndGet()
                recordBrokerOutcome(true)
                return window
            }
            emptyResponses.incrementAndGet()
            recordBrokerOutcome(false)
            lastEmptySample = "$symbol $from..$to $investorType: ${raw.take(EMPTY_SAMPLE_CHARS)}"
            if (investorType != "ALL") return null // FOREIGN can be legitimately empty
            brokerGate.onSoftThrottle()
            EMPTY_RETRY_DELAYS_MS.getOrNull(attempt)?.let { delay(it + kotlin.random.Random.nextLong(0L, 800L)) }
        }
        return null
    }

    private suspend fun requestBrokerWindow(symbol: String, from: LocalDate, to: LocalDate, investorType: String): Pair<BrokerWindow?, String> {
        val query = StockbitBrokerQuery.build(from, to, investorType)
        val raw = authenticatedGet(
            "https://exodus.stockbit.com/marketdetectors/${URLEncoder.encode(symbol, StandardCharsets.UTF_8.name())}?$query",
            StockbitRequestPriority.BROKER_HISTORY
        )
        return parseBrokerWindow(raw, to, investorType) to raw
    }

    private fun parseBrokerWindow(raw: String, to: LocalDate, investorType: String): BrokerWindow? {
        val body = JSONObject(raw)
        val summary = body.optJSONObject("data")?.optJSONObject("broker_summary") ?: return null
        val buyers = parseBrokerRows(summary.optJSONArray("brokers_buy"), "bval", "netbs_buy_avg_price", false)
        val sellers = parseBrokerRows(summary.optJSONArray("brokers_sell"), "sval", "netbs_sell_avg_price", true)
        if (buyers.isEmpty() && sellers.isEmpty()) return null
        val sortedBuyers = buyers.sortedByDescending { it.netValue }
        val sortedSellers = sellers.sortedByDescending { kotlin.math.abs(it.netValue) }
        // Across every broker, total net buy and total net sell are an accounting
        // identity and cancel to zero. For ALL investors we therefore compare the
        // leading five buyer and seller brokers. FOREIGN remains a true aggregate:
        // its local counterpart is outside this filtered investor set.
        val metricBuyers = if (investorType == "ALL") sortedBuyers.take(BROKER_DOMINANCE_DEPTH) else sortedBuyers
        val metricSellers = if (investorType == "ALL") sortedSellers.take(BROKER_DOMINANCE_DEPTH) else sortedSellers
        val buyerMap = metricBuyers.associate { it.code to it.netValue }
        val sellerMap = metricSellers.associate { it.code to kotlin.math.abs(it.netValue) }
        return BrokerWindow(
            BrokerDay(to.toEpochDay(), buyerMap.values.sum(), sellerMap.values.sum(), buyerMap, sellerMap),
            sortedBuyers,
            sortedSellers
        )
    }

    private fun encodeWindow(window: BrokerWindow?): JSONObject {
        if (window == null) return JSONObject().put("empty", true)
        fun items(values: List<BrokerFlowItem>) = JSONArray().apply {
            values.forEach { item ->
                put(JSONObject().put("c", item.code).put("v", item.netValue)
                    .put("a", item.averagePrice ?: JSONObject.NULL).put("t", item.investorClass ?: JSONObject.NULL))
            }
        }
        fun map(values: Map<String, Double>) = JSONObject().apply { values.forEach { (k, v) -> put(k, v) } }
        return JSONObject()
            .put("d", window.day.epochDay).put("b", window.day.buyValue).put("s", window.day.sellValue)
            .put("tb", map(window.day.topBuyers)).put("ts", map(window.day.topSellers))
            .put("buyers", items(window.buyers)).put("sellers", items(window.sellers))
    }

    private fun decodeWindow(json: JSONObject): BrokerWindow? {
        if (json.optBoolean("empty", false)) return null
        fun items(array: JSONArray?): List<BrokerFlowItem> = buildList {
            if (array == null) return@buildList
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                add(BrokerFlowItem(
                    code = row.getString("c"),
                    netValue = row.getDouble("v"),
                    averagePrice = if (row.isNull("a")) null else row.getDouble("a"),
                    investorClass = if (row.isNull("t")) null else row.getString("t")
                ))
            }
        }
        fun map(obj: JSONObject?): Map<String, Double> = buildMap {
            if (obj == null) return@buildMap
            val keys = obj.keys()
            while (keys.hasNext()) { val k = keys.next(); put(k, obj.getDouble(k)) }
        }
        return BrokerWindow(
            BrokerDay(json.getLong("d"), json.getDouble("b"), json.getDouble("s"), map(json.optJSONObject("tb")), map(json.optJSONObject("ts"))),
            items(json.optJSONArray("buyers")),
            items(json.optJSONArray("sellers"))
        )
    }

    private fun parseBrokerRows(rows: JSONArray?, valueKey: String, averageKey: String, negativeMagnitude: Boolean): List<BrokerFlowItem> {
        if (rows == null) return emptyList()
        val values = mutableListOf<BrokerFlowItem>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("netbs_broker_code").trim().uppercase()
            val raw = wireNumber(row.opt(valueKey)) ?: continue
            if (code.matches(Regex("^[A-Z0-9]{2,4}$"))) {
                val value = if (negativeMagnitude) -kotlin.math.abs(raw) else raw.coerceAtLeast(0.0)
                values += BrokerFlowItem(
                    code = code,
                    netValue = value,
                    averagePrice = wireNumber(row.opt(averageKey)),
                    investorClass = row.optString("type").takeIf { it.isNotBlank() }
                )
            }
        }
        return values
    }

    private fun httpBrokerFailure(status: Int): BrokerFailureKind = when (status) {
        401 -> BrokerFailureKind.AUTH
        403 -> BrokerFailureKind.ENTITLEMENT
        408, 504 -> BrokerFailureKind.TIMEOUT
        429 -> BrokerFailureKind.RATE_LIMIT
        else -> BrokerFailureKind.NETWORK
    }

    private suspend fun authenticatedGet(url: String, priority: StockbitRequestPriority = StockbitRequestPriority.SCREENING): String =
        gatedRequest(priority, securities = false) { token -> http.get(url, stockbitHeaders(token), 1) }

    private suspend fun authenticatedPostEmpty(url: String): String =
        gatedRequest(StockbitRequestPriority.SCREENING, securities = false) { token -> http.postEmpty(url, stockbitHeaders(token), 1) }

    private suspend fun authenticatedSecuritiesGet(url: String): String =
        gatedRequest(StockbitRequestPriority.USER, securities = true) { token -> http.get(url, stockbitHeaders(token), 1) }

    /**
     * Single retry policy for every Stockbit read:
     *  - 429 / Cloudflare challenge: the lane backs off (AIMD) and the request is retried;
     *  - 401: the access token is refreshed once (only if nobody refreshed it already);
     *  - 5xx / socket errors: exponential backoff;
     *  - other 4xx: returned immediately, they will not succeed on retry.
     * The lane permit is released while sleeping, so retries do not block other tickers.
     */
    private suspend fun gatedRequest(
        priority: StockbitRequestPriority,
        securities: Boolean,
        call: suspend (String) -> String
    ): String {
        val gate = if (priority == StockbitRequestPriority.BROKER_HISTORY) brokerGate else generalGate
        val maxAttempts = when (priority) {
            StockbitRequestPriority.BROKER_HISTORY -> 5
            StockbitRequestPriority.SCREENING -> 4
            else -> 3
        }
        // Throttling is waited out on a time budget (the lane pauses for everyone), because a
        // Stockbit/Cloudflare cool-down can last longer than a handful of quick retries.
        val throttleBudgetMs = when (priority) {
            StockbitRequestPriority.BROKER_HISTORY -> 30_000L
            StockbitRequestPriority.SCREENING -> 90_000L
            else -> 20_000L
        }
        val startedAt = System.currentTimeMillis()
        var throttled = 0
        var attempt = 0
        var refreshed = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val token = if (securities) ensureSecuritiesAccessToken() else ensureAccessToken()
            try {
                val body = withPriority(priority) { gate.run { call(token) } }
                gate.onSuccess()
                return body
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                gate.onRateLimited(e.retryAfterMs, e.challenge)
                if (priority == StockbitRequestPriority.BROKER_HISTORY) recordBrokerOutcome(false)
                throttled++
                if (throttled >= 3 && System.currentTimeMillis() - startedAt > throttleBudgetMs) throw e
                if (throttled >= 40) throw e
            } catch (e: HttpStatusException) {
                if (e.status == 401 && !refreshed) {
                    refreshed = true
                    if (securities) ensureSecuritiesAccessToken(force = true, stale = token)
                    else ensureAccessToken(force = true, stale = token)
                    continue
                }
                val transient = e.status >= 500 || e.status == 408 || e.status == 425
                attempt++
                if (!transient || attempt >= maxAttempts) throw e
                delay(retryBackoff(attempt))
            } catch (e: IOException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                delay(retryBackoff(attempt))
            }
        }
    }

    private fun retryBackoff(attempt: Int): Long =
        (500L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(8_000L) + kotlin.random.Random.nextLong(100L, 600L)

    /** Audit/user reads overtake queued screening reads for a few seconds at most. */
    private suspend fun <T> withPriority(priority: StockbitRequestPriority, block: suspend () -> T): T {
        val urgent = priority == StockbitRequestPriority.AUDIT || priority == StockbitRequestPriority.USER
        if (urgent) urgentWaiters.incrementAndGet()
        try {
            if (priority == StockbitRequestPriority.SCREENING) {
                var waited = 0L
                while (urgentWaiters.get() > 0 && waited < 5_000L) { delay(25L); waited += 25L }
            }
            return block()
        } finally {
            if (urgent) urgentWaiters.decrementAndGet()
        }
    }

    private fun excludeIncompleteToday(candles: List<Candle>): List<Candle> {
        if (candles.isEmpty()) return candles
        val now = java.time.ZonedDateTime.now(jakarta)
        val lastDate = Instant.ofEpochSecond(candles.last().epochSeconds).atZone(jakarta).toLocalDate()
        return if (lastDate == now.toLocalDate() && !StockbitSessionPolicy.marketFinished(now)) candles.dropLast(1) else candles
    }

    /**
     * Stockbit rotates the refresh token on every successful refresh. When many parallel
     * requests receive 401 together, only the first one may refresh; the rest must reuse the
     * new access token instead of spending (and invalidating) the refresh token again.
     */
    private suspend fun ensureAccessToken(force: Boolean = false, stale: String? = null): String = refreshMutex.withLock {
        val now = System.currentTimeMillis() / 1000
        val memo = cachedAccess
        if (memo != null && memo.second > now + 60 && (!force || (stale != null && memo.first != stale))) return@withLock memo.first
        val current = secrets.get(ACCESS_KEY)
        val expiry = secrets.get(ACCESS_EXPIRY_KEY)?.toLongOrNull() ?: current?.let(StockbitTokenPolicy::jwtExpiry) ?: 0
        if (current != null && expiry > now + 60 && (!force || (stale != null && current != stale))) {
            cachedAccess = current to expiry
            return@withLock current
        }
        cachedAccess = null
        val refresh = secrets.get(REFRESH_KEY) ?: throw HttpStatusException(401)
        val response = JSONObject(http.postEmpty("https://exodus.stockbit.com/login/refresh", stockbitHeaders(refresh), 1))
        val access = StockbitTokenPolicy.findToken(response, "access") ?: response.optString("token").takeIf(StockbitTokenPolicy::looksLikeJwt)
            ?: throw IOException("Refresh response missing access token")
        StockbitTokenPolicy.findToken(response, "refresh")?.let { secrets.put(REFRESH_KEY, it) }
        val newExpiry = StockbitTokenPolicy.jwtExpiry(access) ?: (now + 3600)
        secrets.put(ACCESS_KEY, access)
        secrets.put(ACCESS_EXPIRY_KEY, newExpiry.toString())
        cachedAccess = access to newExpiry
        access
    }

    private suspend fun ensureSecuritiesAccessToken(force: Boolean = false, stale: String? = null): String = refreshMutex.withLock {
        val now = System.currentTimeMillis() / 1000
        val current = secrets.get(SECURITIES_ACCESS_KEY)
        val expiry = secrets.get(SECURITIES_ACCESS_EXPIRY_KEY)?.toLongOrNull() ?: current?.let(StockbitTokenPolicy::jwtExpiry) ?: 0
        if (current != null && expiry > now + 60 && (!force || (stale != null && current != stale))) return@withLock current
        val refresh = secrets.get(SECURITIES_REFRESH_KEY) ?: throw HttpStatusException(401)
        val body = JSONObject().put("refresh_token", refresh)
        val response = JSONObject(http.postJson("https://carina.stockbit.com/auth/refresh", body.toString(), carinaHeaders(refresh), 1))
        val access = findCredential(response, "access")
            ?: throw IOException("Securities refresh response missing access token")
        val rotatedRefresh = findCredential(response, "refresh")
        secrets.put(SECURITIES_ACCESS_KEY, access)
        rotatedRefresh?.let { secrets.put(SECURITIES_REFRESH_KEY, it) }
        secrets.put(SECURITIES_ACCESS_EXPIRY_KEY, (StockbitTokenPolicy.jwtExpiry(access) ?: (now + 3600)).toString())
        access
    }

    private fun stockbitHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token", "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Origin" to "https://stockbit.com", "Referer" to "https://stockbit.com/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    )

    private fun carinaPublicHeaders() = mapOf(
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8", "Origin" to "https://stockbit.com",
        "Referer" to "https://stockbit.com/", "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    )
    private fun carinaHeaders(token: String) = carinaPublicHeaders() + ("Authorization" to "Bearer $token")

    private fun previousWeekdays(end: LocalDate, count: Int): List<LocalDate> {
        val out = mutableListOf<LocalDate>(); var day = end
        while (out.size < count) { if (day.dayOfWeek.value <= 5) out += day; day = day.minusDays(1) }
        return out
    }

    companion object {
        private const val BROKER_DOMINANCE_DEPTH = 5
        private const val DAILY_RESERVE_SESSIONS = 6
        private const val LIVE_WINDOW_TTL_MS = 2 * 60_000L
        private const val EMPTY_WINDOW_TTL_MS = 60_000L
        private val EMPTY_RETRY_DELAYS_MS = listOf(1_500L)
        private const val BROKER_HEALTH_WINDOW = 20
        private const val BROKER_HEALTH_MIN_SAMPLES = 12
        private const val BROKER_HEALTH_BAD_RATIO = 0.6
        private const val EMPTY_SAMPLE_CHARS = 400
        private const val SETTLED_WINDOW_TTL_MS = 30L * 24 * 60 * 60_000L
        private const val HISTORICAL_PAGE_SIZE = 12
        private const val MAX_HISTORICAL_PAGES = 12
        const val REFRESH_KEY = "stockbit_refresh_token"
        const val ACCESS_KEY = "stockbit_access_token"
        const val ACCESS_EXPIRY_KEY = "stockbit_access_expiry"
        const val SECURITIES_REFRESH_KEY = "stockbit_securities_refresh_token"
        const val SECURITIES_ACCESS_KEY = "stockbit_securities_access_token"
        const val SECURITIES_ACCESS_EXPIRY_KEY = "stockbit_securities_access_expiry"

        private fun normalizeKey(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")

        private fun allObjects(root: Any?): List<JSONObject> {
            val out = mutableListOf<JSONObject>()
            fun walk(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        out += value
                        val keys = value.keys()
                        while (keys.hasNext()) walk(value.opt(keys.next()))
                    }
                    is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
                }
            }
            walk(root)
            return out
        }

        private fun findNumber(root: Any?, aliases: Set<String>): Double? {
            val wanted = aliases.map(::normalizeKey).toSet()
            for (obj in allObjects(root)) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (normalizeKey(key) in wanted) wireNumber(obj.opt(key))?.let { return it }
                }
            }
            return null
        }

        private fun findNumbers(root: Any?, aliases: Set<String>): List<Double> {
            val wanted = aliases.map(::normalizeKey).toSet()
            return allObjects(root).flatMap { obj ->
                buildList {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (normalizeKey(key) in wanted) wireNumber(obj.opt(key))?.let(::add)
                    }
                }
            }
        }

        private fun findString(root: Any?, aliases: Set<String>): String? {
            val wanted = aliases.map(::normalizeKey).toSet()
            for (obj in allObjects(root)) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (normalizeKey(key) in wanted) {
                        val value = obj.opt(key)
                        if (value is String && value.isNotBlank()) return value
                    }
                }
            }
            return null
        }

        private fun findStrings(root: Any?, aliases: Set<String>): List<String> {
            val wanted = aliases.map(::normalizeKey).toSet(); val out = mutableListOf<String>()
            for (obj in allObjects(root)) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next(); if (normalizeKey(key) !in wanted) continue
                    when (val value = obj.opt(key)) {
                        is String -> if (value.isNotBlank()) out += value
                        is JSONArray -> for (i in 0 until value.length()) value.optString(i).takeIf(String::isNotBlank)?.let(out::add)
                    }
                }
            }
            return out
        }

        private fun findBoolean(root: Any?, aliases: Set<String>): Boolean? {
            val wanted = aliases.map(::normalizeKey).toSet()
            for (obj in allObjects(root)) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next(); if (normalizeKey(key) !in wanted) continue
                    when (val value = obj.opt(key)) {
                        is Boolean -> return value
                        is Number -> return value.toInt() != 0
                        is String -> when (value.lowercase()) { "true", "yes", "1", "active" -> return true; "false", "no", "0", "inactive" -> return false }
                    }
                }
            }
            return null
        }

        private fun findCredential(root: Any?, kind: String): String? {
            val wanted = if (kind == "refresh") setOf("refresh", "refreshtoken") else setOf("access", "accesstoken")
            for (obj in allObjects(root)) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (normalizeKey(key) !in wanted) continue
                    when (val value = obj.opt(key)) {
                        is String -> if (value.isNotBlank()) return value
                        is JSONObject -> value.optString("token").takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
            return null
        }

        private fun firstLadderPrice(row: JSONObject, side: String): Double? {
            val levels = row.optJSONArray(side) ?: return null
            for (index in 0 until levels.length()) {
                val level = levels.optJSONObject(index) ?: continue
                wireNumber(level.opt("price"))?.takeIf { it > 0 }?.let { return it }
            }
            return null
        }

        internal fun parseCorporateAction(value: Any?): ParsedCorporateAction {
            if (value == null || value == JSONObject.NULL) return ParsedCorporateAction(null, emptyList())
            if (value is Boolean) return ParsedCorporateAction(value, emptyList())
            if (value !is JSONObject) return ParsedCorporateAction(null, emptyList())

            val active = directBoolean(value, "active", "is_active", "isActive")
            if (active != true) return ParsedCorporateAction(active, emptyList())

            val descriptions = listOfNotNull(
                directString(value, "text", "title", "description", "name"),
                value.optJSONObject("detail")?.let { detail ->
                    directString(detail, "text", "title", "description", "name", "type", "action")
                }
            ).map(String::trim)
                .filter { it.length in 2..160 && !it.startsWith("http://", true) && !it.startsWith("https://", true) && !it.endsWith(".svg", true) }
                .distinct()
            return ParsedCorporateAction(true, descriptions)
        }

        private fun spreadTicks(bid: Double, offer: Double): Int? {
            if (bid <= 0 || offer < bid) return null
            var price = bid
            var ticks = 0
            while (price < offer && ticks <= 100) {
                price += when {
                    price < 200 -> 1.0
                    price < 500 -> 2.0
                    price < 2_000 -> 5.0
                    price < 5_000 -> 10.0
                    else -> 25.0
                }
                ticks++
            }
            return ticks.takeIf { price == offer && it <= 100 }
        }

        private fun directString(obj: JSONObject, vararg aliases: String): String? {
            val wanted = aliases.map(::normalizeKey).toSet(); val keys = obj.keys()
            while (keys.hasNext()) { val key = keys.next(); if (normalizeKey(key) in wanted) obj.optString(key).takeIf { it.isNotBlank() }?.let { return it } }
            return null
        }
        private fun directNumber(obj: JSONObject, vararg aliases: String): Double? {
            val wanted = aliases.map(::normalizeKey).toSet(); val keys = obj.keys()
            while (keys.hasNext()) { val key = keys.next(); if (normalizeKey(key) in wanted) wireNumber(obj.opt(key))?.let { return it } }
            return null
        }
        private fun directBoolean(obj: JSONObject, vararg aliases: String): Boolean? {
            val wanted = aliases.map(::normalizeKey).toSet(); val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next(); if (normalizeKey(key) !in wanted) continue
                return when (val value = obj.opt(key)) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    is String -> when (value.trim().lowercase()) {
                        "true", "yes", "1", "active" -> true
                        "false", "no", "0", "inactive" -> false
                        else -> null
                    }
                    else -> null
                }
            }
            return null
        }
        private fun directValue(obj: JSONObject, vararg aliases: String): Any? {
            val wanted = aliases.map(::normalizeKey).toSet(); val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (normalizeKey(key) in wanted) return obj.opt(key)?.takeUnless { it == JSONObject.NULL }
            }
            return null
        }

        private fun findNumberAtPath(obj: JSONObject, path: String): Double? {
            var value: Any? = obj
            for (segment in path.split('.')) {
                value = (value as? JSONObject)?.opt(segment) ?: return null
            }
            return wireNumber(value)
        }

        internal fun parsePortfolioHoldings(root: JSONObject): List<PortfolioHolding> = allObjects(root).mapNotNull { row ->
            val ticker = directString(row, "symbol", "ticker", "stock_code", "stockcode", "code")?.uppercase()?.removeSuffix(".JK")
            val average = findNumberAtPath(row, "price.average.price")
                ?: findNumber(row, setOf("average_price", "avg_price", "avgprice", "averagePrice", "price_avg", "buy_average"))
            var lots = findNumber(row, setOf("lot", "lots", "balance_lot", "total_lot", "lot_balance"))
            var shares = findNumber(row, setOf("shares", "share", "quantity", "balance_share", "total_balance"))
            if (lots == null && shares != null && shares % 100.0 == 0.0) lots = shares / 100.0
            val percent = findNumber(row, setOf("unrealized_percent", "unrealized_pnl_percent", "unrealized_pl_percent", "gain_loss_percent", "profit_loss_percent", "percentage"))
                ?: findNumberAtPath(row, "asset.unrealised.gain")?.let { if (kotlin.math.abs(it) <= 10) it * 100 else it }
            if (ticker == null || !ticker.matches(Regex("^[A-Z][A-Z0-9]{0,11}$")) || (average == null && lots == null && shares == null)) null
            else PortfolioHolding("$ticker.JK", directString(row, "name", "company_name", "companyname"), lots, shares, average,
                findNumber(row, setOf("latest", "last_price", "lastprice", "market_price", "close_price")),
                findNumber(row, setOf("market_value", "marketvalue", "current_value")),
                findNumber(row, setOf("unrealized_profit_loss", "unrealized_pl", "unrealized_pnl", "profit_loss", "gain_loss", "potential_gain")),
                percent)
        }.distinctBy { it.ticker }

        internal fun parsePortfolioOrders(root: JSONObject): List<PortfolioOrder> = allObjects(root).mapNotNull { row ->
            val id = directString(row, "order_id", "orderid", "id") ?: return@mapNotNull null
            val status = directString(row, "status", "order_status", "state") ?: return@mapNotNull null
            val shares = directNumber(row, "shares", "share", "amount", "quantity", "qty", "volume")
            val filledShares = directNumber(row, "filled", "filled_shares", "done", "matched", "match_amount", "traded")
            PortfolioOrder(id, directString(row, "symbol", "ticker", "stock_code", "code")?.uppercase(), directString(row, "side", "action", "order_type"),
                directNumber(row, "price", "order_price", "limit_price"),
                directNumber(row, "lot", "lots", "order_lot", "total_lot") ?: shares?.takeIf { it % 100.0 == 0.0 }?.div(100.0),
                directNumber(row, "filled_lot", "matched_lot", "executed_lot") ?: filledShares?.takeIf { it % 100.0 == 0.0 }?.div(100.0),
                status, directString(row, "timestamp", "created_at", "createdAt", "order_time", "order_date", "time", "date"))
        }.distinctBy { it.id }

        internal fun parsePortfolioTransactions(root: JSONObject): List<PortfolioTransaction> = allObjects(root).mapNotNull { row ->
            val ticker = directString(row, "symbol", "ticker", "stock_code", "code")?.uppercase() ?: return@mapNotNull null
            val timestamp = directString(row, "timestamp", "created_at", "trade_date", "date", "time") ?: return@mapNotNull null
            val id = directString(row, "trade_id", "transaction_id", "order_id", "id") ?: "$ticker-$timestamp"
            PortfolioTransaction(id, ticker, directString(row, "side", "action", "transaction_type"), directNumber(row, "price", "trade_price", "average_price", "match_price", "done_price"),
                directNumber(row, "lot", "lots", "quantity"), directNumber(row, "amount", "value", "net_amount", "total_value", "amount_idr", "net_value", "gross_value"),
                directNumber(row, "realized_profit_loss", "realized_pl", "realized_pnl", "gain_loss"), timestamp)
        }.distinctBy { it.id }

        internal fun parseRunningTrades(root: JSONObject, expectedSymbol: String): List<TradeTick> {
            val sessionDate = findString(root.optJSONObject("data") ?: root, setOf("date", "trade_date", "session_date"))
                ?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now(ZoneId.of("Asia/Jakarta"))
            return allObjects(root).mapNotNull { row ->
                val symbol = directString(row, "symbol", "stock_code", "code", "ticker")?.uppercase()
                if (symbol != null && symbol.removeSuffix(".JK") != expectedSymbol.removeSuffix(".JK")) return@mapNotNull null
                val price = directNumber(row, "price", "trade_price", "matched_price", "last_price", "lastprice")
                    ?.takeIf { it > 0 } ?: return@mapNotNull null
                val rawTime = directValue(row, "timestamp", "trade_time", "created_at", "created", "datetime", "time")
                    ?: return@mapNotNull null
                val epoch = parseTradeEpoch(rawTime, sessionDate) ?: return@mapNotNull null
                TradeTick(epoch, price)
            }.distinctBy { it.epochSeconds to it.price }.sortedBy { it.epochSeconds }
        }

        private fun parseTradeEpoch(value: Any, sessionDate: LocalDate): Long? {
            if (value is Number) {
                val raw = value.toLong()
                return when {
                    raw > 10_000_000_000L -> raw / 1_000L
                    raw > 1_000_000_000L -> raw
                    else -> null
                }
            }
            val text = value.toString().trim()
            text.toLongOrNull()?.let { raw ->
                return when {
                    raw > 10_000_000_000L -> raw / 1_000L
                    raw > 1_000_000_000L -> raw
                    else -> null
                }
            }
            runCatching { return OffsetDateTime.parse(text).toEpochSecond() }
            runCatching { return LocalDateTime.parse(text).atZone(ZoneId.of("Asia/Jakarta")).toEpochSecond() }
            val timeText = text.substringAfterLast('T').removeSuffix("Z")
            return runCatching {
                LocalTime.parse(timeText).atDate(sessionDate).atZone(ZoneId.of("Asia/Jakarta")).toEpochSecond()
            }.getOrNull()
        }

        internal fun parseHistoricalPage(payload: String): List<Candle> {
            val root = JSONObject(payload)
            val data = root.optJSONObject("data") ?: root
            val rows = data.optJSONArray("result") ?: data.optJSONArray("results") ?: JSONArray()
            val zone = ZoneId.of("Asia/Jakarta")
            return buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val date = runCatching { LocalDate.parse(row.optString("date").take(10)) }.getOrNull() ?: continue
                    val open = wireNumber(row.opt("open")) ?: continue
                    val high = wireNumber(row.opt("high")) ?: continue
                    val low = wireNumber(row.opt("low")) ?: continue
                    val close = wireNumber(row.opt("close")) ?: continue
                    val volume = wireNumber(row.opt("volume"))?.toLong() ?: continue
                    add(Candle(
                        epochSeconds = date.atStartOfDay(zone).toEpochSecond(),
                        open = open, high = high, low = low, close = close, adjustedClose = null,
                        volume = volume,
                        tradedValue = wireNumber(row.opt("value")),
                        frequency = wireNumber(row.opt("frequency"))?.toLong()
                    ))
                }
            }
        }

        private fun wireNumber(value: Any?): Double? {
            if (value == null || value == JSONObject.NULL) return null
            if (value is Number) return value.toDouble().takeIf { it.isFinite() }
            if (value is JSONObject && value.has("raw")) return wireNumber(value.opt("raw"))
            val text = value.toString().trim().replace(",", "")
            if (text.isBlank() || text == "-") return null
            return text.removeSuffix("%").toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }
}

internal object StockbitEntitlementPolicy {
    fun classify(httpStatus: Int, payload: String?, hasBrokerRows: Boolean): StockbitAccountTier {
        if (httpStatus == 401) return StockbitAccountTier.UNKNOWN
        if (httpStatus == 402 || httpStatus == 403) return StockbitAccountTier.NON_PRO
        if (httpStatus !in 200..299) return StockbitAccountTier.UNKNOWN
        if (hasBrokerRows) return StockbitAccountTier.PRO
        val normalized = payload.orEmpty().lowercase()
        val explicitlyLocked = listOf(
            "stockbit pro", "upgrade to pro", "upgrade pro", "subscription required",
            "premium required", "permission denied", "not entitled", "unauthorized feature"
        ).any(normalized::contains)
        return if (explicitlyLocked) StockbitAccountTier.NON_PRO else StockbitAccountTier.UNKNOWN
    }
}

object StockbitTokenPolicy {
    private val jwt = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
    fun looksLikeJwt(value: String?): Boolean = value != null && jwt.matches(value.trim())
    fun tokenUrlAllowed(raw: String): Boolean = runCatching {
        val uri = URI(raw); val host = uri.host?.lowercase() ?: return@runCatching false; val path = uri.path.orEmpty()
        when {
            host == "wssocial.stockbit.com" -> true
            host != "exodus.stockbit.com" -> false
            else -> path.startsWith("/login/", true) || path.startsWith("/auth/", true)
        }
    }.getOrDefault(false)
    fun findToken(node: Any?, kind: String): String? {
        val keys = if (kind == "refresh") Regex("^refresh(_token)?$", RegexOption.IGNORE_CASE) else Regex("^access(_token)?$", RegexOption.IGNORE_CASE)
        fun walk(value: Any?): String? {
            return when (value) {
                is JSONObject -> { for (key in value.keys()) { val child = value.opt(key); if (keys.matches(key)) { if (child is String && looksLikeJwt(child)) return child; if (child is JSONObject) child.optString("token").takeIf(::looksLikeJwt)?.let { return it } }; walk(child)?.let { return it } }; null }
                is JSONArray -> { for (i in 0 until value.length()) walk(value.opt(i))?.let { return it }; null }
                else -> null
            }
        }
        return walk(node)
    }
    fun jwtExpiry(token: String): Long? = runCatching {
        val part = token.split('.')[1]; val padded = part + "=".repeat((4 - part.length % 4) % 4)
        JSONObject(String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)).optLong("exp").takeIf { it > 0 }
    }.getOrNull()
}
