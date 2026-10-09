package com.lumisignal.idxscreener.network

import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerPayloadDescribeTest {
    @Test fun emptySuccessAnswerIsSummarisedWithCountsAndEchoedDates() {
        val raw = """{"message":"Successfully retrieved market detector data","data":{"bandar_detector":{"value":0,"total_buyer":0},
            "broker_summary":{"brokers_buy":[],"brokers_sell":[],"symbol":"SMDR"},"from":"2026-09-04","to":"2026-09-04"}}"""
        val line = StockbitRepository.describeBrokerPayload(raw)
        assertTrue(line, line.contains("buy=0") && line.contains("sell=0"))
        assertTrue(line, line.contains("echo=2026-09-04..2026-09-04"))
        assertTrue(line, line.contains("msg=Successfully retrieved"))
    }

    @Test fun unreadableBodyIsReportedNotThrown() {
        assertTrue(StockbitRepository.describeBrokerPayload("<html>").startsWith("tidak bisa dibaca"))
    }
}
