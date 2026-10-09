package com.lumisignal.idxscreener.engine

import com.lumisignal.idxscreener.model.*
import org.junit.Assert.*
import org.junit.Test

class BrokerFlowAndRankingTest {
    @Test
    fun aggregatedBrokerWindowsExposeBuyersForeignFlowAndObjectiveInterpretation() {
        fun window(period: Int, buy: Double, sell: Double) = period to BrokerDay(period.toLong(), buy, sell)
        val analysis = BrokerFlowEngine.analyzePeriods(
            periods = mapOf(
                window(1, 15_000_000_000.0, 5_000_000_000.0),
                window(3, 35_000_000_000.0, 14_000_000_000.0),
                window(5, 60_000_000_000.0, 25_000_000_000.0),
                window(10, 90_000_000_000.0, 42_000_000_000.0)
            ),
            buyersByPeriod = mapOf(
                1 to listOf(BrokerFlowItem("YP", 30_000_000_000.0), BrokerFlowItem("CC", 20_000_000_000.0)),
                10 to listOf(BrokerFlowItem("CC", 55_000_000_000.0))
            ),
            sellersByPeriod = mapOf(
                1 to listOf(BrokerFlowItem("PD", -12_000_000_000.0), BrokerFlowItem("XC", -8_000_000_000.0)),
                10 to listOf(BrokerFlowItem("PD", -42_000_000_000.0))
            ),
            foreignNetBuy = 7_500_000_000.0,
            foreignByPeriod = mapOf(1 to 16_470_000_000.0, 10 to 7_500_000_000.0)
        )

        assertTrue(analysis.available)
        assertEquals(4, analysis.persistenceDays)
        assertEquals(48_000_000_000.0, analysis.periodNetBuy[10]!!, 0.0)
        assertEquals("YP", analysis.topBuyers.first().code)
        assertEquals("CC", analysis.periodTopBuyers[10]!!.first().code)
        assertEquals(7_500_000_000.0, analysis.foreignNetBuy!!, 0.0)
        assertEquals(16_470_000_000.0, analysis.foreignPeriodNetBuy[1]!!, 0.0)
        assertEquals(7_500_000_000.0, analysis.foreignPeriodNetBuy[10]!!, 0.0)
        assertTrue(analysis.flowInterpretation!!.contains("Top-5 buyer"))
    }

    @Test
    fun everyRankingDimensionExcludesCandidatesWithoutMandatoryBrokerData() {
        fun candidate(ticker: String, broker: Double?, volume: Double, anomaly: Double, technical: Double, overall: Double) = Candidate(
            ticker, ticker, 1, 100.0, SetupType.WATCHLIST,
            ScoreBreakdown(broker, volume, anomaly, technical, 10.0, 10.0, overall, overall, broker != null),
            TradePlan(98, 100, 110, 94, 2.0, 99.0), 1.0, 1.0, 1.0, 0.0, emptyList()
        )
        val a = candidate("A.JK", 90.0, 50.0, 60.0, 40.0, 70.0)
        val b = candidate("B.JK", null, 95.0, 40.0, 80.0, 82.0)
        val c = candidate("C.JK", 65.0, 70.0, 99.0, 55.0, 75.0)

        assertEquals("C.JK", CandidateRankingEngine.top(listOf(a,b,c), "OVERALL", 1).single().ticker)
        assertEquals("A.JK", CandidateRankingEngine.top(listOf(a,b,c), "BROKER", 1).single().ticker)
        assertEquals("C.JK", CandidateRankingEngine.top(listOf(a,b,c), "VOLUME", 1).single().ticker)
        assertEquals("C.JK", CandidateRankingEngine.top(listOf(a,b,c), "ANOMALY", 1).single().ticker)
        assertEquals("C.JK", CandidateRankingEngine.top(listOf(a,b,c), "TECHNICAL", 1).single().ticker)
        assertEquals(2, CandidateRankingEngine.top(listOf(a,b,c), "BROKER").size)

        val aisa = candidate("AISA.JK", 69.0, 100.0, 90.0, 92.0, 74.0)
        val irsx = candidate("IRSX.JK", 74.0, 100.0, 90.0, 100.0, 73.0)
        assertEquals("IRSX.JK",CandidateRankingEngine.top(listOf(aisa,irsx),"BROKER",1).single().ticker)
        assertEquals(74.0,CandidateRankingEngine.score(irsx,"BROKER")!!,0.0)
        assertEquals(69.0,CandidateRankingEngine.score(aisa,"BROKER")!!,0.0)
        assertEquals(listOf("AISA.JK","IRSX.JK"),CandidateRankingEngine.top(listOf(irsx,aisa),"VOLUME",2).map{it.ticker})
    }

    @Test
    fun rankingWeightsAreAutomaticPerTopFiveMode() {
        assertEquals(ScreeningWeights(25.0,25.0,25.0,25.0),ScreeningWeights.forRanking("OVERALL"))
        assertEquals(100.0,ScreeningWeights.forRanking("BROKER")!!.broker,0.0)
        assertEquals(100.0,ScreeningWeights.forRanking("VOLUME")!!.volume,0.0)
        assertEquals(100.0,ScreeningWeights.forRanking("ANOMALY")!!.anomaly,0.0)
        assertEquals(100.0,ScreeningWeights.forRanking("TECHNICAL")!!.technical,0.0)
    }
}
