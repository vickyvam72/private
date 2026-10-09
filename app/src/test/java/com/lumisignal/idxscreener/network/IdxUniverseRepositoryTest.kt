package com.lumisignal.idxscreener.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdxUniverseRepositoryTest {
    @Test
    fun parserKeepsOnlyValidIdxEquitiesAndSortsByEstimatedAverageValue() {
        val payload = """
            {
              "totalCount": 844,
              "data": [
                {"s":"IDX:SMALL","d":["SMALL","Small Tbk",100,2000,1000000]},
                {"s":"NASDAQ:AAPL","d":["AAPL","Apple",200,999999,1000000]},
                {"s":"IDX:BIG1","d":["BIG1","Big Tbk",500,10000,1000000]},
                {"s":"IDX:BAD-W","d":["BAD-W","Warrant",50,100,1000]}
              ]
            }
        """.trimIndent()

        val result = IdxUniverseRepository.parseTradingView(payload)

        assertEquals(844, result.totalCount)
        assertEquals(listOf("BIG1.JK", "SMALL.JK"), result.tickers.map { it.ticker })
        assertEquals(5_000_000.0, result.tickers.first().estimatedAverageValue10d!!, 0.0)
    }

    @Test
    fun parserPreservesTickerWhenOptionalLiquidityValuesAreNull() {
        val payload = """{"totalCount":1,"data":[{"s":"IDX:TEST","d":["TEST","Test Tbk",null,null,null]}]}"""

        val result = IdxUniverseRepository.parseTradingView(payload)

        assertEquals("TEST.JK", result.tickers.single().ticker)
        assertNull(result.tickers.single().averageVolume10d)
        assertNull(result.tickers.single().estimatedAverageValue10d)
    }

    @Test
    fun officialIdxParserUsesOnlyValidLiveCompanySymbols() {
        val payload = """
            {
              "recordsTotal": 965,
              "data": [
                {"KodeEmiten":"BBCA","NamaEmiten":"PT Bank Central Asia Tbk"},
                {"KodeEmiten":"AALI","NamaEmiten":"Astra Agro Lestari Tbk"},
                {"KodeEmiten":"BAD-W","NamaEmiten":"Warrant"},
                {"KodeEmiten":"","NamaEmiten":"Missing"}
              ]
            }
        """.trimIndent()

        val result = IdxUniverseRepository.parseIdxOfficial(payload)

        assertEquals("IDX_OFFICIAL_LIVE", result.source)
        assertEquals(965, result.totalCount)
        assertEquals(listOf("AALI.JK", "BBCA.JK"), result.tickers.map { it.ticker })
    }

    @Test
    fun tradingViewParserKeepsFallbackSourceIdentity() {
        val payload = """{"totalCount":1,"data":[{"s":"IDX:BBCA","d":["BBCA","Bank Central Asia",9000,1000,1]}]}"""

        val result = IdxUniverseRepository.parseTradingView(payload, "TRADINGVIEW_GLOBAL_IDX_LIVE")

        assertEquals("TRADINGVIEW_GLOBAL_IDX_LIVE", result.source)
    }
}
