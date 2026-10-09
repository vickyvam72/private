package com.lumisignal.idxscreener.engine

import com.lumisignal.idxscreener.model.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import java.time.Instant
import java.time.ZoneId

/**
 * Deterministic implementation of the ten strategy definitions supplied for
 * Lumi Signal. A missing field is never silently treated as a match.
 */
object StrategyEngine {
    private data class Metrics(
        val candles: List<Candle>,
        val last: Candle,
        val prior: List<Candle>,
        val medVol60: Double,
        val medVol20: Double,
        val medVol5: Double,
        val medValue60: Double,
        val medValue20: Double,
        val medValue5: Double,
        val medFreq60: Double,
        val medFreq20: Double,
        val medFreq5: Double,
        val rvol5: Double,
        val rvol1: Double,
        val rtv5: Double,
        val rtv1: Double,
        val rfreq5: Double,
        val rfreq1: Double,
        val avgTradeSize5: Double,
        val avgTradeSize20: Double,
        val frequencyRisingSessions: Int,
        val frequencyAvailable: Boolean,
        val return20: Double,
        val range20: Double,
        val support: Double,
        val resistance: Double,
        val minorResistance: Double,
        val atr10: Double,
        val atr14: Double,
        val atr60: Double,
        val atrCompression: Double,
        val bbw: Double,
        val bbwPercentile: Double,
        val clv10: Double,
        val obvSlope: Double,
        val adlSlope: Double,
        val upDownRatio: Double,
        val higherLows: Int,
        val baseDays: Int,
        val downVolumeRatio: Double,
        val priceImpactRatio: Double,
        val ema20: Double,
        val ema50: Double,
        val rsi14: Double,
        val dayReturn: Double,
        val volumeZ: Double,
        val medianRange5: Double,
        val medianRange20: Double,
        val volumeContracting: Boolean,
        val absorption: Candle?,
        val spring: Candle?,
        val oldResistance: Double,
        val markupIndex: Int?,
        val breakoutIndex: Int?,
        val breakoutResistance: Double?,
        val retestIndex: Int?
    )

    fun analyze(series: MarketSeries, broker: BrokerAnalysis, market: StockbitMarketContext? = null): List<Candidate> {
        val candles = series.candles
            .filter { it.close > 0 && it.high >= it.low && it.volume >= 0 }
            .map(::adjustedCandle)
            .sortedBy { it.epochSeconds }
        if (candles.size < 60) return emptyList()
        val metrics = metrics(candles)
        val mandatory = mandatoryCriteria(metrics, broker, market)
        val mandatoryPassed = mandatory.none { it.state == CriterionState.MISS }
        val operationalGatesComplete = mandatory.none { it.state == CriterionState.DATA_UNAVAILABLE }
        val commonLimitations = buildList {
            mandatory.filter { it.state == CriterionState.DATA_UNAVAILABLE }
                .forEach { add("${it.label}: ${it.evidence}") }
            if (!broker.available) add("Broker summary: ${broker.explanation.joinToString().ifBlank { "tidak tersedia" }}")
        }

        return StrategyType.entries.map { strategy ->
            val criteria = strategyCriteria(strategy, metrics, broker)
            val matched = criteria.count { it.state == CriterionState.MATCH }
            val unavailable = criteria.filter { it.state == CriterionState.DATA_UNAVAILABLE }
                .map { "${it.label}: ${it.evidence}" }
            val maximumMatches = matched + criteria.count { it.state == CriterionState.DATA_UNAVAILABLE }
            val potentiallyEligible = mandatoryPassed && maximumMatches >= strategy.minimumMatches &&
                hardGateCouldPass(strategy, criteria)
            // Operational metadata (UMA, spread, corporate action) blocks a
            // candidate when Stockbit explicitly reports a bad state. A missing
            // optional metadata key is disclosed as a limitation, but must not
            // erase all ten strategy results. Strategy inputs remain fail-closed.
            val passed = broker.available && mandatoryPassed && unavailable.isEmpty() &&
                matched >= strategy.minimumMatches && hardGatePassed(strategy, criteria)
            val score = rankingScore(strategy, metrics, broker, matched, passed)
            candidate(
                series, metrics, broker, strategy, criteria, score, passed,
                commonLimitations + unavailable,
                calculationComplete = broker.available && unavailable.isEmpty(),
                operationalGatesComplete = operationalGatesComplete,
                potentiallyEligible = potentiallyEligible
            )
        }
    }

    /**
     * Coarse, deliberately generous prefilter for price-only sources (e.g. Yahoo Finance, which has
     * no trade frequency or broker data). Missing frequency/broker inputs count as satisfiable, and
     * [tolerance] extra criteria may miss, so a stock that could qualify on exact Stockbit data is not
     * discarded merely because the coarse source differs slightly. Never used to rank or to pass.
     */
    fun prefilterCouldQualify(series: MarketSeries, tolerance: Int = 1): Boolean {
        val candles = series.candles
            .filter { it.close > 0 && it.high >= it.low && it.volume >= 0 }
            .map(::adjustedCandle)
            .sortedBy { it.epochSeconds }
        if (candles.size < 60) return false
        val metrics = metrics(candles)
        val noBroker = BrokerAnalysis(false, explanation = listOf("prefilter"))
        val mandatory = mandatoryCriteria(metrics, noBroker, null).map { criterion ->
            if (!metrics.frequencyAvailable && criterion.state == CriterionState.MISS && criterion.label.contains("frekuensi", true))
                criterion.copy(state = CriterionState.DATA_UNAVAILABLE) else criterion
        }
        if (mandatory.any { it.state == CriterionState.MISS }) return false
        return StrategyType.entries.any { strategy ->
            val criteria = strategyCriteria(strategy, metrics, noBroker)
            val possible = criteria.count { it.state != CriterionState.MISS }
            possible >= strategy.minimumMatches - tolerance
        }
    }

    fun top(candidates: List<Candidate>, strategy: StrategyType, limit: Int = 5): List<Candidate> =
        candidates.asSequence()
            .filter { it.strategy == strategy && it.passed }
            .sortedWith(compareByDescending<Candidate> { it.strategyScore }.thenBy { it.ticker })
            .take(limit)
            .toList()

    private fun mandatoryCriteria(m: Metrics, broker: BrokerAnalysis, market: StockbitMarketContext?): List<StrategyCriterion> = listOf(
        match("Minimal 60 sesi data", m.candles.size >= 60, "${m.candles.size} sesi valid"),
        match("Pasar reguler / bukan waran", true, "Universe saham IDX dan broker summary MARKET_BOARD_REGULER"),
        if (broker.available) match("Broker summary Stockbit tersedia", true, broker.flowInterpretation ?: "broker flow tervalidasi")
        else unavailable("Broker summary Stockbit tersedia", broker.explanation.joinToString().ifBlank { "broker summary tidak tersedia" }),
        if (market?.tradable != null) match("Tidak suspensi / baru dibuka", market.tradable, "tradable=${market.tradable}${market.marketStatus?.let { "; status=$it" }.orEmpty()}")
        else match("Tidak suspensi / baru dibuka", m.candles.takeLast(20).count { it.volume > 0 } >= 15, "${m.candles.takeLast(20).count { it.volume > 0 }}/20 sesi bervolume"),
        if (market?.uma != null) match("Tidak sedang UMA", market.uma == false, if (market.uma) "Stockbit menandai UMA" else "UMA=false dari Stockbit")
        else unavailable("Tidak sedang UMA", "field UMA Stockbit tidak tersedia"),
        match("Aktif minimal 15 dari 20 sesi", m.candles.takeLast(20).count { it.volume > 0 } >= 15, "${m.candles.takeLast(20).count { it.volume > 0 }}/20 sesi"),
        match("Maksimal tiga sesi tanpa transaksi", m.candles.takeLast(20).count { it.volume <= 0 } <= 3, "${m.candles.takeLast(20).count { it.volume <= 0 }} sesi tanpa volume"),
        if (m.frequencyAvailable) match("Median frekuensi 20 hari", m.medFreq20 >= 50, "${m.medFreq20.toInt()} transaksi/hari") else unavailable("Median frekuensi 20 hari", "respons Stockbit tidak memuat frekuensi"),
        if (market?.spreadTicks != null) match("Spread maksimum tiga fraksi", market.spreadTicks <= 3, "${market.spreadTicks} fraksi; bid ${market.bestBid?.toInt()}, offer ${market.bestOffer?.toInt()}")
        else unavailable("Spread maksimum tiga fraksi", "best bid/offer Stockbit tidak tersedia"),
        if (market?.blockingCorporateAction != null) match("Tidak ada corporate action aktif", market.blockingCorporateAction == false, if (market.blockingCorporateAction) market.corporateActions.joinToString().ifBlank { "corp_action aktif" } else "corp_action kosong dari Stockbit")
        else unavailable("Tidak ada corporate action aktif", "field corp_action Stockbit tidak tersedia"),
        match("Harga, volume, nilai, frekuensi tersedia", m.frequencyAvailable, if (m.frequencyAvailable) "Stockbit OHLCV + value + frequency tersedia" else "frequency/value Stockbit tidak lengkap"),
        match("Baseline median 60 sesi", m.medVol60 > 0 && m.medValue60 > 0, "median volume dan nilai 60 sesi valid"),
        match("Tidak ada diskontinuitas harga ekstrem", !hasSuspiciousPriceDiscontinuity(m.candles),
            if (hasSuspiciousPriceDiscontinuity(m.candles)) "terdeteksi gap >35%; kemungkinan corporate action/data discontinuity" else "tidak ada gap >35% pada 60 sesi"),
        match("Tanpa minimum Rp10 miliar", true, "perbandingan relatif; tidak ada ambang nilai absolut")
    )

    private fun strategyCriteria(s: StrategyType, m: Metrics, b: BrokerAnalysis): List<StrategyCriterion> = when (s) {
        StrategyType.QUIET_ACCUMULATION -> listOf(
            match("Return 20 hari −10% s.d. +15%", m.return20 in -10.0..15.0, pct(m.return20)),
            match("Range 20 hari maksimal 20%", m.range20 <= .20, pct(m.range20 * 100)),
            match("RTV 5/60 minimal 1,3", m.rtv5 >= 1.3, ratio(m.rtv5)),
            match("RVOL 5/60 1,2–3", m.rvol5 in 1.2..3.0, ratio(m.rvol5)),
            match("OBV atau A/D menanjak", m.obvSlope > 0 || m.adlSlope > 0, "OBV ${signed(m.obvSlope)}, A/D ${signed(m.adlSlope)}"),
            match("CLV 10 hari > 0,15", m.clv10 > .15, dec(m.clv10)),
            match("Up/down volume minimal 1,3", m.upDownRatio >= 1.3, ratio(m.upDownRatio)),
            match("Minimal dua higher low", m.higherLows >= 2, "${m.higherLows} higher low"),
            match("Harga maksimal 15% di atas EMA20", (m.last.close / m.ema20 - 1) <= .15, pct((m.last.close / m.ema20 - 1) * 100)),
            match("Belum ada candle RVOL >5 dan ekstrem", m.rvol1 <= 5 || m.dayReturn < 10, "RVOL ${ratio(m.rvol1)}, return ${pct(m.dayReturn)}")
        )
        StrategyType.ABSORPTION_AT_SUPPORT -> {
            val a = m.absorption
            val absorptionFlow = a?.let { brokerNetOn(b, it) }
            listOf(
                match("Support jelas", m.support > 0, rupiah(m.support)),
                match("Support diuji minimal dua kali", supportTouches(m) >= 2, "${supportTouches(m)} pengujian"),
                match("Jarak pengujian maksimal 3%", supportTouches(m, .03) >= 2, "${supportTouches(m, .03)} pengujian dalam pita 3%"),
                match("Volume uji support minimal 1,5×", a != null && a.volume >= m.medVol60 * 1.5, a?.let { ratio(it.volume / m.medVol60) } ?: "tidak ditemukan"),
                match("Harga tidak jatuh >3%", a != null && a.close >= m.support * .97, a?.let { pct((it.close / m.support - 1) * 100) } ?: "tidak ditemukan"),
                match("Close di atas midpoint", a != null && a.close >= (a.high + a.low) / 2, a?.let { rupiah(it.close) } ?: "tidak ditemukan"),
                match("Lower shadow jelas", a != null && lowerShadow(a) > body(a), a?.let { "shadow ${dec(lowerShadow(it))}" } ?: "tidak ditemukan"),
                match("CLV absorption positif", a != null && clv(a) > 0, a?.let { dec(clv(it)) } ?: "tidak ditemukan"),
                match("A/D tidak lower low", m.adlSlope >= 0, signed(m.adlSlope)),
                if (absorptionFlow != null) match("Broker buyer net buy saat uji", absorptionFlow > 0, compact(absorptionFlow)) else unavailable("Broker buyer net buy saat uji", "snapshot broker pada tanggal absorption tidak tersedia")
            )
        }
        StrategyType.BROKER_ACCUMULATION_PERSISTENCE -> listOf(
            if (b.dailyFlows.isNotEmpty()) match("Broker summary 10 sesi harian", b.dailyFlows.size >= 10, "${b.dailyFlows.size}/10 snapshot harian")
            else unavailable("Broker summary 10 sesi harian", "tahap seed baru memuat agregat 10D"),
            if (b.topBuyerPersistenceDays != null) match("Top-3 buyer net buyer minimal 6/10", b.topBuyerPersistenceDays >= 6, "${b.topBuyerPersistenceDays}/10 sesi") else unavailable("Top-3 buyer net buyer minimal 6/10", "snapshot broker harian belum lengkap"),
            if (b.periodNetBuy.containsKey(10) && b.periodNetBuy.containsKey(3)) match("Net buy kumulatif meningkat", b.periodNetBuy.getValue(10) > 0 && b.periodNetBuy.getValue(3) > 0, flow(b))
            else unavailable("Net buy kumulatif meningkat", "agregat broker 3D/10D belum lengkap"),
            match("Harga 20 hari belum naik >15%", m.return20 <= 15, pct(m.return20)),
            match("Average buy broker maksimal 7%", b.topBuyers.firstOrNull()?.averagePrice?.let { abs(m.last.close / it - 1) <= .07 } == true, b.topBuyers.firstOrNull()?.averagePrice?.let(::rupiah) ?: "average buy tidak tersedia"),
            match("Buyer concentration 10–30%", b.buyerConcentration?.let { it in .10..0.30 } == true, b.buyerConcentration?.let { pct(it * 100) } ?: "tidak tersedia"),
            match("Seller lebih tersebar", (b.sellerConcentration ?: 1.0) < (b.buyerConcentration ?: 0.0), "buyer ${pct((b.buyerConcentration ?: 0.0) * 100)}; seller ${pct((b.sellerConcentration ?: 0.0) * 100)}"),
            match("Buyer utama bukan top seller", b.topBuyers.take(3).map { it.code }.intersect(b.topSellers.take(3).map { it.code }.toSet()).isEmpty(), "irisan top-3 diperiksa"),
            if (b.dailyFlows.size >= 10) match("Tidak ada tiga sesi net sell beruntun", maxConsecutiveNetSell(b.dailyFlows) < 3, "maksimum ${maxConsecutiveNetSell(b.dailyFlows)} sesi")
            else unavailable("Tidak ada tiga sesi net sell beruntun", "snapshot broker harian belum lengkap"),
            match("Harga bertahan dekat average buy", b.topBuyers.firstOrNull()?.averagePrice?.let { m.last.close >= it * .93 } == true, b.topBuyers.firstOrNull()?.averagePrice?.let(::rupiah) ?: "average buy tidak tersedia")
        )
        StrategyType.FREQUENCY_CREEP -> listOf(
            if (m.frequencyAvailable) match("RFREQ 5/60 minimal 1,5", m.rfreq5 >= 1.5, ratio(m.rfreq5)) else unavailable("RFREQ 5/60 minimal 1,5", "frekuensi Stockbit tidak tersedia"),
            if (m.frequencyAvailable) match("RFREQ lebih tinggi dari RVOL", m.rfreq5 > m.rvol5, "RFREQ ${ratio(m.rfreq5)} vs RVOL ${ratio(m.rvol5)}") else unavailable("RFREQ lebih tinggi dari RVOL", "frekuensi Stockbit tidak tersedia"),
            if (m.frequencyAvailable) match("Average trade size mengecil/stabil", m.avgTradeSize5 <= m.avgTradeSize20 * 1.10, "5D ${compact(m.avgTradeSize5)} vs 20D ${compact(m.avgTradeSize20)}") else unavailable("Average trade size mengecil/stabil", "frekuensi Stockbit tidak tersedia"),
            match("RTV minimal 1,3", m.rtv5 >= 1.3, ratio(m.rtv5)),
            if (m.frequencyAvailable) match("Frekuensi naik minimal tiga sesi", m.frequencyRisingSessions >= 3, "${m.frequencyRisingSessions} kenaikan beruntun") else unavailable("Frekuensi naik minimal tiga sesi", "frekuensi Stockbit tidak tersedia"),
            match("Return 20 hari maksimal 15%", m.return20 <= 15, pct(m.return20)),
            match("Range 20 hari maksimal 20%", m.range20 <= .20, pct(m.range20 * 100)),
            match("OBV/A-D tidak turun", m.obvSlope >= 0 || m.adlSlope >= 0, "OBV ${signed(m.obvSlope)}, A/D ${signed(m.adlSlope)}"),
            match("Close harian netral/positif", m.dayReturn >= -1, pct(m.dayReturn)),
            if (m.frequencyAvailable) match("Kenaikan bukan lonjakan satu hari", m.rfreq1 <= m.rfreq5 * 2.5, "RFREQ1 ${ratio(m.rfreq1)} vs RFREQ5 ${ratio(m.rfreq5)}") else unavailable("Kenaikan bukan lonjakan satu hari", "frekuensi Stockbit tidak tersedia")
        )
        StrategyType.BROKER_PRICE_DIVERGENCE -> listOf(
            match("Harga 10–20D masih datar/lemah", m.return20 in -10.0..5.0, pct(m.return20)),
            match("Range 20 hari maksimal 20%", m.range20 <= .20, pct(m.range20 * 100)),
            if (b.periodNetBuy.containsKey(10)) match("Broker net buy 10D positif", b.periodNetBuy.getValue(10) > 0, compact(b.periodNetBuy.getValue(10)))
            else unavailable("Broker net buy 10D positif", "agregat broker 10D belum tersedia"),
            if (b.periodNetBuy.containsKey(5)) match("Broker net buy 5D tetap positif", b.periodNetBuy.getValue(5) > 0, compact(b.periodNetBuy.getValue(5)))
            else unavailable("Broker net buy 5D tetap positif", "agregat broker 5D belum tersedia"),
            if (b.topBuyerPersistenceDays != null) match("Buyer dominan aktif minimal 6/10", b.topBuyerPersistenceDays >= 6, "${b.topBuyerPersistenceDays}/10 sesi") else unavailable("Buyer dominan aktif minimal 6/10", "snapshot broker harian belum lengkap"),
            match("A/D Line menanjak melawan harga", m.adlSlope > 0, signed(m.adlSlope)),
            match("OBV tidak membuat pelemahan", m.obvSlope >= 0, signed(m.obvSlope)),
            match("CLV10 positif", m.clv10 > 0, dec(m.clv10)),
            match("Up/down volume minimal 1,2", m.upDownRatio >= 1.2, ratio(m.upDownRatio)),
            match("Tidak ada distribusi ekstrem terbaru", !(m.rvol1 >= 4 && m.dayReturn < -3 && clv(m.last) < -.5), "RVOL ${ratio(m.rvol1)}; return ${pct(m.dayReturn)}; CLV ${dec(clv(m.last))}")
        )
        StrategyType.VOLATILITY_COMPRESSION -> listOf(
            match("Base minimal 10 hari", m.baseDays >= 10, "${m.baseDays} hari"),
            match("ATR10/ATR60 maksimal 0,8", m.atrCompression <= .8, ratio(m.atrCompression)),
            match("BBW pada 20% terendah 60 hari", m.bbwPercentile <= .20, "persentil ${pct(m.bbwPercentile * 100)}"),
            match("Median range 5D < 20D", m.medianRange5 < m.medianRange20, "${dec(m.medianRange5)} < ${dec(m.medianRange20)}"),
            match("Volume mengecil bertahap", m.volumeContracting, "median volume 5D ${ratio(m.medVol5 / m.medVol20)} vs 20D"),
            match("Minimal dua higher low", m.higherLows >= 2, "${m.higherLows} higher low"),
            match("Jarak ke resistance maksimal 5%", (m.resistance / m.last.close - 1) in 0.0..0.05, pct((m.resistance / m.last.close - 1) * 100)),
            match("Close dominan di atas midpoint", m.clv10 > 0, dec(m.clv10)),
            match("OBV bertahan/naik", m.obvSlope >= 0, signed(m.obvSlope)),
            if (b.periodNetBuy.containsKey(1)) match("Broker belum net seller", b.periodNetBuy.getValue(1) >= 0, compact(b.periodNetBuy.getValue(1)))
            else unavailable("Broker belum net seller", "broker summary 1D belum tersedia")
        )
        StrategyType.SHAKEOUT_SPRING_RECLAIM -> {
            val spring = m.spring
            val test = spring?.let { sp -> m.candles.dropWhile { it.epochSeconds <= sp.epochSeconds }.firstOrNull() }
            val springFlow = spring?.let { brokerNetOn(b, it) }
            listOf(
                match("Support jelas", m.support > 0, rupiah(m.support)),
                match("Support diuji minimal dua kali", supportTouches(m) >= 2, "${supportTouches(m)} pengujian"),
                match("Break support 1–5%", spring != null, spring?.let { pct((it.low / m.support - 1) * 100) } ?: "tidak ditemukan"),
                match("Breakdown singkat", spring != null && m.last.close >= m.support, spring?.let { "reclaim pada window 20D" } ?: "tidak ditemukan"),
                match("Close kembali di atas support", spring != null && spring.close >= m.support, spring?.let { rupiah(it.close) } ?: "tidak ditemukan"),
                match("Lower shadow / close dekat high", spring != null && (lowerShadow(spring) > body(spring) || clv(spring) >= .5), spring?.let { "CLV ${dec(clv(it))}" } ?: "tidak ditemukan"),
                match("Volume spring minimal 1,5×", spring != null && spring.volume >= m.medVol60 * 1.5, spring?.let { ratio(it.volume / m.medVol60) } ?: "tidak ditemukan"),
                match("Volume retest maksimal 60% spring", spring != null && test != null && test.volume <= spring.volume * .6, if (spring != null && test != null) ratio(test.volume.toDouble() / spring.volume.coerceAtLeast(1)) else "retest belum tersedia"),
                if (springFlow != null) match("Broker kembali net buy pada spring", springFlow > 0, compact(springFlow)) else unavailable("Broker kembali net buy pada spring", "snapshot broker pada tanggal spring tidak tersedia"),
                match("OBV/A-D tidak breakdown", m.obvSlope >= 0 || m.adlSlope >= 0, "OBV ${signed(m.obvSlope)}, A/D ${signed(m.adlSlope)}")
            )
        }
        StrategyType.MARKUP_IGNITION -> listOf(
            match("Base sebelumnya minimal 10 hari", m.baseDays >= 10, "${m.baseDays} hari"),
            match("Break resistance / highest close 20D", m.last.close > m.resistance, "close ${rupiah(m.last.close)} vs resistance ${rupiah(m.resistance)}"),
            match("RVOL1/60 minimal 3", m.rvol1 >= 3, ratio(m.rvol1)),
            match("RTV1/60 minimal 3", m.rtv1 >= 3, ratio(m.rtv1)),
            if (m.frequencyAvailable) match("RFREQ minimal 2", m.rfreq1 >= 2, ratio(m.rfreq1)) else unavailable("RFREQ minimal 2", "frekuensi Stockbit tidak tersedia"),
            match("Range candle minimal 1,5×", candleRange(m.last) >= m.medianRange20 * 1.5, ratio(candleRange(m.last) / m.medianRange20.coerceAtLeast(.0001))),
            match("Close pada 25% atas candle", clv(m.last) >= .5, "CLV ${dec(clv(m.last))}"),
            match("Close tidak kembali ke base", m.last.close > m.resistance, rupiah(m.last.close)),
            match("OBV/A-D membuat high baru", m.obvSlope > 0 || m.adlSlope > 0, "OBV ${signed(m.obvSlope)}, A/D ${signed(m.adlSlope)}"),
            if (b.periodNetBuy.containsKey(1)) match("Broker belum top seller", b.periodNetBuy.getValue(1) >= 0, compact(b.periodNetBuy.getValue(1)))
            else unavailable("Broker belum top seller", "broker summary 1D belum tersedia")
        )
        StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> {
            val markup = m.markupIndex
            val impulseHigh = markup?.let { index -> m.candles.drop(index).maxOfOrNull { it.high } }
            val breakout = markup?.let { m.candles[it].close }
            val correction = if (impulseHigh != null && breakout != null && impulseHigh > breakout) (impulseHigh - m.last.low) / (impulseHigh - breakout) else 1.0
            val consolidation = markup?.let { m.candles.lastIndex - it } ?: 0
            val markupVolume = markup?.let { m.candles[it].volume.toDouble() }
            listOf(
                match("Markup Ignition sebelumnya valid", markup != null, markup?.let { "terdeteksi ${consolidation} sesi lalu" } ?: "tidak ditemukan"),
                match("Naik minimal 10% setelah breakout", breakout != null && impulseHigh != null && impulseHigh / breakout - 1 >= .10, if (breakout != null && impulseHigh != null) pct((impulseHigh / breakout - 1) * 100) else "tidak tersedia"),
                match("Konsolidasi 3–10 hari", consolidation in 3..10, "$consolidation hari"),
                match("Koreksi maksimal 50% impuls", correction <= .5, pct(correction * 100)),
                match("Volume koreksi maksimal 60% markup", markupVolume != null && m.medVol5 <= markupVolume * .6, markupVolume?.let { ratio(m.medVol5 / it) } ?: "tidak tersedia"),
                match("Tetap di atas resistance lama", m.last.close >= m.oldResistance, "close ${rupiah(m.last.close)}; resistance lama ${rupiah(m.oldResistance)}"),
                match("Terbentuk higher low", m.higherLows >= 1, "${m.higherLows} higher low"),
                match("OBV tidak lower low", m.obvSlope >= 0, signed(m.obvSlope)),
                if (b.periodNetBuy.containsKey(1)) match("Broker netral/net buy", b.periodNetBuy.getValue(1) >= 0, compact(b.periodNetBuy.getValue(1)))
                else unavailable("Broker netral/net buy", "broker summary 1D belum tersedia"),
                match("Break konsolidasi volume 1,5×", m.last.close > m.minorResistance && m.rvol1 >= 1.5, "close ${rupiah(m.last.close)}; RVOL ${ratio(m.rvol1)}")
            )
        }
        StrategyType.BREAKOUT_RETEST_CONFIRMATION -> {
            val breakout = m.breakoutIndex?.let { m.candles[it] }
            val retest = m.retestIndex?.let { m.candles[it] }
            val resistance = m.breakoutResistance
            listOf(
                match("Breakout valid terjadi 1–7 sesi lalu", breakout != null, m.breakoutIndex?.let { "${m.candles.lastIndex - it} sesi lalu" } ?: "tidak ditemukan"),
                match("Breakout menutup di atas resistance", breakout != null && resistance != null && breakout.close > resistance, if (breakout != null && resistance != null) "${rupiah(breakout.close)} > ${rupiah(resistance)}" else "tidak tersedia"),
                match("Volume breakout minimal 1,5×", breakout != null && breakout.volume >= m.medVol60 * 1.5, breakout?.let { ratio(it.volume / m.medVol60) } ?: "tidak tersedia"),
                match("Retest menyentuh zona ±3%", retest != null && resistance != null && retest.low in resistance * .97..resistance * 1.03, retest?.let { rupiah(it.low) } ?: "tidak ditemukan"),
                match("Retest bertahan di atas resistance", retest != null && resistance != null && retest.close >= resistance * .98, retest?.let { rupiah(it.close) } ?: "tidak ditemukan"),
                match("Volume retest maksimal 70% breakout", retest != null && breakout != null && retest.volume <= breakout.volume * .70, if (retest != null && breakout != null) ratio(retest.volume.toDouble() / breakout.volume.coerceAtLeast(1)) else "tidak tersedia"),
                match("Harga merebut high retest", retest != null && m.last.close > retest.high, retest?.let { "close ${rupiah(m.last.close)} > ${rupiah(it.high)}" } ?: "tidak tersedia"),
                match("Trigger ditutup dekat high", clv(m.last) >= .5, dec(clv(m.last))),
                match("OBV/A-D tetap menguat", m.obvSlope >= 0 || m.adlSlope >= 0, "OBV ${signed(m.obvSlope)}, A/D ${signed(m.adlSlope)}"),
                if (b.periodNetBuy.containsKey(1)) match("Broker tetap netral/net buy", b.periodNetBuy.getValue(1) >= 0, compact(b.periodNetBuy.getValue(1)))
                else unavailable("Broker tetap netral/net buy", "broker summary 1D belum tersedia")
            )
        }
    }

    private fun candidate(
        series: MarketSeries,
        m: Metrics,
        broker: BrokerAnalysis,
        strategy: StrategyType,
        criteria: List<StrategyCriterion>,
        score: Double,
        passed: Boolean,
        limitations: List<String>,
        calculationComplete: Boolean,
        operationalGatesComplete: Boolean,
        potentiallyEligible: Boolean
    ): Candidate {
        val plan = structuralPlan(strategy, m)
        val matched = criteria.count { it.state == CriterionState.MATCH }
        val volumeScore = ((m.rvol5.coerceAtMost(4.0) / 4 * 45) + (m.rtv5.coerceAtMost(4.0) / 4 * 45) + if (m.upDownRatio >= 1.3) 10 else 0).coerceIn(0.0, 100.0)
        val anomaly = (50 + m.volumeZ * 12 + if (m.clv10 > .15) 12 else 0).coerceIn(0.0, 100.0)
        val chasing = (max(0.0, m.return20 - 8) * 3 + max(0.0, (m.last.close / m.ema20 - 1) * 100 - 5) * 4).coerceIn(0.0, 100.0)
        val distribution = ((broker.sellerConcentration ?: 0.0) * 55 + if ((broker.netBuy ?: 0.0) < 0) 25 else 0).coerceIn(0.0, 100.0)
        val setup = when (strategy) {
            StrategyType.MARKUP_IGNITION, StrategyType.BREAKOUT_RETEST_CONFIRMATION -> SetupType.BREAKOUT
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> SetupType.MOMENTUM
            StrategyType.SHAKEOUT_SPRING_RECLAIM -> SetupType.EARLY_ACCUMULATION
            else -> SetupType.WATCHLIST
        }
        val why = buildList {
            add("Memenuhi $matched/10 filter tambahan; ambang strategi ${strategy.minimumMatches}/10 dan hard gate wajib.")
            criteria.filter { it.state == CriterionState.MATCH }.take(6).forEach { add("${it.label}: ${it.evidence}") }
            if (limitations.isNotEmpty()) add("Keterbatasan data: ${limitations.distinct().joinToString("; ")}")
            add("Ranking ${dec(score)}/100 dihitung khusus untuk ${strategy.label}, bukan score lintas strategi.")
        }
        val indicators = indicators(strategy, m, broker)
        return Candidate(
            ticker = series.ticker,
            companyName = series.companyName,
            referenceDate = m.last.epochSeconds,
            referenceClose = m.last.close,
            setup = setup,
            scores = ScoreBreakdown(broker.score, volumeScore, anomaly, score, distribution, chasing, score, score, broker.available),
            tradePlan = plan,
            rvol5 = m.rvol5,
            rvol20 = m.rvol1,
            valueExpansion = m.rtv5,
            volumeZScore = m.volumeZ,
            why = why,
            brokerAnalysis = broker,
            technical = TechnicalSnapshot(m.ema20, m.ema50, m.rsi14, m.atr14, m.dayReturn, m.return20, m.support, m.resistance, m.clv10, m.obvSlope, m.adlSlope, m.atrCompression, m.bbw),
            strategy = strategy,
            strategyScore = score,
            matchedCriteria = matched,
            totalCriteria = 10,
            passed = passed,
            criteria = criteria,
            indicators = indicators,
            dataLimitations = limitations.distinct(),
            calculationComplete = calculationComplete,
            operationalGatesComplete = operationalGatesComplete,
            potentiallyEligible = potentiallyEligible
        )
    }

    private fun indicators(s: StrategyType, m: Metrics, b: BrokerAnalysis): List<StrategyIndicator> {
        fun i(label: String, value: String, good: Boolean? = null) = StrategyIndicator(label, value, when (good) { true -> "GOOD"; false -> "BAD"; null -> "NEUTRAL" })
        return when (s) {
            StrategyType.QUIET_ACCUMULATION -> listOf(i("RTV 5/60", ratio(m.rtv5), m.rtv5 >= 1.3), i("RVOL 5/60", ratio(m.rvol5), m.rvol5 in 1.2..3.0), i("CLV10", dec(m.clv10), m.clv10 > .15), i("OBV slope", signed(m.obvSlope), m.obvSlope > 0))
            StrategyType.ABSORPTION_AT_SUPPORT -> { val flow=m.absorption?.let { brokerNetOn(b,it) };listOf(i("Support", rupiah(m.support)), i("Uji support", supportTouches(m).toString(), supportTouches(m) >= 2), i("CLV10", dec(m.clv10), m.clv10 > 0), i("Broker saat uji", flow?.let(::compact) ?: "N/A", (flow ?: 0.0) > 0)) }
            StrategyType.BROKER_ACCUMULATION_PERSISTENCE -> listOf(i("Broker 1D", compact(b.periodNetBuy[1] ?: 0.0)), i("Broker 10D", compact(b.periodNetBuy[10] ?: 0.0)), i("Buyer concentration", pct((b.buyerConcentration ?: 0.0) * 100)), i("Avg top buyer", b.topBuyers.firstOrNull()?.averagePrice?.let(::rupiah) ?: "N/A"))
            StrategyType.FREQUENCY_CREEP -> listOf(i("RFREQ 5/60", if (m.frequencyAvailable) ratio(m.rfreq5) else "N/A", m.frequencyAvailable && m.rfreq5 >= 1.5), i("Avg trade size 5D", if (m.frequencyAvailable) compact(m.avgTradeSize5) else "N/A"), i("RTV 5/60", ratio(m.rtv5), m.rtv5 >= 1.3), i("Frekuensi naik", "${m.frequencyRisingSessions} sesi", m.frequencyRisingSessions >= 3))
            StrategyType.BROKER_PRICE_DIVERGENCE -> listOf(i("Return 20D", pct(m.return20), m.return20 in -10.0..5.0), i("Broker 10D", compact(b.periodNetBuy[10] ?: 0.0), (b.periodNetBuy[10] ?: 0.0) > 0), i("Persistence", b.topBuyerPersistenceDays?.let { "$it/10" } ?: "N/A", (b.topBuyerPersistenceDays ?: 0) >= 6), i("A/D slope", signed(m.adlSlope), m.adlSlope > 0))
            StrategyType.VOLATILITY_COMPRESSION -> listOf(i("ATR10/60", ratio(m.atrCompression), m.atrCompression <= .8), i("BBW percentile", pct(m.bbwPercentile * 100), m.bbwPercentile <= .2), i("Base", "${m.baseDays}D", m.baseDays >= 10), i("Jarak resistance", pct((m.resistance / m.last.close - 1) * 100), (m.resistance / m.last.close - 1) <= .05))
            StrategyType.SHAKEOUT_SPRING_RECLAIM -> { val flow=m.spring?.let { brokerNetOn(b,it) };listOf(i("Support", rupiah(m.support)), i("Spring", m.spring?.let { rupiah(it.low) } ?: "Tidak ada", m.spring != null), i("Volume spring", m.spring?.let { ratio(it.volume / m.medVol60) } ?: "N/A"), i("Broker saat spring", flow?.let(::compact) ?: "N/A", (flow ?: 0.0) > 0)) }
            StrategyType.MARKUP_IGNITION -> listOf(i("RVOL 1/60", ratio(m.rvol1), m.rvol1 >= 3), i("RTV 1/60", ratio(m.rtv1), m.rtv1 >= 3), i("RFREQ 1/60", if (m.frequencyAvailable) ratio(m.rfreq1) else "N/A", m.frequencyAvailable && m.rfreq1 >= 2), i("Trigger CLV", dec(clv(m.last)), clv(m.last) >= .5))
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> listOf(i("Markup pertama", m.markupIndex?.let { "${m.candles.lastIndex - it} sesi lalu" } ?: "Tidak ada", m.markupIndex != null), i("Resistance lama", rupiah(m.oldResistance)), i("RVOL trigger", ratio(m.rvol1), m.rvol1 >= 1.5), i("OBV slope", signed(m.obvSlope), m.obvSlope >= 0))
            StrategyType.BREAKOUT_RETEST_CONFIRMATION -> listOf(i("Breakout", m.breakoutIndex?.let { "${m.candles.lastIndex - it} sesi lalu" } ?: "Tidak ada", m.breakoutIndex != null), i("Resistance breakout", m.breakoutResistance?.let(::rupiah) ?: "N/A"), i("Retest", m.retestIndex?.let { "${m.candles.lastIndex - it} sesi lalu" } ?: "Tidak ada", m.retestIndex != null), i("Trigger CLV", dec(clv(m.last)), clv(m.last) >= .5))
        }
    }

    private fun structuralPlan(s: StrategyType, m: Metrics): TradePlan {
        val trigger = when (s) {
            StrategyType.ABSORPTION_AT_SUPPORT -> m.absorption?.high ?: m.minorResistance
            StrategyType.SHAKEOUT_SPRING_RECLAIM -> m.spring?.high ?: m.minorResistance
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> m.minorResistance
            StrategyType.BREAKOUT_RETEST_CONFIRMATION -> m.retestIndex?.let { m.candles[it].high } ?: m.minorResistance
            else -> m.resistance
        }.coerceAtLeast(m.last.close * .98)
        val entryLow = IDXTickSizeEngine.roundUpToValidTick(trigger)
        val entryHigh = entryLow + IDXTickSizeEngine.getTickSize(entryLow.toDouble()) * 2
        val structuralLow = when (s) {
            StrategyType.ABSORPTION_AT_SUPPORT, StrategyType.SHAKEOUT_SPRING_RECLAIM -> m.support
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> m.candles.takeLast(10).minOf { it.low }
            StrategyType.BREAKOUT_RETEST_CONFIRMATION -> m.retestIndex?.let { m.candles[it].low } ?: m.candles.takeLast(10).minOf { it.low }
            else -> min(m.support, m.candles.takeLast(10).minOf { it.low })
        }
        val stop = IDXTickSizeEngine.roundDownToValidTick((structuralLow - m.atr14 * .25).coerceAtLeast(entryLow * .85))
        val risk = (entryLow - stop).toDouble().coerceAtLeast(IDXTickSizeEngine.getTickSize(entryLow.toDouble()).toDouble())
        val measuredMove = (m.resistance + (m.resistance - m.support).coerceAtLeast(risk * 1.5))
        val target = max(entryLow + risk * 2, measuredMove)
        val tp = IDXTickSizeEngine.roundDownToValidTick(target)
        val rr = (tp - entryLow).toDouble() / risk
        return TradePlan(entryLow, entryHigh, tp, stop, rr, structuralLow)
    }

    private fun rankingScore(s: StrategyType, m: Metrics, b: BrokerAnalysis, matched: Int, passed: Boolean): Double {
        val evidence = matched * 8.5
        val strength = when (s) {
            StrategyType.QUIET_ACCUMULATION -> min(8.0, m.rtv5 * 2) + min(7.0, m.rvol5 * 2) + max(0.0, m.clv10 * 6)
            StrategyType.ABSORPTION_AT_SUPPORT -> supportTouches(m).coerceAtMost(4) * 2.0 + max(0.0, m.clv10 * 5) + if ((b.periodNetBuy[1] ?: 0.0) > 0) 5 else 0
            StrategyType.BROKER_ACCUMULATION_PERSISTENCE -> (b.score ?: 0.0) * .15
            StrategyType.FREQUENCY_CREEP -> min(10.0, m.rfreq5 * 4) + min(5.0, m.rtv5 * 2)
            StrategyType.BROKER_PRICE_DIVERGENCE -> min(8.0, (b.topBuyerPersistenceDays ?: 0) * 1.2) + max(0.0, m.adlSlope * 8)
            StrategyType.VOLATILITY_COMPRESSION -> max(0.0, (1 - m.atrCompression) * 15) + max(0.0, (.3 - m.bbwPercentile) * 20)
            StrategyType.SHAKEOUT_SPRING_RECLAIM -> if (m.spring != null) 12.0 else 0.0
            StrategyType.MARKUP_IGNITION -> min(8.0, m.rvol1) + min(8.0, m.rtv1)
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> if (m.markupIndex != null) 10.0 else 0.0
            StrategyType.BREAKOUT_RETEST_CONFIRMATION -> if (m.breakoutIndex != null && m.retestIndex != null) 12.0 else 0.0
        }
        return (evidence + strength + if (passed) 2 else 0).coerceIn(0.0, 100.0)
    }

    private fun metrics(c: List<Candle>): Metrics {
        val last = c.last(); val prior = c.dropLast(1); val p60 = prior.takeLast(60); val p20 = prior.takeLast(20); val p5 = prior.takeLast(5)
        val medVol60 = median(p60.map { it.volume.toDouble() }).coerceAtLeast(1.0)
        val medVol20 = median(p20.map { it.volume.toDouble() }).coerceAtLeast(1.0)
        val medVol5 = median(p5.map { it.volume.toDouble() }).coerceAtLeast(1.0)
        val medValue60 = median(p60.map { it.estimatedTradingValue }).coerceAtLeast(1.0)
        val medValue20 = median(p20.map { it.estimatedTradingValue }).coerceAtLeast(1.0)
        val medValue5 = median(p5.map { it.estimatedTradingValue }).coerceAtLeast(1.0)
        val freq60 = p60.mapNotNull { it.frequency?.takeIf { value -> value > 0 }?.toDouble() }
        val freq20 = p20.mapNotNull { it.frequency?.takeIf { value -> value > 0 }?.toDouble() }
        val freq5 = p5.mapNotNull { it.frequency?.takeIf { value -> value > 0 }?.toDouble() }
        val frequencyAvailable = freq60.size >= 55 && freq20.size == p20.size && freq5.size == p5.size && last.frequency != null
        val medFreq60 = median(freq60).coerceAtLeast(1.0)
        val medFreq20 = median(freq20).coerceAtLeast(1.0)
        val medFreq5 = median(freq5).coerceAtLeast(1.0)
        val recentFreq = c.takeLast(6).mapNotNull { it.frequency?.toDouble() }
        var frequencyRisingSessions = 0
        if (recentFreq.size >= 4) {
            for (index in recentFreq.lastIndex downTo 1) {
                if (recentFreq[index] > recentFreq[index - 1]) frequencyRisingSessions++ else break
            }
        }
        val resistance = p20.maxOf { it.high }; val support = p20.minOf { it.low }; val minor = prior.takeLast(10).maxOf { it.high }
        val lows = p20.chunked(5).mapNotNull { it.minOfOrNull(Candle::low) }
        val higherLows = lows.zipWithNext().count { (a, b) -> b > a }
        val atr10 = atr(c, 10); val atr14 = atr(c, 14); val atr60 = atr(c, 60).coerceAtLeast(.0001)
        val closes = c.map(Candle::close); val ema20 = ema(closes.takeLast(60), 20); val ema50 = ema(closes.takeLast(100), 50)
        val bbwHistory = (20 until c.size).map { idx -> bollingerBandwidth(c.subList(0, idx + 1).map(Candle::close).takeLast(20)) }
        val bbw = bbwHistory.lastOrNull() ?: 0.0
        val bbwPercentile = if (bbwHistory.isEmpty()) 1.0 else bbwHistory.takeLast(60).count { it <= bbw }.toDouble() / bbwHistory.takeLast(60).size
        val obv = mutableListOf(0.0); val adl = mutableListOf(0.0)
        c.zipWithNext().forEach { (prev, now) ->
            obv += obv.last() + when { now.close > prev.close -> now.volume.toDouble(); now.close < prev.close -> -now.volume.toDouble(); else -> 0.0 }
            adl += adl.last() + clv(now) * now.volume
        }
        val upVol = p20.filter { it.close >= it.open }.sumOf { it.volume.toDouble() }
        val downVol = p20.filter { it.close < it.open }.sumOf { it.volume.toDouble() }.coerceAtLeast(1.0)
        val down5 = p5.filter { it.close < it.open }.map { it.volume.toDouble() }
        val down20 = p20.filter { it.close < it.open }.map { it.volume.toDouble() }
        val impact5 = p5.map { abs(it.close / it.open - 1) / it.volume.coerceAtLeast(1) }.averageOrZero()
        val impact20 = p20.dropLast(5).map { abs(it.close / it.open - 1) / it.volume.coerceAtLeast(1) }.averageOrZero().coerceAtLeast(1e-12)
        val baseDays = baseLength(prior)
        val absorption = p20.filter { it.low <= support * 1.03 && it.volume >= medVol60 * 1.5 }.maxByOrNull { it.epochSeconds }
        val spring = p20.indices.mapNotNull { index ->
            if (index < 5) null else {
                val row = p20[index]
                val priorSupport = p20.subList(0, index).takeLast(15).minOf(Candle::low)
                row.takeIf { it.low in priorSupport * .95..priorSupport * .99 && it.close >= priorSupport }
            }
        }.maxByOrNull { it.epochSeconds }
        val markupIndex = c.indices.toList().dropLast(3).takeLast(20).lastOrNull { idx ->
            idx >= 20 && c[idx].close > c.subList(idx - 20, idx).maxOf(Candle::high) && c[idx].volume >= medVol60 * 3
        }
        val oldResistance = markupIndex?.let { idx -> c.subList((idx - 20).coerceAtLeast(0), idx).maxOf(Candle::high) } ?: resistance
        val breakoutIndex = ((c.lastIndex - 7).coerceAtLeast(20) until c.lastIndex).lastOrNull { idx ->
            val priorResistance = c.subList(idx - 20, idx).maxOf(Candle::high)
            c[idx].close > priorResistance && c[idx].volume >= medVol60 * 1.5 && clv(c[idx]) >= 0
        }
        val breakoutResistance = breakoutIndex?.let { idx -> c.subList(idx - 20, idx).maxOf(Candle::high) }
        val retestIndex = if (breakoutIndex != null && breakoutResistance != null && breakoutIndex + 1 < c.lastIndex) {
            (breakoutIndex + 1 until c.lastIndex).lastOrNull { idx ->
                c[idx].low in breakoutResistance * .97..breakoutResistance * 1.03 && c[idx].close >= breakoutResistance * .98
            }
        } else null
        return Metrics(
            candles = c, last = last, prior = prior,
            medVol60 = medVol60, medVol20 = medVol20, medVol5 = medVol5,
            medValue60 = medValue60, medValue20 = medValue20, medValue5 = medValue5,
            medFreq60 = medFreq60, medFreq20 = medFreq20, medFreq5 = medFreq5,
            rvol5 = medVol5 / medVol60, rvol1 = last.volume / medVol60,
            rtv5 = medValue5 / medValue60, rtv1 = last.estimatedTradingValue / medValue60,
            rfreq5 = medFreq5 / medFreq60, rfreq1 = (last.frequency?.toDouble() ?: 0.0) / medFreq60,
            avgTradeSize5 = medValue5 / medFreq5, avgTradeSize20 = medValue20 / medFreq20,
            frequencyRisingSessions = frequencyRisingSessions, frequencyAvailable = frequencyAvailable,
            return20 = (last.close / p20.first().close - 1) * 100,
            range20 = (p20.maxOf(Candle::high) - p20.minOf(Candle::low)) / p20.minOf(Candle::low).coerceAtLeast(1.0),
            support = support, resistance = resistance, minorResistance = minor,
            atr10 = atr10, atr14 = atr14, atr60 = atr60, atrCompression = atr10 / atr60,
            bbw = bbw, bbwPercentile = bbwPercentile,
            clv10 = c.takeLast(10).map(::clv).average(), obvSlope = slope(obv.takeLast(20)), adlSlope = slope(adl.takeLast(20)),
            upDownRatio = upVol / downVol, higherLows = higherLows, baseDays = baseDays,
            downVolumeRatio = median(down5) / median(down20).coerceAtLeast(1.0), priceImpactRatio = impact5 / impact20,
            ema20 = ema20, ema50 = ema50, rsi14 = rsi(closes, 14), dayReturn = (last.close / prior.last().close - 1) * 100,
            volumeZ = zScore(last.volume.toDouble(), p20.map { it.volume.toDouble() }),
            medianRange5 = median(p5.map(::candleRange)), medianRange20 = median(p20.map(::candleRange)),
            volumeContracting = p5.chunked(2).map { median(it.map { row -> row.volume.toDouble() }) }.zipWithNext().all { (a, b) -> b <= a },
            absorption = absorption, spring = spring, oldResistance = oldResistance, markupIndex = markupIndex,
            breakoutIndex = breakoutIndex, breakoutResistance = breakoutResistance, retestIndex = retestIndex
        )
    }

    private fun baseLength(c: List<Candle>): Int {
        var length = 0
        for (size in 5..min(30, c.size)) {
            val w = c.takeLast(size)
            val range = (w.maxOf(Candle::high) - w.minOf(Candle::low)) / w.minOf(Candle::low).coerceAtLeast(1.0)
            if (range <= .20) length = size else break
        }
        return length
    }

    private fun supportTouches(m: Metrics, band: Double = .035) = m.candles.takeLast(20).count { abs(it.low / m.support - 1) <= band }
    private fun resistanceTouches(m: Metrics, band: Double = .025) = m.candles.takeLast(20).count { abs(it.high / m.resistance - 1) <= band }
    private fun brokerNetOn(b: BrokerAnalysis, candle: Candle): Double? {
        val day = Instant.ofEpochSecond(candle.epochSeconds).atZone(ZoneId.of("Asia/Jakarta")).toLocalDate().toEpochDay()
        return b.dailyFlows.firstOrNull { it.epochDay == day }?.netBuy
    }
    private fun maxConsecutiveNetSell(days: List<BrokerDay>): Int {
        var current = 0
        var maximum = 0
        days.asReversed().forEach { day ->
            if (day.netBuy < 0) { current++; maximum = max(maximum, current) } else current = 0
        }
        return maximum
    }
    private fun accumulationScore(m: Metrics, b: BrokerAnalysis) = listOf(
        m.return20 in -10.0..15.0, m.range20 <= .20, m.rtv5 >= 1.3, m.rvol5 >= 1.2,
        m.obvSlope > 0, m.adlSlope > 0, m.clv10 > .15, m.upDownRatio >= 1.3,
        m.higherLows >= 2, (b.periodNetBuy[10] ?: b.netBuy ?: 0.0) > 0
    ).count { it }

    /**
     * An 8/10 or 9/10 score cannot rescue a setup that misses the evidence
     * defining its strategy. These indexes are deliberately stricter than the
     * generic score so a named strategy never passes as a different pattern.
     */
    private fun hardGatePassed(strategy: StrategyType, criteria: List<StrategyCriterion>): Boolean {
        val required = when (strategy) {
            StrategyType.QUIET_ACCUMULATION -> setOf(2, 3, 4)
            StrategyType.ABSORPTION_AT_SUPPORT -> setOf(0, 1, 3, 5, 9)
            StrategyType.BROKER_ACCUMULATION_PERSISTENCE -> setOf(0, 1, 2)
            StrategyType.FREQUENCY_CREEP -> setOf(0, 1, 2, 4)
            StrategyType.BROKER_PRICE_DIVERGENCE -> setOf(0, 2, 4, 5)
            StrategyType.VOLATILITY_COMPRESSION -> setOf(0, 1, 2)
            StrategyType.SHAKEOUT_SPRING_RECLAIM -> setOf(2, 4, 6, 8)
            StrategyType.MARKUP_IGNITION -> setOf(1, 2, 3, 4)
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> setOf(0, 2, 5, 9)
            StrategyType.BREAKOUT_RETEST_CONFIRMATION -> setOf(0, 1, 3, 4, 6)
        }
        return required.all { criteria.getOrNull(it)?.state == CriterionState.MATCH }
    }

    /** Missing broker evidence may still pass after enrichment; an explicit miss never can. */
    private fun hardGateCouldPass(strategy: StrategyType, criteria: List<StrategyCriterion>): Boolean {
        val required = when (strategy) {
            StrategyType.QUIET_ACCUMULATION -> setOf(2, 3, 4)
            StrategyType.ABSORPTION_AT_SUPPORT -> setOf(0, 1, 3, 5, 9)
            StrategyType.BROKER_ACCUMULATION_PERSISTENCE -> setOf(0, 1, 2)
            StrategyType.FREQUENCY_CREEP -> setOf(0, 1, 2, 4)
            StrategyType.BROKER_PRICE_DIVERGENCE -> setOf(0, 2, 4, 5)
            StrategyType.VOLATILITY_COMPRESSION -> setOf(0, 1, 2)
            StrategyType.SHAKEOUT_SPRING_RECLAIM -> setOf(2, 4, 6, 8)
            StrategyType.MARKUP_IGNITION -> setOf(1, 2, 3, 4)
            StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP -> setOf(0, 2, 5, 9)
            StrategyType.BREAKOUT_RETEST_CONFIRMATION -> setOf(0, 1, 3, 4, 6)
        }
        return required.all { criteria.getOrNull(it)?.state != CriterionState.MISS }
    }

    private fun adjustedCandle(candle: Candle): Candle {
        val adjusted = candle.adjustedClose?.takeIf { it > 0 } ?: return candle
        val factor = adjusted / candle.close
        return candle.copy(
            open = candle.open * factor,
            high = candle.high * factor,
            low = candle.low * factor,
            close = adjusted
        )
    }

    private fun hasSuspiciousPriceDiscontinuity(candles: List<Candle>): Boolean =
        candles.takeLast(60).zipWithNext().any { (previous, current) ->
            previous.close > 0 && abs(current.open / previous.close - 1.0) > .35
        }

    private fun match(label: String, value: Boolean, evidence: String) = StrategyCriterion(label, if (value) CriterionState.MATCH else CriterionState.MISS, evidence)
    private fun unavailable(label: String, evidence: String) = StrategyCriterion(label, CriterionState.DATA_UNAVAILABLE, evidence)
    private fun body(c: Candle) = abs(c.close - c.open)
    private fun lowerShadow(c: Candle) = min(c.open, c.close) - c.low
    private fun clv(c: Candle): Double = if (c.high <= c.low) 0.0 else ((c.close - c.low) - (c.high - c.close)) / (c.high - c.low)
    private fun candleRange(c: Candle) = (c.high - c.low) / c.close.coerceAtLeast(1.0)
    private fun median(v: List<Double>): Double { if (v.isEmpty()) return 0.0; val s = v.sorted(); val i = s.size / 2; return if (s.size % 2 == 0) (s[i - 1] + s[i]) / 2 else s[i] }
    private fun slope(v: List<Double>): Double { if (v.size < 2) return 0.0; val scale = v.map { abs(it) }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0; return (v.last() - v.first()) / scale }
    private fun ema(v: List<Double>, p: Int): Double { if (v.isEmpty()) return 0.0; val a = 2.0 / (p + 1); return v.drop(1).fold(v.first()) { acc, x -> x * a + acc * (1 - a) } }
    private fun atr(c: List<Candle>, p: Int): Double = c.zipWithNext { prev, now -> max(now.high - now.low, max(abs(now.high - prev.close), abs(now.low - prev.close))) }.takeLast(p).averageOrZero()
    private fun rsi(v: List<Double>, p: Int): Double { val d = v.zipWithNext { a, b -> b - a }.takeLast(p); val g = d.filter { it > 0 }.sum() / d.size.coerceAtLeast(1); val l = -d.filter { it < 0 }.sum() / d.size.coerceAtLeast(1); return if (l == 0.0) 100.0 else 100 - 100 / (1 + g / l) }
    private fun bollingerBandwidth(v: List<Double>): Double { if (v.isEmpty()) return 0.0; val mean = v.average(); val sd = sqrt(v.sumOf { (it - mean).pow(2) } / v.size); return if (mean == 0.0) 0.0 else 4 * sd / mean }
    private fun zScore(x: Double, v: List<Double>): Double { if (v.isEmpty()) return 0.0; val mean = v.average(); val sd = sqrt(v.sumOf { (it - mean).pow(2) } / v.size); return if (sd == 0.0) 0.0 else (x - mean) / sd }
    private fun List<Double>.averageOrZero() = if (isEmpty()) 0.0 else average()
    private fun ratio(v: Double) = "${"%.2f".format(v)}×"
    private fun dec(v: Double) = "%.2f".format(v)
    private fun pct(v: Double) = "%+.2f%%".format(v)
    private fun signed(v: Double) = "%+.3f".format(v)
    private fun rupiah(v: Double) = "Rp${IDXTickSizeEngine.roundToValidTick(v)}"
    private fun compact(v: Double): String { val a = abs(v); val x = when { a >= 1e12 -> "%.2fT".format(a / 1e12); a >= 1e9 -> "%.2fB".format(a / 1e9); a >= 1e6 -> "%.2fM".format(a / 1e6); else -> a.toLong().toString() }; return (if (v >= 0) "+" else "-") + x }
    private fun flow(b: BrokerAnalysis) = listOf(1, 3, 5, 10).joinToString(" / ") { compact(b.periodNetBuy[it] ?: 0.0) }
}
