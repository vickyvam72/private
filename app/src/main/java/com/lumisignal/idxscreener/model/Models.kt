package com.lumisignal.idxscreener.model

data class Candle(
    val epochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val adjustedClose: Double?,
    val volume: Long,
    /** Official traded value supplied by Stockbit; deterministic fallback supports old snapshots/tests. */
    val tradedValue: Double? = null,
    /** Number of transactions in the session. Null means the source did not carry it. */
    val frequency: Long? = null
) {
    val estimatedTradingValue: Double get() = tradedValue ?: close * volume
}

/** A single Stockbit running-trade print, ordered in Jakarta market time. */
data class TradeTick(
    val epochSeconds: Long,
    val price: Double
)

data class MarketSeries(
    val ticker: String,
    val companyName: String,
    val currency: String,
    val candles: List<Candle>,
    val lastUpdated: Long = System.currentTimeMillis()
)

/** Read-only Stockbit market metadata. Missing values stay null instead of being guessed. */
data class StockbitMarketContext(
    val ticker: String,
    val lastPrice: Double? = null,
    val bestBid: Double? = null,
    val bestOffer: Double? = null,
    val spreadPercent: Double? = null,
    val specialNotations: List<String> = emptyList(),
    val uma: Boolean? = null,
    val corporateActions: List<String> = emptyList(),
    val shareholderSummary: List<String> = emptyList(),
    val tradeBookSummary: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Number of valid IDX price steps between best bid and best offer. */
    val spreadTicks: Int? = null,
    /** Null means Stockbit did not publish a reliable current corporate-action status. */
    val blockingCorporateAction: Boolean? = null,
    /** Current tradability flag from the Stockbit orderbook, when supplied. */
    val tradable: Boolean? = null,
    val marketStatus: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)

enum class StockbitAccountTier {
    PRO,
    NON_PRO,
    UNKNOWN;

    val strategiesUnlocked: Boolean get() = this == PRO
}

data class StockbitCapabilitySnapshot(
    val marketData: String = "Unknown",
    val brokerSummary: String = "Unknown",
    val runningTrade: String = "Unknown",
    val accountTier: StockbitAccountTier = StockbitAccountTier.UNKNOWN,
    val accountMessage: String = "Status Stockbit PRO belum diperiksa.",
    val checkedAt: Long = System.currentTimeMillis()
)

data class PortfolioHolding(
    val ticker: String,
    val name: String? = null,
    val lots: Double? = null,
    val shares: Double? = null,
    val averagePrice: Double? = null,
    val lastPrice: Double? = null,
    val marketValue: Double? = null,
    val unrealizedProfitLoss: Double? = null,
    val unrealizedPercent: Double? = null
)

data class PortfolioOrder(
    val id: String,
    val ticker: String? = null,
    val side: String? = null,
    val price: Double? = null,
    val lots: Double? = null,
    val filledLots: Double? = null,
    val status: String? = null,
    val timestamp: String? = null
)

data class PortfolioTransaction(
    val id: String,
    val ticker: String? = null,
    val side: String? = null,
    val price: Double? = null,
    val lots: Double? = null,
    val amount: Double? = null,
    val realizedProfitLoss: Double? = null,
    val timestamp: String? = null
)

/**
 * Actual Stockbit Sekuritas account snapshot. Account identifiers are deliberately
 * not retained. Every nullable number means the upstream field was absent or was
 * not mapped safely; it must never be rendered as zero.
 */
data class PortfolioSnapshot(
    val totalEquity: Double? = null,
    val cashOnHand: Double? = null,
    val buyingPower: Double? = null,
    val withdrawableBalance: Double? = null,
    val settlementT0: Double? = null,
    val settlementT1: Double? = null,
    val settlementT2: Double? = null,
    val realizedProfitLoss: Double? = null,
    val tradingPerformancePercent: Double? = null,
    val holdings: List<PortfolioHolding> = emptyList(),
    val openOrders: List<PortfolioOrder> = emptyList(),
    val transactions: List<PortfolioTransaction> = emptyList(),
    val warnings: List<String> = emptyList(),
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val ownedTickers: Set<String> get() = holdings.map { it.ticker.removeSuffix(".JK").uppercase() }.toSet()
}

data class UniverseTicker(
    val ticker: String,
    val companyName: String,
    val averageVolume10d: Double?,
    val estimatedAverageValue10d: Double?,
    val lastPrice: Double? = null
)

data class UniverseSnapshot(
    val tickers: List<UniverseTicker>,
    val totalCount: Int,
    val source: String,
    val fetchedAt: Long = System.currentTimeMillis(),
    val fromCache: Boolean = false
)

data class BrokerDay(
    val epochDay: Long,
    val buyValue: Double,
    val sellValue: Double,
    val topBuyers: Map<String, Double> = emptyMap(),
    val topSellers: Map<String, Double> = emptyMap()
) { val netBuy: Double get() = buyValue - sellValue }

data class BrokerFlowItem(
    val code: String,
    val netValue: Double,
    val averagePrice: Double? = null,
    val investorClass: String? = null
)

enum class BrokerFailureKind {
    AUTH,
    ENTITLEMENT,
    RATE_LIMIT,
    TIMEOUT,
    EMPTY,
    INCOMPLETE,
    NETWORK,
    UNKNOWN;

    val label: String get() = when (this) {
        AUTH -> "sesi/login"
        ENTITLEMENT -> "akses PRO"
        RATE_LIMIT -> "rate limit"
        TIMEOUT -> "timeout"
        EMPTY -> "respons kosong"
        INCOMPLETE -> "sesi kurang"
        NETWORK -> "jaringan"
        UNKNOWN -> "lainnya"
    }
}

data class BrokerAnalysis(
    val available: Boolean,
    val score: Double? = null,
    val netBuy: Double? = null,
    val persistenceDays: Int? = null,
    val windowDays: Int? = null,
    val buyerConcentration: Double? = null,
    val sellerConcentration: Double? = null,
    val explanation: List<String> = emptyList(),
    val periodNetBuy: Map<Int, Double> = emptyMap(),
    val topBuyers: List<BrokerFlowItem> = emptyList(),
    val topSellers: List<BrokerFlowItem> = emptyList(),
    val periodTopBuyers: Map<Int, List<BrokerFlowItem>> = emptyMap(),
    val periodTopSellers: Map<Int, List<BrokerFlowItem>> = emptyMap(),
    val foreignPeriodNetBuy: Map<Int, Double> = emptyMap(),
    val foreignNetBuy: Double? = null,
    val foreignDataAvailable: Boolean = false,
    val flowInterpretation: String? = null,
    /** Up to twenty single-session snapshots, newest first; the newest ten drive persistence. */
    val dailyFlows: List<BrokerDay> = emptyList(),
    /** Net-positive days for the aggregate top-three buyer group in the newest ten sessions. */
    val topBuyerPersistenceDays: Int? = null,
    /** Net-buy days that coincided with a non-positive daily price return. */
    val correctionNetBuyDays: Int? = null,
    val failureKind: BrokerFailureKind? = null
)

enum class StrategyType(
    val number: Int,
    val label: String,
    val phase: String,
    val description: String,
    val minimumMatches: Int
) {
    QUIET_ACCUMULATION(1, "Quiet Accumulation", "Early accumulation", "Jejak akumulasi muncul saat harga masih tidur", 8),
    ABSORPTION_AT_SUPPORT(2, "Absorption at Support", "Early accumulation", "Sell pressure besar gagal merobohkan support", 8),
    BROKER_ACCUMULATION_PERSISTENCE(3, "Broker Accumulation Persistence", "Early accumulation", "Broker dominan mengoleksi saham lintas sesi", 8),
    FREQUENCY_CREEP(4, "Frequency Creep", "Early activity", "Transaksi kecil makin ramai sebelum harga bangun", 8),
    BROKER_PRICE_DIVERGENCE(5, "Broker–Price Divergence", "Early accumulation", "Broker menyerap saat harga belum ikut bergerak", 8),
    VOLATILITY_COMPRESSION(6, "Volatility Compression", "Breakout preparation", "Volatilitas mengering, tenaga breakout terkunci", 8),
    SHAKEOUT_SPRING_RECLAIM(7, "Shakeout–Spring Reclaim", "Reversal", "Sapuan support gagal lalu harga direbut buyer", 8),
    MARKUP_IGNITION(8, "Markup Ignition", "Breakout", "Ledakan demand menyalakan fase markup", 9),
    REACCUMULATION_AFTER_FIRST_MARKUP(9, "Reaccumulation After First Markup", "Continuation", "Pullback sehat menyiapkan dorongan kedua", 9),
    BREAKOUT_RETEST_CONFIRMATION(10, "Breakout Retest Confirmation", "Breakout confirmation", "Retest bertahan lalu buyer mengambil alih", 9);

    companion object {
        fun fromStored(value: String?): StrategyType {
            val legacy = when (value) {
                "FREE_FLOAT_TURNOVER_CREEP", "Free-Float Turnover Creep" -> BROKER_PRICE_DIVERGENCE
                "SUPPLY_VACUUM_BREAKOUT", "Supply Vacuum Breakout" -> BREAKOUT_RETEST_CONFIRMATION
                else -> null
            }
            return legacy ?: entries.firstOrNull {
                it.name == value || it.label.equals(value, ignoreCase = true)
            } ?: QUIET_ACCUMULATION
        }
    }
}

enum class CriterionState { MATCH, MISS, DATA_UNAVAILABLE }

data class StrategyCriterion(
    val label: String,
    val state: CriterionState,
    val evidence: String
)

data class StrategyIndicator(
    val label: String,
    val value: String,
    val tone: String = "NEUTRAL"
)

enum class SetupType { EARLY_ACCUMULATION, BREAKOUT, MOMENTUM, WATCHLIST, DISTRIBUTION_RISK }
enum class SignalStatus { WAITING_ENTRY, HOLDING, TAKE_PROFIT, STOP_LOSS, EXPIRED, AMBIGUOUS, SUPERSEDED }

data class ScoreBreakdown(
    val broker: Double?,
    val volume: Double,
    val anomaly: Double,
    val technical: Double,
    val distributionRisk: Double,
    val chasingRisk: Double,
    val rawFinal: Double,
    val adjustedFinal: Double,
    val brokerAvailable: Boolean
)

data class TradePlan(
    val entryLow: Int,
    val entryHigh: Int,
    val takeProfit: Int,
    val stopLoss: Int,
    val riskReward: Double,
    val accumulationArea: Double
)

data class TechnicalSnapshot(
    val ema20: Double = 0.0,
    val ema50: Double = 0.0,
    val rsi14: Double = 50.0,
    val atr14: Double = 0.0,
    val dayReturn: Double = 0.0,
    val twentyDayReturn: Double = 0.0,
    val support: Double = 0.0,
    val resistance: Double = 0.0,
    val clv10: Double = 0.0,
    val obvSlope: Double = 0.0,
    val adlSlope: Double = 0.0,
    val atrCompression: Double = 1.0,
    val bollingerBandwidth: Double = 0.0
)

data class Candidate(
    val ticker: String,
    val companyName: String,
    val referenceDate: Long,
    val referenceClose: Double,
    val setup: SetupType,
    val scores: ScoreBreakdown,
    val tradePlan: TradePlan,
    val rvol5: Double,
    val rvol20: Double,
    val valueExpansion: Double,
    val volumeZScore: Double,
    val why: List<String>,
    val brokerAnalysis: BrokerAnalysis = BrokerAnalysis(false),
    val technical: TechnicalSnapshot = TechnicalSnapshot(),
    val strategy: StrategyType = StrategyType.QUIET_ACCUMULATION,
    val strategyScore: Double = scores.adjustedFinal,
    val matchedCriteria: Int = 0,
    val totalCriteria: Int = 10,
    val passed: Boolean = false,
    val criteria: List<StrategyCriterion> = emptyList(),
    val indicators: List<StrategyIndicator> = emptyList(),
    val dataLimitations: List<String> = emptyList(),
    /** True only when every input that belongs to this strategy was available. */
    val calculationComplete: Boolean = true,
    /** False when a Stockbit operational gate such as UMA/spread/corporate action was absent. */
    val operationalGatesComplete: Boolean = true,
    /** Conservative upper-bound eligibility used before expensive broker enrichment. */
    val potentiallyEligible: Boolean = false
)

data class ScreeningWeights(
    val broker: Double = 25.0,
    val volume: Double = 25.0,
    val anomaly: Double = 25.0,
    val technical: Double = 25.0
) {
    val total: Double get() = broker + volume + anomaly + technical
    fun isValid() = kotlin.math.abs(total - 100.0) < 0.001 && listOf(broker, volume, anomaly, technical).all { it >= 0.0 }

    companion object {
        fun forRanking(dimension: String): ScreeningWeights? = when (dimension.uppercase()) {
            "OVERALL" -> ScreeningWeights()
            "BROKER" -> ScreeningWeights(broker = 100.0, volume = 0.0, anomaly = 0.0, technical = 0.0)
            "VOLUME" -> ScreeningWeights(broker = 0.0, volume = 100.0, anomaly = 0.0, technical = 0.0)
            "ANOMALY" -> ScreeningWeights(broker = 0.0, volume = 0.0, anomaly = 100.0, technical = 0.0)
            "TECHNICAL" -> ScreeningWeights(broker = 0.0, volume = 0.0, anomaly = 0.0, technical = 100.0)
            else -> null
        }
    }
}

sealed interface DataResult<out T> {
    data class Success<T>(val value: T) : DataResult<T>
    data class Error(val userMessage: String, val cause: Throwable? = null) : DataResult<Nothing>
}
