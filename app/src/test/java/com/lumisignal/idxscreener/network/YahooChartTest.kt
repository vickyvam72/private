package com.lumisignal.idxscreener.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class YahooChartTest {
    // 2026-10-08 and 2026-10-09 09:00 WIB, plus a null row Yahoo sometimes emits.
    private val payload = """
        {"chart":{"result":[{"meta":{"symbol":"BBCA.JK","currency":"IDR"},
          "timestamp":[1791424800,1791511200,1791597600],
          "indicators":{"quote":[{"open":[9800,9850,null],"high":[9900,9950,null],"low":[9750,9800,null],
            "close":[9875,9900,null],"volume":[81234500,70000000,null]}]}}],"error":null}}
    """.trimIndent()

    @Test fun parsesDailyCandlesAsLotsAndSkipsNullRows() {
        val now = ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneId.of("Asia/Jakarta"))
        val candles = YahooChartRepository.parse(payload, now)
        assertEquals(2, candles.size)
        assertEquals(812_345L, candles.first().volume)
        assertEquals(9875.0 * 81_234_500, candles.first().tradedValue!!, 1.0)
        assertNull("Yahoo has no trade frequency", candles.first().frequency)
    }
}
