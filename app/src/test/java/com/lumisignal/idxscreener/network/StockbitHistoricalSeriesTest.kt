package com.lumisignal.idxscreener.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class StockbitHistoricalSeriesTest {
    @Test fun parsesOfficialValueAndFrequencyWithoutEstimatingThem() {
        val payload = """
            {"data":{"result":[{
              "date":"2026-10-02","open":"3,200","high":"3,250","low":"3,180","close":"3,240",
              "volume":"125000","value":"40500000000","frequency":"1789"
            }]}}
        """.trimIndent()

        val candle = StockbitRepository.parseHistoricalPage(payload).single()

        assertEquals(3200.0, candle.open, 0.0)
        assertEquals(3240.0, candle.close, 0.0)
        assertEquals(125000L, candle.volume)
        assertEquals(40_500_000_000.0, candle.tradedValue!!, 0.0)
        assertEquals(1789L, candle.frequency)
        assertNotNull(candle.epochSeconds)
    }
}
