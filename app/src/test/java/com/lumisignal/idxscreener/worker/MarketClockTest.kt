package com.lumisignal.idxscreener.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MarketClockTest {
    private val jakarta = ZoneId.of("Asia/Jakarta")

    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, jakarta)

    @Test fun mondaySessionsAndBreakAreApplied() {
        assertTrue(MarketClock.isOpen(at(5, 9, 0)))
        assertFalse(MarketClock.isOpen(at(5, 12, 30)))
        assertTrue(MarketClock.isOpen(at(5, 13, 30)))
        assertFalse(MarketClock.isOpen(at(5, 15, 50)))
    }

    @Test fun fridayUsesItsOwnSessionBreak() {
        assertTrue(MarketClock.isOpen(at(2, 11, 29)))
        assertFalse(MarketClock.isOpen(at(2, 11, 30)))
        assertFalse(MarketClock.isOpen(at(2, 13, 59)))
        assertTrue(MarketClock.isOpen(at(2, 14, 0)))
    }

    @Test fun weekendIsClosed() {
        assertFalse(MarketClock.isOpen(at(3, 10, 0)))
        assertFalse(MarketClock.isOpen(at(4, 10, 0)))
    }
}
