package com.lumisignal.idxscreener.engine

import com.lumisignal.idxscreener.model.BrokerAnalysis
import com.lumisignal.idxscreener.model.Candle
import com.lumisignal.idxscreener.model.MarketSeries
import com.lumisignal.idxscreener.model.ScreeningWeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningEngineTest {
    @Test
    fun singleTickerAnalysisProducesCompleteTransparentScoresWithoutInventingBrokerData() {
        val candles = (1L..30L).map { day ->
            val close = 6_000.0 + day * 10
            Candle(
                epochSeconds = day * 86_400,
                open = close - 10,
                high = close + 30,
                low = close - 30,
                close = close,
                adjustedClose = close,
                volume = if (day == 30L) 3_000_000 else 1_000_000
            )
        }
        val series = MarketSeries("BBCA.JK", "PT Bank Central Asia Tbk", "IDR", candles)
        val brokerUnavailable = BrokerAnalysis(
            available = false,
            explanation = listOf("Broker Analysis Unavailable")
        )

        val result = ScreeningEngine.analyze(series, brokerUnavailable, ScreeningWeights())

        assertNotNull(result)
        result!!
        assertEquals("BBCA.JK", result.ticker)
        assertNull(result.scores.broker)
        assertFalse(result.scores.brokerAvailable)
        assertTrue(result.scores.volume in 0.0..100.0)
        assertTrue(result.scores.anomaly in 0.0..100.0)
        assertTrue(result.scores.technical in 0.0..100.0)
        assertTrue(result.scores.adjustedFinal in 0.0..100.0)
        assertTrue(result.why.any { it.contains("Broker Analysis Unavailable") })
        assertTrue(result.why.any { it.contains("Chasing risk") && it.contains("return 20D") })
        assertTrue(result.tradePlan.entryLow > result.tradePlan.stopLoss)
        assertTrue(result.tradePlan.takeProfit > result.tradePlan.entryHigh)
    }
}
