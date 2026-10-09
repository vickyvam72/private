package com.lumisignal.idxscreener.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdxStockSummaryTest {
    private val payload = """
        {"draw":0,"recordsTotal":3,"recordsFiltered":3,"data":[
          {"IDStockSummary":1,"StockCode":"BBCA","StockName":"Bank Central Asia Tbk.","Date":"2026-10-09T00:00:00",
           "OpenPrice":9800,"High":9900,"Low":9750,"Close":9875,"Previous":9800,"Volume":81234500,"Value":801234567890,
           "Frequency":45678,"ForeignBuy":350000000000,"ForeignSell":300000000000},
          {"IDStockSummary":2,"StockCode":"ZZZZ","StockName":"Tanpa Transaksi Tbk.","Date":"2026-10-09T00:00:00",
           "OpenPrice":0,"High":0,"Low":0,"Close":120,"Previous":120,"Volume":0,"Value":0,
           "Frequency":0,"ForeignBuy":0,"ForeignSell":0},
          {"IDStockSummary":3,"StockCode":"BBCA-W","StockName":"Waran","Date":"2026-10-09T00:00:00",
           "OpenPrice":5,"High":6,"Low":5,"Close":6,"Previous":5,"Volume":100,"Value":600,
           "Frequency":2,"ForeignBuy":0,"ForeignSell":0}
        ]}
    """.trimIndent()

    @Test fun parsesMarketWideRowsAndForeignFlow() {
        val rows = IdxStockSummaryRepository.parseDay(payload)
        val bbca = rows.getValue("BBCA.JK")
        assertEquals(9875.0, bbca.close, 0.0)
        assertEquals(81_234_500L, bbca.volumeShares)
        assertEquals(45_678L, bbca.frequency)
        assertEquals(50_000_000_000.0, bbca.foreignNet, 0.0)
        assertFalse("warrants are not ordinary shares", rows.containsKey("BBCA-W.JK"))
    }

    @Test fun noTradeDayBecomesFlatCandleAtClose() {
        val row = IdxStockSummaryRepository.parseDay(payload).getValue("ZZZZ.JK")
        assertEquals(120.0, row.open, 0.0)
        assertEquals(120.0, row.high, 0.0)
        assertEquals(120.0, row.low, 0.0)
        assertEquals(0L, row.volumeShares)
    }

    @Test fun holidayResponseIsEmptyNotAnError() {
        assertTrue(IdxStockSummaryRepository.parseDay("""{"draw":0,"recordsTotal":0,"data":[]}""").isEmpty())
    }
}
