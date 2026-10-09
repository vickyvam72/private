package com.lumisignal.idxscreener.network

import com.lumisignal.idxscreener.model.DataResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class IdxSyncResumeTest {
    private class MemoryStore : BrokerWindowStore {
        val map = ConcurrentHashMap<String, String>()
        override suspend fun read(key: String): String? = map[key]
        override suspend fun write(key: String, ticker: String, payload: String, ttlMs: Long) { map[key] = payload }
    }

    /** Fake IDX: three liquid stocks every session, one holiday, and a 429 on the first call of some days. */
    private class FakeIdx {
        var calls = 0
        private val throttledOnce = mutableSetOf<String>()
        suspend fun get(url: String): String {
            calls++
            val date = url.substringAfter("date=")
            if (date.endsWith("5") && throttledOnce.add(date)) throw RateLimitException()
            if (date.endsWith("17")) return """{"draw":0,"recordsTotal":0,"data":[]}""" // holiday
            val rows = listOf("BBCA", "BBRI", "TLKM").mapIndexed { i, code ->
                val price = 1000 + i * 100 + (date.takeLast(2).toInt() % 7) * 5
                """{"StockCode":"$code","OpenPrice":$price,"High":${price + 10},"Low":${price - 10},"Close":${price + 5},
                   "Volume":${1_000_000 + i},"Value":${price * 1_000_000L},"Frequency":${500 + i},"ForeignBuy":2000000,"ForeignSell":1000000}"""
            }
            return """{"draw":0,"recordsTotal":3,"data":[${rows.joinToString(",")}]}"""
        }
    }

    private val names = mapOf("BBCA.JK" to "BCA", "BBRI.JK" to "BRI", "TLKM.JK" to "Telkom")

    @Test fun quickPathRefusesColdCacheWithoutNetwork() = runBlocking {
        val fake = FakeIdx()
        val repo = IdxStockSummaryRepository(MemoryStore(), directOverride = fake::get, spacingMs = 0, backoffBaseMs = 1)
        val result = repo.buildSeries(names, maxNetworkDays = 6)
        assertTrue(result is DataResult.Error)
        assertEquals("cold cache must not trigger 70+ downloads on the fast path", 0, fake.calls)
    }

    @Test fun fullSyncSurvivesRateLimitAndHolidaysThenResumesFromCache() = runBlocking {
        val store = MemoryStore()
        val fake = FakeIdx()
        val repo = IdxStockSummaryRepository(store, directOverride = fake::get, spacingMs = 0, backoffBaseMs = 1)
        val first = repo.buildSeries(names)
        assertTrue((first as? DataResult.Error)?.userMessage ?: "", first is DataResult.Success)
        val bulk = (first as DataResult.Success).value
        assertEquals(72, bulk.sessions.size)
        assertEquals(setOf("BBCA.JK", "BBRI.JK", "TLKM.JK"), bulk.series.keys)
        assertEquals(72, bulk.series.getValue("BBCA.JK").candles.size)
        assertEquals(10_000L, bulk.series.getValue("BBCA.JK").candles.last().volume) // shares -> lots
        assertTrue(bulk.foreignNet.getValue("BBCA.JK").all { it.second == 1_000_000.0 })

        // Second run (next screening): everything settled comes from the phone cache.
        val callsBefore = fake.calls
        val second = IdxStockSummaryRepository(store, directOverride = fake::get, spacingMs = 0, backoffBaseMs = 1)
            .buildSeries(names, maxNetworkDays = 6)
        assertTrue((second as? DataResult.Error)?.userMessage ?: "", second is DataResult.Success)
        assertTrue("resumed run should need at most the newest unpublished days", fake.calls - callsBefore <= 6)
    }
}
