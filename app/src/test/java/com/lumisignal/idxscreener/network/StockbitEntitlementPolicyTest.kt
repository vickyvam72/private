package com.lumisignal.idxscreener.network

import com.lumisignal.idxscreener.model.StockbitAccountTier
import org.junit.Assert.assertEquals
import org.junit.Test

class StockbitEntitlementPolicyTest {
    @Test fun brokerRowsVerifyPro() {
        assertEquals(StockbitAccountTier.PRO, StockbitEntitlementPolicy.classify(200, "{}", true))
    }

    @Test fun forbiddenMeansNonPro() {
        assertEquals(StockbitAccountTier.NON_PRO, StockbitEntitlementPolicy.classify(403, null, false))
    }

    @Test fun emptySuccessfulResponseStaysUnknown() {
        assertEquals(StockbitAccountTier.UNKNOWN, StockbitEntitlementPolicy.classify(200, "{}", false))
    }

    @Test fun explicitUpgradeMessageMeansNonPro() {
        assertEquals(StockbitAccountTier.NON_PRO, StockbitEntitlementPolicy.classify(200, "Upgrade to Stockbit PRO", false))
    }

    @Test fun unauthorizedSessionIsNotMislabelledNonPro() {
        assertEquals(StockbitAccountTier.UNKNOWN, StockbitEntitlementPolicy.classify(401, "unauthorized", false))
    }
}
