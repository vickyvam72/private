package com.lumisignal.idxscreener.network

import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockbitBrokerAndCorporateActionTest {
    @Test fun oneSessionBrokerRangeUsesSameInclusiveDate() {
        val session = LocalDate.of(2026, 10, 7)
        val query = StockbitBrokerQuery.build(session, session, "ALL")

        assertTrue(query.contains("from=2026-10-07"))
        assertTrue(query.contains("to=2026-10-07"))
        assertFalse(query.contains("period="))
    }

    @Test fun aggregateBrokerRangeNeverMixesPeriodWithDates() {
        val from = LocalDate.of(2026, 9, 24)
        val to = LocalDate.of(2026, 10, 7)
        val query = StockbitBrokerQuery.build(from, to, "FOREIGN")

        assertTrue(query.contains("from=2026-09-24"))
        assertTrue(query.contains("to=2026-10-07"))
        assertTrue(query.contains("INVESTOR_TYPE_FOREIGN"))
        assertFalse(query.contains("period="))
    }

    @Test fun corporateActionIconUrlIsNeverRenderedAsDescription() {
        val payload = JSONObject(
            """{"active":true,"icon":"https://assets.stockbit.com/images/corp_action_event_icon.svg","text":"Perusahaan Memiliki Corporate Action"}"""
        )

        val parsed = StockbitRepository.parseCorporateAction(payload)

        assertTrue(parsed.active == true)
        assertEquals(listOf("Perusahaan Memiliki Corporate Action"), parsed.descriptions)
    }

    @Test fun inactiveCorporateActionDoesNotLeakIconOrText() {
        val payload = JSONObject(
            """{"active":false,"icon_url":"https://assets.stockbit.com/icon.svg","text":"Tidak aktif"}"""
        )

        val parsed = StockbitRepository.parseCorporateAction(payload)

        assertFalse(parsed.active!!)
        assertEquals(emptyList<String>(), parsed.descriptions)
    }
}
