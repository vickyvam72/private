package com.lumisignal.idxscreener.network

import com.lumisignal.idxscreener.model.Candle
import com.lumisignal.idxscreener.model.DataResult
import com.lumisignal.idxscreener.model.MarketSeries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One stock on one session, as published by IDX `TradingSummary/GetStockSummary`. */
data class IdxDailyRow(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volumeShares: Long,
    val value: Double,
    val frequency: Long,
    val foreignBuy: Double,
    val foreignSell: Double
) {
    val foreignNet: Double get() = foreignBuy - foreignSell
}

data class IdxBulkResult(
    val series: Map<String, MarketSeries>,
    /** ticker (with .JK) -> foreign net value per session, newest last. */
    val foreignNet: Map<String, List<Pair<LocalDate, Double>>>,
    val sessions: List<LocalDate>,
    val networkRequests: Int
)

/**
 * Market-wide daily data from the Indonesia Stock Exchange. One request returns OHLC, volume,
 * value, frequency and foreign buy/sell for every listed stock on one session, so 72 sessions of
 * the whole market cost ~80 requests the first time and about one request per day afterwards
 * (completed sessions are cached). This replaces ~850–5,000 per-ticker Stockbit requests.
 */
/** A real browser engine that can read idx.co.id the way the website itself does. */
interface IdxBrowserFetcher {
    val lastTitle: String
    suspend fun open()
    suspend fun get(url: String): String
    suspend fun close()
}

class IdxStockSummaryRepository(
    private val store: BrokerWindowStore? = null,
    private val browserFactory: (() -> IdxBrowserFetcher)? = null,
    /** Test seam: replaces direct HTTP. */
    private val directOverride: (suspend (String) -> String)? = null,
    private val spacingMs: Long = IDX_SPACING_MS,
    private val backoffBaseMs: Long = 5_000L
) {
    private val jakarta = ZoneId.of("Asia/Jakarta")
    private val sessionMutex = Mutex()
    private val browserMutex = Mutex()
    @Volatile private var browser: IdxBrowserFetcher? = null
    @Volatile private var directBlocked = false
    @Volatile private var browserFailure: Throwable? = null
    @Volatile var lastTransport: String = "-"
        private set
    @Volatile private var cookie: String? = null
    private val compact = DateTimeFormatter.BASIC_ISO_DATE

    /**
     * Builds 72-session series from cached IDX days plus at most [maxNetworkDays] downloads.
     * Completed sessions never change, so every downloaded day is cached immediately: an
     * interrupted or rate-limited sync resumes on the next screening instead of starting over.
     * IDX rate-limits its API, so downloads are strictly sequential and paced.
     */
    suspend fun buildSeries(
        names: Map<String, String>,
        sessions: Int = 72,
        maxNetworkDays: Int = Int.MAX_VALUE,
        onProgress: suspend (done: Int, target: Int) -> Unit = { _, _ -> }
    ): DataResult<IdxBulkResult> = try {
        directBlocked = false
        browserFailure = null
        lastTransport = "cache"
        val now = ZonedDateTime.now(jakarta)
        val newest = StockbitSessionPolicy.completedDataBucket(now)
        val weekdays = generateSequence(newest) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }
            .take(sessions + 45).toList()
        val days = sortedMapOf<LocalDate, Map<String, IdxDailyRow>>()
        val uncached = mutableListOf<LocalDate>()
        // Pass 1: cache only. Stop once cached sessions + unknown days could cover the target.
        var walk = 0
        while (walk < weekdays.size && days.size + uncached.size < sessions) {
            val date = weekdays[walk++]
            val cached = readCachedDay(date)
            when {
                cached == null -> uncached += date
                cached.isNotEmpty() -> days[date] = cached
            }
        }
        lastCachedSessions = days.size
        if (uncached.size > maxNetworkDays) {
            throw IOException("cache IDX ${days.size}/$sessions sesi; ${uncached.size} hari belum diunduh")
        }
        var requests = 0
        val queue = ArrayDeque(uncached)
        // Pass 2: download missing days newest first; holidays extend the walk further back.
        while (days.size < sessions) {
            val date: LocalDate = if (queue.isNotEmpty()) queue.removeFirst() else {
                val more = weekdays.getOrNull(walk++) ?: break
                val cached = readCachedDay(more)
                if (cached != null) {
                    if (cached.isNotEmpty()) days[more] = cached
                    continue
                }
                more
            }
            if (requests.toLong() >= maxNetworkDays.toLong() + 10L) break
            if (requests > 0) delay(spacingMs)
            val rows = downloadDay(date, newest)
            requests++
            if (rows.isNotEmpty()) days[date] = rows
            onProgress(days.size.coerceAtMost(sessions), sessions)
        }
        if (days.size < 60) throw IOException("IDX hanya memberi ${days.size} sesi")
        val chosen = days.entries.toList().takeLast(sessions)
        val series = linkedMapOf<String, MarketSeries>()
        val foreign = linkedMapOf<String, List<Pair<LocalDate, Double>>>()
        val tickers = chosen.flatMap { it.value.keys }.toSet()
        for (ticker in tickers) {
            val candles = mutableListOf<Candle>()
            val flows = mutableListOf<Pair<LocalDate, Double>>()
            for ((date, rows) in chosen) {
                val row = rows[ticker] ?: continue
                candles += Candle(
                    epochSeconds = date.atStartOfDay(jakarta).toEpochSecond(),
                    open = row.open, high = row.high, low = row.low, close = row.close,
                    adjustedClose = null,
                    // Stockbit history reports lots; IDX reports shares. Keep Lumi's unit.
                    volume = row.volumeShares / 100L,
                    tradedValue = row.value,
                    frequency = row.frequency
                )
                flows += date to row.foreignNet
            }
            if (candles.size < 60) continue
            if (candles.count { it.frequency != null && it.tradedValue != null } < 55) continue
            val symbol = ticker.removeSuffix(".JK")
            series[ticker] = MarketSeries(ticker, names[ticker]?.ifBlank { symbol } ?: symbol, "IDR", candles)
            foreign[ticker] = flows
        }
        if (series.isEmpty()) throw IOException("IDX tidak memuat ticker dengan 60 sesi")
        DataResult.Success(IdxBulkResult(series, foreign, chosen.map { it.key }, requests))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        DataResult.Error("Ringkasan harian IDX tidak tersedia ($lastTransport): ${e.message ?: e.javaClass.simpleName}", e)
    } finally {
        // Must run even when the caller was cancelled, or the hidden WebView would leak.
        withContext(kotlinx.coroutines.NonCancellable) {
            browserMutex.withLock {
                browser?.let { runCatching { it.close() } }
                browser = null
            }
        }
    }

    /** Sessions already stored on the phone during previous runs (for the progress message). */
    @Volatile var lastCachedSessions: Int = 0
        private set

    private suspend fun readCachedDay(date: LocalDate): Map<String, IdxDailyRow>? {
        val payload = store?.let { s -> runCatching { s.read("IDXDAY_V1:$date") }.getOrNull() } ?: return null
        return runCatching { decodeDay(JSONObject(payload)) }.getOrNull()
    }

    private suspend fun downloadDay(date: LocalDate, newest: LocalDate): Map<String, IdxDailyRow> {
        var lastError: Throwable? = null
        val url = "https://www.idx.co.id/primary/TradingSummary/GetStockSummary?length=9999&start=0&date=${date.format(compact)}"
        var throttled = 0
        var failures = 0
        while (true) {
            try {
                val result = parseDay(fetch(url))
                // A recent empty day may simply not be published yet; only old empty days are holidays.
                val cacheable = result.isNotEmpty() || date.isBefore(newest.minusDays(4))
                if (cacheable) runCatching { store?.write("IDXDAY_V1:$date", "IDX", encodeDay(result).toString(), DAY_TTL_MS) }
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitException) {
                lastError = e
                if (++throttled > 5) break
                // IDX/Cloudflare rate limit: back off generously (5 s, 10 s, 20 s, 40 s, 60 s).
                delay(maxOf(e.retryAfterMs, (backoffBaseMs shl (throttled - 1)).coerceAtMost(60_000L)))
            } catch (e: Throwable) {
                lastError = e
                if (e is HttpStatusException && (e.status == 401 || e.status == 403)) cookie = null
                if (++failures >= 3) break
                delay((backoffBaseMs / 3) shl failures)
            }
        }
        throw IOException("IDX $date gagal: ${lastError?.message ?: "tanpa respons"}", lastError)
    }

    private suspend fun ensureCookie(): String? = sessionMutex.withLock {
        cookie?.let { return@withLock it }
        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URI("https://www.idx.co.id/id").toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                browserHeaders().forEach { (k, v) -> connection.setRequestProperty(k, v) }
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.responseCode
                val cookies = connection.headerFields.entries
                    .filter { it.key.equals("Set-Cookie", true) }
                    .flatMap { it.value }
                    .map { it.substringBefore(';') }
                runCatching { (connection.errorStream ?: connection.inputStream)?.close() }
                cookies.joinToString("; ").ifBlank { null }
            }.getOrNull()
        }
        cookie = fetched
        fetched
    }

    /**
     * Direct HTTP first (cheapest). Cloudflare answers idx.co.id API calls from non-browser clients
     * with "Attention Required" (HTTP 403), so on the first such answer every later request goes
     * through the hidden WebView, which loads IDX's own page and fetches from inside it.
     */
    private suspend fun fetch(url: String): String {
        if (!directBlocked) {
            try {
                return (directOverride?.invoke(url) ?: request(url)).also { lastTransport = "langsung" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val blocked = (e is HttpStatusException && e.status == 403) || (e is RateLimitException && e.challenge)
                if (!blocked || browserFactory == null) throw e
                directBlocked = true
            }
        }
        val active = browserMutex.withLock {
            browserFailure?.let { throw IOException("WebView IDX gagal: ${it.message}", it) }
            browser ?: browserFactory!!.invoke().also { candidate ->
                try {
                    candidate.open()
                } catch (e: Throwable) {
                    runCatching { candidate.close() }
                    if (e !is CancellationException) browserFailure = e
                    lastTransport = "WebView gagal (${candidate.lastTitle.take(40)})"
                    throw e
                }
                browser = candidate
                lastTransport = "WebView (${candidate.lastTitle.take(40)})"
            }
        }
        return active.get(url)
    }

    private suspend fun request(url: String): String {
        val session = ensureCookie()
        return withContext(Dispatchers.IO) {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 45_000
            browserHeaders().forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            session?.let { connection.setRequestProperty("Cookie", it) }
            val code = connection.responseCode
            val text = runCatching { (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } }
                .getOrNull().orEmpty()
            if (code == 429) throw RateLimitException()
            if (code !in 200..299) throw HttpStatusException(code, text.take(200))
            if (HttpClient.looksLikeHtmlChallenge(text)) throw RateLimitException(challenge = true)
            text
        }
    }

    private fun browserHeaders() = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "id-ID,id;q=0.9,en;q=0.8",
        "Referer" to "https://www.idx.co.id/id/data-pasar/ringkasan-perdagangan/ringkasan-saham/",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    )

    companion object {
        private const val IDX_SPACING_MS = 1_200L
        private const val DAY_TTL_MS = 150L * 24 * 60 * 60_000

        internal fun parseDay(payload: String): Map<String, IdxDailyRow> {
            val root = JSONObject(payload)
            val rows = root.optJSONArray("data") ?: throw IOException("IDX response missing data")
            val out = linkedMapOf<String, IdxDailyRow>()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val code = row.optString("StockCode").trim().uppercase()
                if (!code.matches(Regex("^[A-Z]{4}$"))) continue
                val close = num(row, "Close") ?: continue
                if (close <= 0) continue
                val volume = num(row, "Volume")?.toLong() ?: 0L
                val traded = volume > 0
                val open = num(row, "OpenPrice")?.takeIf { traded && it > 0 } ?: close
                val high = num(row, "High")?.takeIf { traded && it > 0 } ?: maxOf(open, close)
                val low = num(row, "Low")?.takeIf { traded && it > 0 } ?: minOf(open, close)
                out["$code.JK"] = IdxDailyRow(
                    open = open, high = maxOf(high, open, close), low = minOf(low, open, close), close = close,
                    volumeShares = volume,
                    value = num(row, "Value") ?: 0.0,
                    frequency = num(row, "Frequency")?.toLong() ?: 0L,
                    foreignBuy = num(row, "ForeignBuy") ?: 0.0,
                    foreignSell = num(row, "ForeignSell") ?: 0.0
                )
            }
            return out
        }

        private fun num(row: JSONObject, key: String): Double? {
            if (!row.has(key) || row.isNull(key)) return null
            return when (val v = row.opt(key)) {
                is Number -> v.toDouble()
                is String -> v.trim().replace(",", "").toDoubleOrNull()
                else -> null
            }?.takeIf { it.isFinite() }
        }

        private fun encodeDay(rows: Map<String, IdxDailyRow>): JSONObject {
            val array = JSONArray()
            rows.forEach { (ticker, r) ->
                array.put(JSONArray(listOf(ticker, r.open, r.high, r.low, r.close, r.volumeShares, r.value, r.frequency, r.foreignBuy, r.foreignSell)))
            }
            return JSONObject().put("v", 1).put("rows", array)
        }

        private fun decodeDay(json: JSONObject): Map<String, IdxDailyRow> {
            val array = json.optJSONArray("rows") ?: throw IOException("bad cache")
            val out = linkedMapOf<String, IdxDailyRow>()
            for (i in 0 until array.length()) {
                val r = array.optJSONArray(i) ?: continue
                out[r.optString(0)] = IdxDailyRow(
                    r.optDouble(1), r.optDouble(2), r.optDouble(3), r.optDouble(4),
                    r.optLong(5), r.optDouble(6), r.optLong(7), r.optDouble(8), r.optDouble(9)
                )
            }
            return out
        }
    }
}
