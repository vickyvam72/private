package com.lumisignal.idxscreener.engine

import com.lumisignal.idxscreener.model.*
import org.junit.Assert.*
import org.junit.Test

class StrategyEngineTest {
    private fun series(): MarketSeries {
        val candles = (0 until 100).map { index ->
            val base = 100.0 + index * .08
            val pulse = if (index % 7 == 0) 1.0 else 0.0
            Candle(
                epochSeconds = 1_700_000_000L + index * 86_400L,
                open = base,
                high = base + 2.0,
                low = base - 1.5,
                close = base + pulse,
                adjustedClose = null,
                volume = 1_000_000L + index * 3_000L,
                tradedValue = (base + pulse) * (1_000_000L + index * 3_000L),
                frequency = if (index < 90) 100L else 200L + (index - 90) * 100L
            )
        }
        return MarketSeries("TEST.JK", "Test Emiten", "IDR", candles)
    }

    private fun broker() = BrokerAnalysis(
        available = true,
        score = 72.0,
        netBuy = 2_000_000_000.0,
        buyerConcentration = .22,
        sellerConcentration = .14,
        periodNetBuy = mapOf(1 to 200_000_000.0, 3 to 600_000_000.0, 5 to 1_000_000_000.0, 10 to 2_000_000_000.0),
        topBuyers = listOf(BrokerFlowItem("AA", 1_000_000_000.0, 106.0)),
        topSellers = listOf(BrokerFlowItem("ZZ", -500_000_000.0, 107.0)),
        dailyFlows = (0 until 10).map { day -> BrokerDay(day.toLong(), 200_000_000.0, 50_000_000.0, mapOf("AA" to 200_000_000.0), mapOf("ZZ" to 50_000_000.0)) },
        topBuyerPersistenceDays = 10
    )

    private fun market() = StockbitMarketContext(
        ticker = "TEST.JK",
        bestBid = 108.0,
        bestOffer = 109.0,
        spreadTicks = 1,
        uma = false,
        blockingCorporateAction = false,
        tradable = true
    )

    @Test fun producesIndependentEvaluationForAllTenStrategies() {
        val candidates = StrategyEngine.analyze(series(), broker(), market())
        assertEquals(10, candidates.size)
        assertEquals(StrategyType.entries.toSet(), candidates.map { it.strategy }.toSet())
        assertTrue(candidates.all { it.criteria.size == 10 })
        assertTrue(candidates.all { it.ticker == "TEST.JK" })
    }

    @Test fun stockbitFrequencyIsEvaluatedAndCanPass() {
        val frequency = StrategyEngine.analyze(series(), broker(), market()).single { it.strategy == StrategyType.FREQUENCY_CREEP }
        assertTrue(frequency.criteria.filter { it.label.startsWith("RFREQ") }.all { it.state != CriterionState.DATA_UNAVAILABLE })
        assertTrue(frequency.passed)
    }

    @Test fun topFiveNeverFillsWithFailedCandidates() {
        val candidates = StrategyEngine.analyze(series(), broker(), market())
        val top = StrategyEngine.top(candidates, StrategyType.FREQUENCY_CREEP, 5)
        assertEquals(1, top.size)
    }

    @Test fun absentOperationalMetadataDoesNotEraseValidStrategyResults() {
        val frequency = StrategyEngine.analyze(series(), broker(), null)
            .single { it.strategy == StrategyType.FREQUENCY_CREEP }
        assertTrue(frequency.passed)
        assertTrue(frequency.dataLimitations.any { it.contains("UMA") })
        assertTrue(frequency.dataLimitations.any { it.contains("Spread") })
    }

    @Test fun explicitUmaStillBlocksCandidate() {
        val unsafe = market().copy(uma = true)
        assertTrue(StrategyEngine.analyze(series(), broker(), unsafe).none { it.passed })
    }

    @Test fun missingBrokerKeepsDiagnosticScoresButBlocksEveryTopFiveCandidate() {
        val analyses = StrategyEngine.analyze(
            series(), BrokerAnalysis(false, explanation = listOf("HTTP 403")), market()
        )
        assertEquals(10, analyses.size)
        assertTrue(analyses.none { it.passed })
        assertTrue(analyses.all { it.potentiallyEligible || it.criteria.any { criterion -> criterion.state == CriterionState.MISS } })
        assertTrue(analyses.all { it.dataLimitations.any { limitation -> limitation.contains("Broker summary") } })
    }

    @Test fun everyStrategyHasAConciseUniqueDescription() {
        val descriptions = StrategyType.entries.map { it.description }
        assertTrue(descriptions.all { it.length in 20..70 })
        assertEquals(StrategyType.entries.size, descriptions.distinct().size)
    }

    @Test fun legacyStrategyNamesMapToTheirReplacements() {
        assertEquals(StrategyType.BROKER_PRICE_DIVERGENCE, StrategyType.fromStored("FREE_FLOAT_TURNOVER_CREEP"))
        assertEquals(StrategyType.BREAKOUT_RETEST_CONFIRMATION, StrategyType.fromStored("SUPPLY_VACUUM_BREAKOUT"))
    }

    @Test fun aggregateSeedDoesNotFalselyRejectMissingDailyBrokerWindows() {
        val seed = broker().copy(
            periodNetBuy = mapOf(10 to 2_000_000_000.0),
            dailyFlows = emptyList(),
            topBuyerPersistenceDays = null
        )
        val candidate = StrategyEngine.analyze(series(), seed, market())
            .single { it.strategy == StrategyType.BROKER_ACCUMULATION_PERSISTENCE }

        assertEquals(CriterionState.DATA_UNAVAILABLE, candidate.criteria[0].state)
        assertEquals(CriterionState.DATA_UNAVAILABLE, candidate.criteria[1].state)
        assertEquals(CriterionState.DATA_UNAVAILABLE, candidate.criteria[2].state)
    }
}
