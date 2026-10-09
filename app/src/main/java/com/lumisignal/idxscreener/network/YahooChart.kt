package com.lumisignal.idxscreener.network

import com.lumisignal.idxscreener.model.Candle
import com.lumisignal.idxscreener.model.DataResult
import com.lumisignal.idxscreener.model.MarketSeries
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Free daily OHLCV for IDX stocks from Yahoo Finance's public chart endpoint (`BBCA.JK`).
 * Yahoo has no trade frequency and no broker data, so Lumi only uses it as a fast, generous
 * prefilter; every stock that might qualify is then re-read from Stockbit with full OHLCVF.
 */
class YahooChartRepository(private val http: HttpClient = HttpClient()) {
    private val jakarta = ZoneId.of("Asia/Jakarta")

    suspend fun fetch(ticker: String, companyName: String): DataResult<MarketSeries> {
        val symbol = ticker.removeSuffix(".JK").uppercase()
        var last: Throwable? = null
        for (host in HOSTS) {
            try {
                val body = http.get("https://$host/v8/finance/chart/$symbol.JK?range=6mo&interval=1d&includePrePost=false&events=div%2Csplit", HEADERS, 2)
                val candles = parse(body, ZonedDateTime.now(jakarta))
                if (candles.size < 60) return DataResult.Error("Yahoo $symbol hanya ${candles.size} sesi")
                return DataResult.Success(MarketSeries("$symbol.JK", companyName.ifBlank { symbol }, "IDR", candles))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        return DataResult.Error("Yahoo Finance $symbol tidak tersedia: ${last?.message ?: "network error"}", last)
    }

    companion object {
        private val HOSTS = listOf("query1.finance.yahoo.com", "query2.finance.yahoo.com")
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36",
            "Accept-Language" to "en-US,en;q=0.9,id;q=0.8"
        )

        /** Parses `chart.result[0]`; drops null rows and today's still-running session. */
        internal fun parse(payload: String, now: ZonedDateTime): List<Candle> {
            val zone = ZoneId.of("Asia/Jakarta")
            val root = JSONObject(payload)
            val chart = root.optJSONObject("chart") ?: throw IOException("Yahoo response missing chart")
            val result = chart.optJSONArray("result")?.optJSONObject(0)
                ?: throw IOException("Yahoo: ${chart.optJSONObject("error")?.optString("description") ?: "no result"}")
            val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
            val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
                ?: throw IOException("Yahoo response missing quote")
            val open = quote.optJSONArray("open"); val high = quote.optJSONArray("high")
            val low = quote.optJSONArray("low"); val close = quote.optJSONArray("close")
            val volume = quote.optJSONArray("volume")
            val out = linkedMapOf<Long, Candle>()
            for (i in 0 until timestamps.length()) {
                val date = Instant.ofEpochSecond(timestamps.optLong(i)).atZone(zone).toLocalDate()
                val c = num(close, i) ?: continue
                if (c <= 0) continue
                val o = num(open, i) ?: c
                val h = maxOf(num(high, i) ?: c, o, c)
                val l = minOf(num(low, i) ?: c, o, c)
                val shares = num(volume, i)?.toLong() ?: 0L
                val epoch = date.atStartOfDay(zone).toEpochSecond()
                out[epoch] = Candle(epoch, o, h, l, c, null, shares / 100L, c * shares, null)
            }
            val candles = out.values.sortedBy { it.epochSeconds }
            val lastDate = candles.lastOrNull()?.let { Instant.ofEpochSecond(it.epochSeconds).atZone(zone).toLocalDate() }
            return if (lastDate == now.toLocalDate() && !StockbitSessionPolicy.marketFinished(now)) candles.dropLast(1) else candles
        }

        private fun num(array: JSONArray?, index: Int): Double? {
            if (array == null || index >= array.length() || array.isNull(index)) return null
            return array.optDouble(index).takeIf { it.isFinite() }
        }
    }
}
