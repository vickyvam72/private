package com.lumisignal.idxscreener

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactVolumeTest {
    @Test fun formatsThousandsMillionsAndBillionsWithoutLongZeroRuns() {
        assertEquals("950", compactVolume(950))
        assertEquals("1,25K", compactVolume(1_250))
        assertEquals("12,35M", compactVolume(12_350_000))
        assertEquals("135,7M", compactVolume(135_669_000))
        assertEquals("1,25B", compactVolume(1_250_000_000))
    }
}
