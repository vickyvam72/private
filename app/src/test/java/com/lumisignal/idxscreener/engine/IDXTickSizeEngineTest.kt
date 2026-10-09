package com.lumisignal.idxscreener.engine

import org.junit.Assert.*
import org.junit.Test

class IDXTickSizeEngineTest {
    @Test fun tickBandsAndBoundaries() {
        assertEquals(1, IDXTickSizeEngine.getTickSize(199.0))
        assertEquals(2, IDXTickSizeEngine.getTickSize(200.0))
        assertEquals(2, IDXTickSizeEngine.getTickSize(499.0))
        assertEquals(5, IDXTickSizeEngine.getTickSize(500.0))
        assertEquals(5, IDXTickSizeEngine.getTickSize(1999.0))
        assertEquals(10, IDXTickSizeEngine.getTickSize(2000.0))
        assertEquals(10, IDXTickSizeEngine.getTickSize(4999.0))
        assertEquals(25, IDXTickSizeEngine.getTickSize(5000.0))
    }

    @Test fun roundingAlwaysProducesValidPrice() {
        listOf(51.4,199.9,200.1,499.9,500.1,1999.9,2000.1,4999.9,5000.1,8757.0).forEach { price ->
            assertTrue(IDXTickSizeEngine.isValid(IDXTickSizeEngine.roundDownToValidTick(price)))
            assertTrue(IDXTickSizeEngine.isValid(IDXTickSizeEngine.roundUpToValidTick(price)))
            assertTrue(IDXTickSizeEngine.isValid(IDXTickSizeEngine.roundToValidTick(price)))
        }
    }

    @Test fun expectedRounding() {
        assertEquals(498, IDXTickSizeEngine.roundDownToValidTick(499.0))
        assertEquals(500, IDXTickSizeEngine.roundUpToValidTick(499.0))
        assertEquals(1995, IDXTickSizeEngine.roundDownToValidTick(1999.0))
        assertEquals(2000, IDXTickSizeEngine.roundUpToValidTick(1999.0))
        assertEquals(8750, IDXTickSizeEngine.roundDownToValidTick(8757.0))
        assertEquals(8775, IDXTickSizeEngine.roundUpToValidTick(8757.0))
    }
}
