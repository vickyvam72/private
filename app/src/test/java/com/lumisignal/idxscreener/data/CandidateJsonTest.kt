package com.lumisignal.idxscreener.data

import com.lumisignal.idxscreener.model.*
import org.junit.Assert.*
import org.junit.Test

class CandidateJsonTest {
    @Test
    fun brokerFlowDetailsSurviveScreeningSnapshotRoundTrip() {
        val broker = BrokerAnalysis(
            available = true, score = 82.0, netBuy = 12_000_000_000.0,
            explanation = listOf("Flow multi-periode positif"),
            periodNetBuy = mapOf(1 to 2_000_000_000.0, 10 to 12_000_000_000.0),
            topBuyers = listOf(BrokerFlowItem("YP", 8_000_000_000.0, 1_525.0, "Asing")),
            topSellers = listOf(BrokerFlowItem("PD", -3_000_000_000.0, 1_520.0, "Lokal")),
            periodTopBuyers = mapOf(1 to listOf(BrokerFlowItem("YP", 8_000_000_000.0, 1_525.0, "Asing")), 10 to listOf(BrokerFlowItem("CC", 10_000_000_000.0))),
            periodTopSellers = mapOf(1 to listOf(BrokerFlowItem("PD", -3_000_000_000.0, 1_520.0, "Lokal"))),
            foreignPeriodNetBuy = mapOf(1 to 16_470_000_000.0, 10 to 4_000_000_000.0),
            foreignNetBuy = 4_000_000_000.0, foreignDataAvailable = true,
            flowInterpretation = "Quiet accumulation",
            dailyFlows = listOf(BrokerDay(20_000, 5_000_000_000.0, 2_000_000_000.0, mapOf("YP" to 5_000_000_000.0), mapOf("PD" to 2_000_000_000.0))),
            topBuyerPersistenceDays = 7
        )
        val candidate = Candidate(
            "TEST.JK", "Test Tbk", 1, 1_525.0, SetupType.EARLY_ACCUMULATION,
            ScoreBreakdown(82.0,80.0,75.0,60.0,10.0,20.0,78.0,74.5,true),
            TradePlan(1_500,1_525,1_650,1_450,2.0,1_512.5),
            2.0,2.5,3.0,2.2,listOf("Reason"),broker
        )

        val decoded = CandidateJson.decode(CandidateJson.encode(candidate))

        assertEquals(12_000_000_000.0, decoded.brokerAnalysis.periodNetBuy[10]!!, 0.0)
        assertEquals("YP", decoded.brokerAnalysis.topBuyers.single().code)
        assertEquals("Asing", decoded.brokerAnalysis.topBuyers.single().investorClass)
        assertEquals("CC", decoded.brokerAnalysis.periodTopBuyers[10]!!.single().code)
        assertEquals(4_000_000_000.0, decoded.brokerAnalysis.foreignNetBuy!!, 0.0)
        assertEquals(16_470_000_000.0, decoded.brokerAnalysis.foreignPeriodNetBuy[1]!!, 0.0)
        assertEquals("Quiet accumulation", decoded.brokerAnalysis.flowInterpretation)
        assertEquals(listOf("Flow multi-periode positif"), decoded.brokerAnalysis.explanation)
        assertEquals(7, decoded.brokerAnalysis.topBuyerPersistenceDays)
        assertEquals(3_000_000_000.0, decoded.brokerAnalysis.dailyFlows.single().netBuy, 0.0)
        assertEquals(5_000_000_000.0, decoded.brokerAnalysis.dailyFlows.single().topBuyers["YP"]!!, 0.0)
    }
}
