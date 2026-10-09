package com.lumisignal.idxscreener.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockbitPortfolioParserTest {
    @Test fun parsesHoldingsWithoutConvertingLotsIntoShares() {
        val root = JSONObject("""{"data":{"positions":[{"stock_code":"BBCA","company_name":"Bank Central Asia","lot":"12","average_price":"9125","last_price":"9500","market_value":"11400000","unrealized_pl":"450000","unrealized_percent":"4.11"}]}}""")

        val holding = StockbitRepository.parsePortfolioHoldings(root).single()

        assertEquals("BBCA.JK", holding.ticker)
        assertEquals(12.0, holding.lots!!, 0.0)
        assertNull(holding.shares)
        assertEquals(9_125.0, holding.averagePrice!!, 0.0)
    }

    @Test fun ignoresTickerLikeMetadataWithoutPositionQuantityOrAverage() {
        val root = JSONObject("""{"data":{"symbol":"BBRI","status":"ACTIVE"}}""")
        assertEquals(emptyList<com.lumisignal.idxscreener.model.PortfolioHolding>(), StockbitRepository.parsePortfolioHoldings(root))
    }

    @Test fun parsesOrderStatusAndHistorySeparately() {
        val orders = JSONObject("""{"data":{"orders":[{"order_id":"OID-1","symbol":"TLKM","side":"BUY","price":3120,"lots":5,"filled_lot":2,"status":"PARTIAL"}]}}""")
        val history = JSONObject("""{"data":{"trades":[{"trade_id":"T-1","symbol":"TLKM","side":"BUY","trade_price":3120,"lots":2,"amount":624000,"trade_date":"2026-10-05"}]}}""")

        assertEquals("PARTIAL", StockbitRepository.parsePortfolioOrders(orders).single().status)
        assertEquals(624_000.0, StockbitRepository.parsePortfolioTransactions(history).single().amount!!, 0.0)
    }

    @Test fun parsesAndSortsRunningTradeTicks() {
        val root = JSONObject("""{"data":{"date":"2026-10-07","results":[{"symbol":"BBCA","price":9100,"trade_time":"09:01:03"},{"symbol":"TLKM","price":3000,"trade_time":"09:01:02"},{"symbol":"BBCA","price":"9075","trade_time":"09:01:01"}]}}""")

        val ticks = StockbitRepository.parseRunningTrades(root, "BBCA")

        assertEquals(2, ticks.size)
        assertEquals(9_075.0, ticks.first().price, 0.0)
        assertEquals(9_100.0, ticks.last().price, 0.0)
        assertTrue(ticks.first().epochSeconds < ticks.last().epochSeconds)
    }
}
