package com.lumisignal.idxscreener.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class StockbitSessionPolicyTest {
    private val jakarta = ZoneId.of("Asia/Jakarta")

    @Test fun before1615UsesPreviousCalendarDateBucket() {
        val now = ZonedDateTime.of(2026, 10, 7, 10, 5, 0, 0, jakarta)
        assertFalse(StockbitSessionPolicy.marketFinished(now))
        assertEquals(LocalDate.of(2026, 10, 6), StockbitSessionPolicy.completedDataBucket(now))
    }

    @Test fun at1615UsesCurrentDateBucket() {
        val now = ZonedDateTime.of(2026, 10, 7, 16, 15, 0, 0, jakarta)
        assertTrue(StockbitSessionPolicy.marketFinished(now))
        assertEquals(LocalDate.of(2026, 10, 7), StockbitSessionPolicy.completedDataBucket(now))
    }
}
