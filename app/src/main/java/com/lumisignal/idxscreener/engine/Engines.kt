package com.lumisignal.idxscreener.engine

import com.lumisignal.idxscreener.model.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** General equity price fractions are isolated here so a future IDX rule change is one edit. */
object IDXTickSizeEngine {
    data class Band(val minimum: Int, val maximumExclusive: Int?, val tick: Int)
    val bands = listOf(
        Band(0, 200, 1), Band(200, 500, 2), Band(500, 2_000, 5),
        Band(2_000, 5_000, 10), Band(5_000, null, 25)
    )

    fun getTickSize(price: Double): Int {
        val p = max(0, floor(price).toInt())
        return bands.first { p >= it.minimum && (it.maximumExclusive == null || p < it.maximumExclusive) }.tick
    }

    fun roundDownToValidTick(price: Double): Int {
        val p = max(0.0, price)
        val tick = getTickSize(p)
        return floor(p / tick).toInt() * tick
    }

    fun roundUpToValidTick(price: Double): Int {
        val down = roundDownToValidTick(price)
        if (abs(price - down) < 1e-8) return down
        val candidate = down + getTickSize(max(price, down.toDouble()))
        return if (candidate == 200 || candidate == 500 || candidate == 2_000 || candidate == 5_000) candidate else candidate
    }

    fun roundToValidTick(price: Double): Int {
        val down = roundDownToValidTick(price)
        val up = roundUpToValidTick(price)
        return if (price - down <= up - price) down else up
    }

    fun isValid(price: Int): Boolean = price >= 0 && price % getTickSize(price.toDouble()) == 0
}

object Statistics {
    fun mean(values: List<Double>) = if (values.isEmpty()) 0.0 else values.average()
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted(); val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2.0 else s[m]
    }
    fun zScore(current: Double, history: List<Double>): Double {
        if (history.size < 5) return 0.0
        val mean = history.average()
        val sd = sqrt(history.sumOf { (it - mean).pow(2) } / history.size)
        return if (sd < 1e-9) 0.0 else (current - mean) / sd
    }
    fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val alpha = 2.0 / (period + 1.0)
        return values.drop(1).fold(values.first()) { acc, v -> v * alpha + acc * (1 - alpha) }
    }
    fun rsi(values: List<Double>, period: Int = 14): Double {
        if (values.size < 2) return 50.0
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(period)
        val gain = changes.filter { it > 0 }.sum() / max(1, changes.size)
        val loss = -changes.filter { it < 0 }.sum() / max(1, changes.size)
        if (loss == 0.0) return 100.0
        return 100.0 - 100.0 / (1.0 + gain / loss)
    }
    fun atr(c: List<Candle>, period: Int = 14): Double {
        if (c.size < 2) return 0.0
        return c.zipWithNext { prev, now -> max(now.high - now.low, max(abs(now.high - prev.close), abs(now.low - prev.close))) }.takeLast(period).average()
    }
}

object BrokerFlowEngine {
    fun analyze(days: List<BrokerDay>): BrokerAnalysis {
        if (days.isEmpty()) return BrokerAnalysis(false)
        val window = days.takeLast(10)
        val last5 = window.takeLast(5)
        val positives = last5.count { it.netBuy > 0 }
        val totalAbs = window.sumOf { abs(it.netBuy) }.coerceAtLeast(1.0)
        val directional = (window.sumOf { it.netBuy } / totalAbs).coerceIn(-1.0, 1.0)
        val persistence = positives / last5.size.toDouble()
        val recentAcceleration = if (last5.size >= 4) {
            val first = last5.take(2).sumOf { it.netBuy }
            val second = last5.takeLast(2).sumOf { it.netBuy }
            ((second - first) / totalAbs).coerceIn(-1.0, 1.0)
        } else 0.0
        val buyers = last5.flatMap { it.topBuyers.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val sellers = last5.flatMap { it.topSellers.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val buyerCon = buyers.values.maxOrNull()?.div(buyers.values.sum().coerceAtLeast(1.0)) ?: 0.0
        val sellerCon = sellers.values.maxOrNull()?.div(sellers.values.sum().coerceAtLeast(1.0)) ?: 0.0
        val score = (50 + directional * 25 + persistence * 20 + recentAcceleration * 10 + (buyerCon - sellerCon) * 10).coerceIn(0.0, 100.0)
        val reason = buildList {
            add("Broker net buy pada $positives/${last5.size} sesi")
            fun flow(period:Int)=window.takeLast(period.coerceAtMost(window.size)).sumOf{it.netBuy}
            add("Top-broker flow 1D/3D/5D/10D: ${flow(1).compactIdr()} / ${flow(3).compactIdr()} / ${flow(5).compactIdr()} / ${flow(10).compactIdr()}")
            if (recentAcceleration > .1) add("Akumulasi broker meningkat pada sesi terbaru")
            if (sellerCon > buyerCon + .15) add("Konsentrasi seller lebih tinggi daripada buyer")
        }
        return BrokerAnalysis(true, score, window.sumOf { it.netBuy }, positives, last5.size, buyerCon, sellerCon, reason)
    }

    /**
     * Scores aggregated 1D/3D/5D/10D Stockbit windows. This keeps a market-wide
     * scan bounded: each horizon costs one aggregate request instead of fetching
     * ten separate daily broker summaries per ticker.
     */
    fun analyzePeriods(
        periods: Map<Int, BrokerDay>,
        buyersByPeriod: Map<Int, List<BrokerFlowItem>>,
        sellersByPeriod: Map<Int, List<BrokerFlowItem>>,
        foreignNetBuy: Double?,
        foreignByPeriod: Map<Int, Double> = emptyMap()
    ): BrokerAnalysis {
        if (periods.isEmpty()) return BrokerAnalysis(false)
        val ordered = listOf(1, 3, 5, 10).mapNotNull { period -> periods[period]?.let { period to it } }
        val positiveWindows = ordered.count { it.second.netBuy > 0 }
        val longest = ordered.maxByOrNull { it.first }!!.second
        val detailPeriod = if (buyersByPeriod.containsKey(1) || sellersByPeriod.containsKey(1)) 1 else ordered.maxOf { it.first }
        val buyerDetails = buyersByPeriod[detailPeriod].orEmpty()
        val sellerDetails = sellersByPeriod[detailPeriod].orEmpty()
        val gross = (longest.buyValue + longest.sellValue).coerceAtLeast(1.0)
        val directional = (longest.netBuy / gross).coerceIn(-1.0, 1.0)
        val consistency = positiveWindows / ordered.size.toDouble()
        val buyTotal = buyerDetails.sumOf { it.netValue }.coerceAtLeast(1.0)
        val sellTotal = sellerDetails.sumOf { kotlin.math.abs(it.netValue) }.coerceAtLeast(1.0)
        val buyerCon = buyerDetails.take(3).sumOf { it.netValue } / buyTotal
        val sellerCon = sellerDetails.take(3).sumOf { kotlin.math.abs(it.netValue) } / sellTotal
        val shortImproving = (periods[1]?.netBuy ?: 0.0) > 0 &&
            (periods[3]?.netBuy ?: 0.0) > (periods[10]?.netBuy ?: 0.0) / 4.0
        val score = (50 + directional * 35 + (consistency - .5) * 35 +
            (buyerCon - sellerCon) * 15 + if (shortImproving) 7 else 0).coerceIn(0.0, 100.0)
        val netByPeriod = ordered.associate { it.first to it.second.netBuy }
        val interpretation = when {
            positiveWindows >= 3 && longest.netBuy > 0 -> "Top-5 buyer lebih dominan di mayoritas horizon; tetap perlu konfirmasi respons harga dan risiko distribusi."
            positiveWindows <= 1 && longest.netBuy < 0 -> "Top-5 seller lebih dominan; kenaikan harga perlu diperlakukan hati-hati."
            (periods[1]?.netBuy ?: 0.0) > 0 && longest.netBuy <= 0 -> "Top-5 buyer unggul pada 1D, tetapi dominasi 10D belum mengonfirmasi akumulasi."
            (periods[1]?.netBuy ?: 0.0) < 0 && longest.netBuy > 0 -> "Dominasi buyer 10D masih positif, tetapi 1D menunjukkan tekanan seller."
            else -> "Imbalance Top-5 broker campuran; belum ada dominasi buyer atau seller yang kuat."
        }
        val reasons = buildList {
            add("Dominasi Top-5 buyer positif pada $positiveWindows/${ordered.size} horizon (1D/3D/5D/10D)")
            add("Top-5 broker imbalance 1D/3D/5D/10D: " + listOf(1, 3, 5, 10).joinToString(" / ") { netByPeriod[it]?.compactIdr() ?: "N/A" })
            add(interpretation)
            foreignByPeriod[1]?.let { add("Foreign investor net flow 1D: ${it.compactIdr()}") }
            (foreignByPeriod[10] ?: foreignNetBuy)?.let { add("Foreign investor net flow 10D: ${it.compactIdr()}") }
            if (foreignByPeriod.isEmpty() && foreignNetBuy == null) add("Foreign investor flow tidak tersedia dari respons Stockbit")
        }
        return BrokerAnalysis(
            available = true,
            score = score,
            netBuy = longest.netBuy,
            persistenceDays = positiveWindows,
            windowDays = ordered.size,
            buyerConcentration = buyerCon.coerceIn(0.0, 1.0),
            sellerConcentration = sellerCon.coerceIn(0.0, 1.0),
            explanation = reasons,
            periodNetBuy = netByPeriod,
            topBuyers = buyerDetails.take(5),
            topSellers = sellerDetails.take(5),
            periodTopBuyers = buyersByPeriod.mapValues { it.value.take(5) },
            periodTopSellers = sellersByPeriod.mapValues { it.value.take(5) },
            foreignPeriodNetBuy = foreignByPeriod,
            foreignNetBuy = foreignNetBuy,
            foreignDataAvailable = foreignNetBuy != null,
            flowInterpretation = interpretation
        )
    }
}

private fun Double.compactIdr():String{val s=if(this>=0)"+" else "-";val v=abs(this);return when{v>=1_000_000_000_000->"$s${"%.2f".format(v/1_000_000_000_000)}T";v>=1_000_000_000->"$s${"%.2f".format(v/1_000_000_000)}B";v>=1_000_000->"$s${"%.2f".format(v/1_000_000)}M";else->"$s${v.toLong()}"}}

object ScreeningEngine {
    fun analyze(series: MarketSeries, broker: BrokerAnalysis, weights: ScreeningWeights): Candidate? {
        val c = series.candles.filter { it.close > 0 && it.volume >= 0 }.sortedBy { it.epochSeconds }
        if (c.size < 21 || !weights.isValid()) return null
        val last = c.last(); val prior = c.dropLast(1)
        val vol5 = prior.takeLast(5).map { it.volume.toDouble() }.average().coerceAtLeast(1.0)
        val vol20List = prior.takeLast(20).map { it.volume.toDouble() }
        val vol20 = vol20List.average().coerceAtLeast(1.0)
        val rvol5 = last.volume / vol5
        val rvol20 = last.volume / vol20
        val values20 = prior.takeLast(20).map { it.estimatedTradingValue }
        val valueExpansion = last.estimatedTradingValue / Statistics.median(values20).coerceAtLeast(1.0)
        val volZ = Statistics.zScore(last.volume.toDouble(), vol20List)
        val closes = c.map { it.close }
        val ema20 = Statistics.ema(closes.takeLast(40), 20)
        val ema50 = Statistics.ema(closes.takeLast(80), 50)
        val rsi = Statistics.rsi(closes)
        val atr = Statistics.atr(c)
        val resistance = prior.takeLast(20).maxOf { it.high }
        val support = prior.takeLast(20).minOf { it.low }
        val dayReturn = if (prior.last().close > 0) (last.close / prior.last().close - 1) * 100 else 0.0
        val twentyReturn = if (prior.takeLast(20).first().close > 0) (last.close / prior.takeLast(20).first().close - 1) * 100 else 0.0
        val ema20Distance = if (ema20 > 0) (last.close - ema20) / ema20 * 100 else 0.0
        val volumeScore = (35 + min(35.0, max(0.0, rvol20 - 1) * 25) + min(20.0, max(0.0, valueExpansion - 1) * 12) + min(10.0, max(0.0, rvol5 - 1) * 8)).coerceIn(0.0, 100.0)
        val priceCompression = prior.takeLast(10).map { (it.high - it.low) / it.close }.average()
        val anomalyScore = (30 + min(35.0, max(0.0, volZ) * 13) + min(25.0, max(0.0, valueExpansion - 1) * 12) + if (dayReturn in -1.5..3.0 && rvol20 > 1.4) 10 else 0).coerceIn(0.0, 100.0)
        val technicalScore = (50.0
            + (if (last.close >= ema20) 12.0 else -8.0)
            + (if (ema20 >= ema50) 12.0 else -5.0)
            + (if (last.close >= resistance) 18.0 else 0.0)
            + (if (rsi in 45.0..75.0) 8.0 else 0.0)).coerceIn(0.0, 100.0)
        val distribution = (if (dayReturn > 2 && rvol20 > 1.8 && (broker.score ?: 50.0) < 45) 55.0 else 10.0) + (broker.sellerConcentration ?: 0.0) * 25
        val chasing = (max(0.0, twentyReturn - 8) * 3 + max(0.0, ema20Distance - 5) * 4).coerceIn(0.0, 100.0)
        val raw = if (broker.available && broker.score != null) {
            broker.score * weights.broker / 100 + volumeScore * weights.volume / 100 + anomalyScore * weights.anomaly / 100 + technicalScore * weights.technical / 100
        } else {
            val usable = weights.volume + weights.anomaly + weights.technical
            if (usable <= 0) return null
            (volumeScore * weights.volume + anomalyScore * weights.anomaly + technicalScore * weights.technical) / usable
        }
        val adjusted = (raw - distribution.coerceIn(0.0, 100.0) * .15 - chasing * .10).coerceIn(0.0, 100.0)
        val flowPricePov = if (!broker.available) null else when {
            (broker.netBuy ?: 0.0) > 0 && dayReturn in -1.5..2.5 && rvol20 >= 1.2 ->
                "POV flow-price: indikasi quiet accumulation—net buy broker positif dan aktivitas naik, tetapi harga belum bergerak terlalu jauh."
            (broker.netBuy ?: 0.0) > 0 && dayReturn > 2.5 && rvol20 >= 1.4 ->
                "POV flow-price: akumulasi ikut mendorong harga; momentum ada, tetapi chasing risk harus diperiksa."
            (broker.netBuy ?: 0.0) < 0 && dayReturn > 1.0 && rvol20 >= 1.3 ->
                "POV flow-price: distribution risk—harga naik saat broker flow agregat negatif."
            (broker.netBuy ?: 0.0) < 0 && dayReturn < 0 && rvol20 >= 1.3 ->
                "POV flow-price: selling pressure—broker distribution disertai penurunan harga dan volume aktif."
            else -> "POV flow-price: broker flow dan respons harga masih campuran; belum cukup kuat untuk menyimpulkan akumulasi/distribusi dominan."
        }
        val setup = when {
            distribution >= 60 -> SetupType.DISTRIBUTION_RISK
            last.close >= resistance && rvol20 >= 1.5 && (broker.score ?: 55.0) >= 50 -> SetupType.BREAKOUT
            twentyReturn < 8 && rvol20 >= 1.25 && valueExpansion >= 1.25 && priceCompression < .07 -> SetupType.EARLY_ACCUMULATION
            dayReturn > 1 && rvol20 >= 1.4 && chasing < 70 -> SetupType.MOMENTUM
            else -> SetupType.WATCHLIST
        }
        val plan = TradePlanEngine.create(last.close, support, resistance, atr, chasing)
        val why = buildList {
            addAll(broker.explanation)
            flowPricePov?.let { add(it) }
            if (!broker.available) add("Broker Analysis Unavailable — score dinormalisasi tanpa data broker")
            add("RVOL20 ${"%.2f".format(rvol20)}x; RVOL5 ${"%.2f".format(rvol5)}x")
            add("Estimasi trading value ${"%.2f".format(valueExpansion)}x median 20 sesi")
            if (volZ > 1.5) add("Volume Z-Score +${"%.2f".format(volZ)}")
            add("Chasing risk ${chasing.toInt()}/100: return 20D ${"%+.2f".format(twentyReturn)}%; jarak dari EMA20 ${"%+.2f".format(ema20Distance)}%")
            add("Struktur harga: support ${support.toInt()}, resistance ${resistance.toInt()}")
            if (chasing >= 60) add("Chasing risk tinggi; tunggu pullback")
            if (distribution >= 50) add("Distribution risk perlu perhatian")
        }
        val brokerWithPov = if (flowPricePov == null) broker else broker.copy(flowInterpretation = flowPricePov)
        return Candidate(series.ticker, series.companyName, last.epochSeconds, last.close, setup,
            ScoreBreakdown(broker.score, volumeScore, anomalyScore, technicalScore, distribution.coerceIn(0.0,100.0), chasing, raw, adjusted, broker.available),
            plan, rvol5, rvol20, valueExpansion, volZ, why, brokerWithPov,
            TechnicalSnapshot(ema20, ema50, rsi, atr, dayReturn, twentyReturn))
    }
}

object CandidateRankingEngine {
    fun score(candidate: Candidate, dimension: String): Double? {
        if (!candidate.scores.brokerAvailable) return null
        val broker = candidate.scores.broker ?: return null
        val weights = ScreeningWeights.forRanking(dimension) ?: return null
        val weighted = (
            broker * weights.broker +
                candidate.scores.volume * weights.volume +
                candidate.scores.anomaly * weights.anomaly +
                candidate.scores.technical * weights.technical
            ) / weights.total
        return if (dimension.equals("OVERALL", ignoreCase = true)) {
            (weighted - candidate.scores.distributionRisk * .15 - candidate.scores.chasingRisk * .10)
                .coerceIn(0.0, 100.0)
        } else {
            weighted.coerceIn(0.0, 100.0)
        }
    }

    fun top(candidates: List<Candidate>, dimension: String, limit: Int = 5): List<Candidate> =
        candidates.filter { it.scores.brokerAvailable && it.scores.broker != null }
            .mapNotNull { candidate -> score(candidate, dimension)?.let { candidate to it } }
            .sortedWith(compareByDescending<Pair<Candidate,Double>> { it.second }.thenBy { it.first.ticker })
            .take(limit).map { it.first }
}

object TradePlanEngine {
    fun create(close: Double, support: Double, resistance: Double, atr: Double, chasingRisk: Double): TradePlan {
        val safeAtr = if (atr > 0) atr else close * .025
        val pullback = if (chasingRisk >= 60) close - safeAtr * .45 else close - safeAtr * .20
        val entryLow = IDXTickSizeEngine.roundDownToValidTick(max(support, pullback))
        val entryHigh = IDXTickSizeEngine.roundUpToValidTick(max(entryLow.toDouble(), min(close + safeAtr * .12, resistance + safeAtr * .15)))
        val midpoint = (entryLow + entryHigh) / 2.0
        val stop = IDXTickSizeEngine.roundDownToValidTick(min(support - safeAtr * .20, midpoint - safeAtr * .90))
        val rawTp = max(resistance + safeAtr * .8, midpoint + (midpoint - stop) * 1.6)
        val tp = IDXTickSizeEngine.roundUpToValidTick(rawTp)
        val rr = if (midpoint > stop) (tp - midpoint) / (midpoint - stop) else 0.0
        return TradePlan(entryLow, entryHigh, tp, stop, rr, midpoint)
    }
}

data class AuditDecision(
    val status: SignalStatus,
    val entryEpoch: Long? = null,
    val entryPrice: Double? = null,
    val exitEpoch: Long? = null,
    val exitPrice: Double? = null,
    val returnPct: Double? = null
)

object AuditEngine {
    fun evaluate(
        entryLow: Double,
        entryHigh: Double,
        tp: Double,
        sl: Double,
        status: SignalStatus,
        candles: List<Candle>,
        expirySessions: Int,
        existingEntryPrice: Double? = null
    ): AuditDecision {
        if (status in setOf(SignalStatus.TAKE_PROFIT, SignalStatus.STOP_LOSS, SignalStatus.EXPIRED, SignalStatus.AMBIGUOUS, SignalStatus.SUPERSEDED)) {
            return AuditDecision(status, entryPrice = existingEntryPrice)
        }
        var holding = status == SignalStatus.HOLDING
        var entryDate: Long? = null
        var entryPrice: Double? = existingEntryPrice
        candles.sortedBy { it.epochSeconds }.forEachIndexed { index, candle ->
            if (!holding && candle.low <= entryHigh && candle.high >= entryLow) {
                holding = true
                entryDate = candle.epochSeconds
                entryPrice = triggeredEntryPrice(entryLow, entryHigh, candle.open)
                val tpHit = candle.high >= tp; val slHit = candle.low <= sl
                if (tpHit && slHit) return AuditDecision(SignalStatus.AMBIGUOUS, entryDate, entryPrice, candle.epochSeconds)
                if (tpHit) return terminal(SignalStatus.TAKE_PROFIT, entryDate, entryPrice, candle.epochSeconds, tp)
                if (slHit) return terminal(SignalStatus.STOP_LOSS, entryDate, entryPrice, candle.epochSeconds, sl)
            } else if (holding) {
                val tpHit = candle.high >= tp; val slHit = candle.low <= sl
                if (tpHit && slHit) return AuditDecision(SignalStatus.AMBIGUOUS, entryDate, entryPrice, candle.epochSeconds)
                if (tpHit) return terminal(SignalStatus.TAKE_PROFIT, entryDate, entryPrice, candle.epochSeconds, tp)
                if (slHit) return terminal(SignalStatus.STOP_LOSS, entryDate, entryPrice, candle.epochSeconds, sl)
            }
            if (!holding && index + 1 >= expirySessions) return AuditDecision(SignalStatus.EXPIRED)
        }
        return AuditDecision(if (holding) SignalStatus.HOLDING else SignalStatus.WAITING_ENTRY, entryDate, entryPrice)
    }

    private fun triggeredEntryPrice(entryLow: Double, entryHigh: Double, open: Double): Double =
        IDXTickSizeEngine.roundToValidTick(open.coerceIn(entryLow, entryHigh)).toDouble()
            .coerceIn(entryLow, entryHigh)

    private fun terminal(status: SignalStatus, entryEpoch: Long?, entryPrice: Double?, exitEpoch: Long, exitPrice: Double): AuditDecision {
        val actualEntry = entryPrice ?: return AuditDecision(status, entryEpoch, null, exitEpoch, exitPrice)
        return AuditDecision(status, entryEpoch, actualEntry, exitEpoch, exitPrice, (exitPrice - actualEntry) / actualEntry * 100)
    }

    /**
     * Expiry is counted only from completed daily sessions. Live 1m/5m bars are
     * evaluated afterwards so ten intraday bars can never expire a signal.
     */
    fun evaluateCompletedThenLive(
        entryLow: Double,
        entryHigh: Double,
        tp: Double,
        sl: Double,
        status: SignalStatus,
        completedSessions: List<Candle>,
        liveCandles: List<Candle>,
        expirySessions: Int,
        existingEntryPrice: Double? = null
    ): AuditDecision {
        val completed = evaluate(entryLow, entryHigh, tp, sl, status, completedSessions, expirySessions, existingEntryPrice)
        if (completed.status in setOf(SignalStatus.TAKE_PROFIT, SignalStatus.STOP_LOSS, SignalStatus.EXPIRED, SignalStatus.AMBIGUOUS)) return completed
        if (liveCandles.isEmpty()) return completed
        val live = evaluate(entryLow, entryHigh, tp, sl, completed.status, liveCandles, Int.MAX_VALUE, completed.entryPrice)
        return live.copy(
            entryEpoch = live.entryEpoch ?: completed.entryEpoch,
            entryPrice = live.entryPrice ?: completed.entryPrice
        )
    }

    /**
     * Resolves intraday order from Stockbit running-trade prints. Unlike an OHLC
     * candle, a tick sequence can prove whether entry, TP, or SL happened first.
     */
    fun evaluateTicks(
        entryLow: Double,
        entryHigh: Double,
        tp: Double,
        sl: Double,
        status: SignalStatus,
        ticks: List<TradeTick>,
        existingEntryPrice: Double? = null,
        existingEntryEpoch: Long? = null
    ): AuditDecision {
        if (status in setOf(SignalStatus.TAKE_PROFIT, SignalStatus.STOP_LOSS, SignalStatus.EXPIRED, SignalStatus.AMBIGUOUS, SignalStatus.SUPERSEDED)) {
            return AuditDecision(status, existingEntryEpoch, existingEntryPrice)
        }
        var holding = status == SignalStatus.HOLDING
        var entryEpoch = existingEntryEpoch
        var entryPrice = existingEntryPrice
        for (tick in ticks.sortedBy { it.epochSeconds }) {
            if (!holding && tick.price in entryLow..entryHigh) {
                holding = true
                entryEpoch = tick.epochSeconds
                entryPrice = IDXTickSizeEngine.roundToValidTick(tick.price).toDouble().coerceIn(entryLow, entryHigh)
                continue
            }
            if (holding && tick.price >= tp) return terminal(SignalStatus.TAKE_PROFIT, entryEpoch, entryPrice, tick.epochSeconds, tp)
            if (holding && tick.price <= sl) return terminal(SignalStatus.STOP_LOSS, entryEpoch, entryPrice, tick.epochSeconds, sl)
        }
        return AuditDecision(if (holding) SignalStatus.HOLDING else SignalStatus.WAITING_ENTRY, entryEpoch, entryPrice)
    }
}

data class Performance(val total: Int, val finished: Int, val wins: Int, val losses: Int, val holding: Int, val waiting: Int, val winRate: Double, val cumulativeReturn: Double, val compoundedReturn: Double, val averageWin: Double, val averageLoss: Double, val profitFactor: Double)

object PerformanceEngine {
    fun calculate(statusesAndReturns: List<Pair<SignalStatus, Double?>>): Performance {
        val realized = statusesAndReturns.filter { it.first == SignalStatus.TAKE_PROFIT || it.first == SignalStatus.STOP_LOSS }
        val wins = realized.filter { it.first == SignalStatus.TAKE_PROFIT }; val losses = realized.filter { it.first == SignalStatus.STOP_LOSS }
        val values = realized.mapNotNull { it.second }
        val winValues = wins.mapNotNull { it.second }; val lossValues = losses.mapNotNull { it.second }
        val compounded = (values.fold(1.0) { acc, r -> acc * (1 + r / 100.0) } - 1) * 100
        val grossWin = winValues.sum(); val grossLoss = abs(lossValues.sum())
        return Performance(statusesAndReturns.size, realized.size, wins.size, losses.size,
            statusesAndReturns.count { it.first == SignalStatus.HOLDING }, statusesAndReturns.count { it.first == SignalStatus.WAITING_ENTRY },
            if (realized.isEmpty()) 0.0 else wins.size * 100.0 / realized.size, values.sum(), compounded,
            winValues.averageOrZero(), lossValues.averageOrZero(), if (grossLoss == 0.0) if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0 else grossWin / grossLoss)
    }
    private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
}
