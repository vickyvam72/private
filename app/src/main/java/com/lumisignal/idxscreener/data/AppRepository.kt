package com.lumisignal.idxscreener.data

import android.content.Context
import androidx.room.withTransaction
import com.lumisignal.idxscreener.engine.*
import com.lumisignal.idxscreener.model.*
import com.lumisignal.idxscreener.network.*
import com.lumisignal.idxscreener.security.SecureStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class AuditCycleReport(
    val summary: String,
    val scannedSignals: Int,
    val uniqueTickers: Int,
    val updated: Int,
    val failures: Int,
    val completedAt: Long = System.currentTimeMillis()
)

class AppRepository(context: Context) {
    val db = AppDatabase.get(context)
    val idxUniverse = IdxUniverseRepository()
    val telegram = TelegramRepository()
    val secrets = SecureStore(context)
    private val cacheStore = object : BrokerWindowStore {
        override suspend fun read(key: String): String? =
            db.cacheDao().broker(key, System.currentTimeMillis())?.payload
        override suspend fun write(key: String, ticker: String, payload: String, ttlMs: Long) {
            val now = System.currentTimeMillis()
            db.cacheDao().upsertBroker(BrokerCacheEntity(key, ticker, if (ticker == "IDX") "IDX_DAILY" else "BROKER_WINDOW", payload, now, now + ttlMs))
        }
    }
    val stockbit = StockbitRepository(secrets, windowStore = cacheStore)
    /** Market-wide daily OHLCV + foreign flow from IDX (one request per session for all stocks). */
    private val appContext = context.applicationContext
    val idxDaily = IdxStockSummaryRepository(cacheStore) { IdxWebTransport(appContext) }

    val signals: Flow<List<SignalEntity>> = db.signalDao().observeAll()
    val latestScreening: Flow<List<ScreeningResultEntity>> = db.screeningDao().observeLatest()
    val latestScreeningRun: Flow<ScreeningRunEntity?> = db.screeningRunDao().observeLatest()
    val activity: Flow<List<ActivityLogEntity>> = db.activityDao().observe()
    val performance: Flow<Performance> = signals.combine(kotlinx.coroutines.flow.flowOf(Unit)) { rows, _ ->
        PerformanceEngine.calculate(rows.map { it.status to it.gainLoss })
    }

    suspend fun log(event: String, detail: String? = null, level: String = "INFO") {
        db.activityDao().insert(ActivityLogEntity(UUID.randomUUID().toString(), System.currentTimeMillis(), level, event, detail?.take(500)))
    }

    suspend fun setting(key: String, default: String): String = db.settingsDao().value(key) ?: default
    suspend fun putSetting(key: String, value: String) = db.settingsDao().put(SettingEntity(key, value))

    /**
     * Successful analyses are cached per reference session. Failures are never persisted:
     * a transient 429/timeout used to be replayed from cache and inflate the failure count.
     * Empty answers are not cached either: Stockbit uses them as a silent throttle.
     */
    private fun brokerCacheTtl(analysis: BrokerAnalysis): Long = when {
        analysis.available -> 7L * 24 * 60 * 60_000
        else -> 0L
    }

    private suspend fun storeBrokerAnalysis(key: String, ticker: String, period: String, analysis: BrokerAnalysis) {
        val ttl = brokerCacheTtl(analysis)
        if (ttl <= 0L) return
        val now = System.currentTimeMillis()
        db.cacheDao().upsertBroker(BrokerCacheEntity(key, ticker, period, CandidateJson.encodeBrokerAnalysis(analysis).toString(), now, now + ttl))
    }

    /** Removes expired cache rows so the broker window cache cannot grow without bound. */
    suspend fun purgeExpiredCache() = runCatching { db.cacheDao().purgeExpiredBroker(System.currentTimeMillis()) }

    suspend fun brokerAnalysis(
        ticker: String,
        referenceEpochSeconds: Long,
        dailySessions: Int = 20,
        knownSessionEpochSeconds: List<Long> = emptyList()
    ): BrokerAnalysis {
        val requestedSessions = dailySessions.coerceIn(10, 20)
        val referenceDate = Instant.ofEpochSecond(referenceEpochSeconds).atZone(JAKARTA).toLocalDate()
        val key = "${ticker.uppercase()}:$referenceDate:FLOW_V12_DAILY_${requestedSessions}D"
        db.cacheDao().broker(key, System.currentTimeMillis())?.let { cached ->
            runCatching { CandidateJson.decodeBrokerAnalysis(JSONObject(cached.payload)) }.getOrNull()?.let { return it }
        }
        val analysis = stockbit.brokerAnalysis(ticker, referenceEpochSeconds, requestedSessions, knownSessionEpochSeconds)
        storeBrokerAnalysis(key, ticker, "EVENT_FLOW_DAILY_${requestedSessions}D_TOP3_PERSISTENCE_10D", analysis)
        return analysis
    }

    suspend fun brokerSeedAnalysis(ticker: String, sessionEpochSeconds: List<Long>): BrokerAnalysis {
        val dates = sessionEpochSeconds.sorted().takeLast(10)
        if (dates.size < 10) return BrokerAnalysis(false, explanation = listOf("Riwayat sesi untuk broker seed kurang dari 10 hari"), failureKind = BrokerFailureKind.INCOMPLETE)
        val referenceDate = Instant.ofEpochSecond(dates.last()).atZone(JAKARTA).toLocalDate()
        val key = "${ticker.uppercase()}:$referenceDate:FLOW_V12_SEED_10D"
        val now = System.currentTimeMillis()
        db.cacheDao().broker(key, now)?.let { cached ->
            runCatching { CandidateJson.decodeBrokerAnalysis(JSONObject(cached.payload)) }.getOrNull()?.let { return it }
        }
        val analysis = stockbit.brokerSeedAnalysis(ticker, dates.first(), dates.last())
        storeBrokerAnalysis(key, ticker, "BROKER_SEED_10D", analysis)
        return analysis
    }

    suspend fun brokerPeriodAnalysis(
        ticker: String,
        referenceEpochSeconds: Long,
        periods: Set<Int>,
        knownSessionEpochSeconds: List<Long>
    ): BrokerAnalysis {
        val normalizedPeriods = periods.filter { it in setOf(1, 3, 5, 10) }.toSortedSet()
        val referenceDate = Instant.ofEpochSecond(referenceEpochSeconds).atZone(JAKARTA).toLocalDate()
        val key = "${ticker.uppercase()}:$referenceDate:FLOW_V12_PERIOD_${normalizedPeriods.joinToString("-")}"
        val now = System.currentTimeMillis()
        db.cacheDao().broker(key, now)?.let { cached ->
            runCatching { CandidateJson.decodeBrokerAnalysis(JSONObject(cached.payload)) }.getOrNull()?.let { return it }
        }
        val analysis = stockbit.brokerPeriodAnalysis(ticker, referenceEpochSeconds, normalizedPeriods, knownSessionEpochSeconds)
        storeBrokerAnalysis(key, ticker, "BROKER_PERIOD_${normalizedPeriods.joinToString("_")}", analysis)
        return analysis
    }

    /**
     * Daily OHLCVF is stable for the active session because Stockbit's incomplete
     * current candle is excluded. A short disk cache makes repeat screening much
     * faster without reusing stale intraday audit prices.
     */
    suspend fun screeningSeries(ticker: String, companyName: String): DataResult<MarketSeries> {
        val normalized = ticker.uppercase()
        val bucket = StockbitSessionPolicy.completedDataBucket(java.time.ZonedDateTime.now(JAKARTA))
        val key = "$normalized:SERIES_V2_72:$bucket"
        val latestKey = "$normalized:SERIES_V2_72:LATEST"
        val now = System.currentTimeMillis()
        db.cacheDao().broker(key, now)?.let { cached ->
            runCatching { decodeSeries(JSONObject(cached.payload), companyName) }.getOrNull()?.let {
                return DataResult.Success(it)
            }
        }
        val previous = db.cacheDao().brokerAny(latestKey)?.let { cached ->
            runCatching { decodeSeries(JSONObject(cached.payload), companyName) }.getOrNull()
        }
        val result = if (previous != null && previous.candles.size >= 60) {
            stockbit.refreshSeries(ticker, companyName, previous, bars = 72)
        } else stockbit.fetchSeries(ticker, companyName, bars = 72)
        if (result is DataResult.Success) {
            val payload = encodeSeries(result.value).toString()
            val latestDate = Instant.ofEpochSecond(result.value.candles.last().epochSeconds).atZone(JAKARTA).toLocalDate()
            val retrySoon = bucket.dayOfWeek.value <= 5 && latestDate.isBefore(bucket)
            val sessionTtl = if (retrySoon) 15 * 60_000L else 6 * 60 * 60_000L
            db.cacheDao().upsertBroker(BrokerCacheEntity(key, normalized, "STOCKBIT_OHLCVF_72_SESSION", payload, now, now + sessionTtl))
            db.cacheDao().upsertBroker(BrokerCacheEntity(latestKey, normalized, "STOCKBIT_OHLCVF_72_INCREMENTAL", payload, now, now + 30L * 24 * 60 * 60_000))
        }
        return result
    }


    suspend fun saveScreening(batchId: String, candidates: List<Candidate>, checked: Int, passed: Int, coverageStatus: String) {
        val now = System.currentTimeMillis()
        val ranked = candidates.groupBy { it.strategy }.flatMap { (_, rows) ->
            rows.sortedWith(compareByDescending<Candidate> { it.passed }.thenByDescending { it.strategyScore }).take(5).mapIndexed { index, candidate -> index + 1 to candidate }
        }
        val screeningRows = ranked.map { (rank, c) ->
            ScreeningResultEntity("$batchId-${c.strategy.name}-${c.ticker}", batchId, now, c.referenceDate, rank, c.ticker, c.companyName,
                c.strategy.name, c.strategyScore, CandidateJson.encode(c).toString(),
                if (c.scores.brokerAvailable) "STOCKBIT_OHLCVF_BROKER" else "STOCKBIT_MARKET_NO_BROKER", checked, passed, coverageStatus)
        }
        db.withTransaction {
            db.screeningDao().insertAll(screeningRows)
            // Only broker-verified candidates become tracked signals. Provisional rows
            // (broker not yet verified) are shown in the screener but never tracked or sent.
            ranked.filter { (_, candidate) -> candidate.passed }.forEach { (_, candidate) -> upsertSignalSnapshot(candidate) }
        }
    }

    private suspend fun upsertSignalSnapshot(candidate: Candidate): SignalEntity {
        val uuid = candidate.signalUuid()
        val incoming = candidate.toEntity(uuid)
        val existing = db.signalDao().get(uuid)
        val stored = when {
            existing == null -> {
                db.signalDao().insertImported(listOf(incoming))
                incoming
            }
            !existing.telegramSent && existing.status == SignalStatus.WAITING_ENTRY -> {
                val refreshed = incoming.copy(
                    telegramSent = existing.telegramSent,
                    telegramMessageId = existing.telegramMessageId,
                    auditTelegramSent = false,
                    status = existing.status,
                    signalTimestamp = System.currentTimeMillis()
                )
                db.signalDao().update(refreshed)
                refreshed
            }
            else -> existing
        }
        db.signalDao().supersedeOlder(candidate.ticker, candidate.strategy.name, candidate.referenceDate, uuid)
        return stored
    }

    sealed interface SendOutcome {
        data class Sent(val messageId: String, val resent: Boolean) : SendOutcome
        data object AlreadySent : SendOutcome
        data class Failed(val message: String) : SendOutcome
    }

    suspend fun sendSignal(screeningId: String, forceResend: Boolean = false): SendOutcome {
        val row = db.screeningDao().get(screeningId) ?: return SendOutcome.Failed("Hasil screening tidak ditemukan.")
        val candidate = runCatching { CandidateJson.decode(JSONObject(row.candidateJson)) }.getOrElse { return SendOutcome.Failed("Snapshot screening rusak.") }
        if (!candidate.passed) return SendOutcome.Failed("Kandidat sementara: broker Stockbit belum terverifikasi, jadi tidak dikirim sebagai sinyal.")
        val uuid = candidate.signalUuid()
        val existing = db.signalDao().get(uuid)
        if (existing?.telegramSent == true && !forceResend) return SendOutcome.AlreadySent
        val signal = existing ?: candidate.toEntity(uuid)
        if (existing == null) db.signalDao().insert(signal)
        val token = secrets.get(TELEGRAM_TOKEN_KEY) ?: return SendOutcome.Failed("Telegram belum dikonfigurasi.")
        val chatId = secrets.get(TELEGRAM_CHAT_KEY) ?: return SendOutcome.Failed("Telegram belum dikonfigurasi.")
        return when (val sent = telegram.send(token, chatId, TelegramFormatter.signal(signal))) {
            is DataResult.Success -> {
                if (existing?.telegramSent != true) db.signalDao().markTelegramSent(uuid, sent.value.messageId)
                log(if (forceResend) "Signal ${signal.ticker} dikirim ulang ke Telegram" else "Signal ${signal.ticker} dikirim ke Telegram")
                SendOutcome.Sent(sent.value.messageId, forceResend)
            }
            is DataResult.Error -> SendOutcome.Failed(sent.userMessage)
        }
    }

    /** Add every valid candidate in the latest ten-strategy batch to local audit. */
    suspend fun trackLatestCandidates(strategy: StrategyType? = null): Int {
        val rows = db.screeningDao().latestOnce().filter { strategy == null || StrategyType.fromStored(it.setup) == strategy }
        val entities = rows.mapNotNull { row ->
            runCatching { CandidateJson.decode(JSONObject(row.candidateJson)) }.getOrNull()
        }
        var inserted = 0
        entities.forEach { candidate ->
            val existed = db.signalDao().get(candidate.signalUuid()) != null
            upsertSignalSnapshot(candidate)
            if (!existed) inserted++
        }
        if (inserted > 0) log("Background audit watchlist synchronized", "$inserted sinyal baru dari 10 strategi")
        return inserted
    }

    suspend fun testTelegram(token: String, chatId: String): DataResult<TelegramSendResult> {
        val resolvedToken = token.trim().ifBlank { secrets.get(TELEGRAM_TOKEN_KEY).orEmpty() }
        val resolvedChat = chatId.trim().ifBlank { secrets.get(TELEGRAM_CHAT_KEY).orEmpty() }
        val result = telegram.send(resolvedToken, resolvedChat, "✅ <b>Lumi Signal - IDX Screener</b>\n\nTelegram Connected ✓")
        if (result is DataResult.Success) {
            secrets.put(TELEGRAM_TOKEN_KEY, resolvedToken)
            secrets.put(TELEGRAM_CHAT_KEY, resolvedChat)
            log("Telegram connected")
        }
        return result
    }

    fun hasTelegramConfiguration(): Boolean =
        secrets.get(TELEGRAM_TOKEN_KEY) != null && secrets.get(TELEGRAM_CHAT_KEY) != null

    suspend fun validateStoredTelegram(): DataResult<Unit> {
        val token = secrets.get(TELEGRAM_TOKEN_KEY) ?: return DataResult.Error("Telegram belum dikonfigurasi.")
        val chat = secrets.get(TELEGRAM_CHAT_KEY) ?: return DataResult.Error("Telegram belum dikonfigurasi.")
        return telegram.validate(token, chat)
    }

    suspend fun logoutTelegram() {
        secrets.remove(TELEGRAM_TOKEN_KEY)
        secrets.remove(TELEGRAM_CHAT_KEY)
        log("Telegram credential dihapus")
    }

    suspend fun saveAndTestStockbit(token: String): DataResult<Unit> {
        val result = stockbit.saveManualRefreshToken(token)
        log("Stockbit connection test", if (result is DataResult.Error) result.userMessage else "Connected")
        return result
    }

    suspend fun acceptStockbitWebSession(session: CapturedStockbitSession): DataResult<Unit> {
        val stored=stockbit.storeCapturedSession(session)
        if(stored is DataResult.Error)return stored
        val result=stockbit.testConnection()
        log("Stockbit WebView session",if(result is DataResult.Success)"Connected (read-only)" else (result as DataResult.Error).userMessage)
        return result
    }

    suspend fun logoutStockbit(){stockbit.logout();log("Stockbit logout")}

    suspend fun auditAll(strategy: StrategyType? = null): AuditCycleReport {
        val rows = db.signalDao().auditable().filter { strategy == null || StrategyType.fromStored(it.setup) == strategy }
        if (rows.isEmpty()) return AuditCycleReport("Tidak ada signal WAITING ENTRY atau HOLDING untuk diaudit.", 0, 0, 0, 0)
        log("Audit started", "${rows.size} signal Top 5 • ${rows.map { it.ticker }.distinct().size} ticker")
        var updated = 0
        var failures = 0
        val dailyCache = mutableMapOf<String, DataResult<MarketSeries>>()
        val liveCache = mutableMapOf<String, DataResult<Candle>>()
        val tickCache = mutableMapOf<String, DataResult<List<TradeTick>>>()
        for (signal in rows) {
            // Historical candles are session-cached. The only request repeated
            // every minute is the current orderbook/session OHLC per ticker.
            val series = when (val r = dailyCache.getOrPut(signal.ticker) { screeningSeries(signal.ticker, signal.companyName) }) {
                is DataResult.Success -> r.value
                is DataResult.Error -> { log("Audit ${signal.ticker} gagal", r.userMessage, "ERROR"); failures++; continue }
            }
            val zone = ZoneId.of("Asia/Jakarta")
            fun day(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(zone).toLocalDate()
            val referenceDay = day(signal.referenceTradingDate)
            val today = java.time.LocalDate.now(zone)
            val completed = series.candles.filter { day(it.epochSeconds) > referenceDay }
            // Keep today's completed candle out of the first pass. Stockbit
            // running-trade prints can establish the real intraday order; a
            // daily OHLC bar alone cannot tell whether entry, TP, or SL came first.
            val historical = completed.filter { day(it.epochSeconds) < today }
            val auditStatus = if (signal.status == SignalStatus.HOLDING && signal.entryTriggeredPrice == null) SignalStatus.WAITING_ENTRY else signal.status
            val completedDecision = AuditEngine.evaluate(
                signal.entryLow.toDouble(), signal.entryHigh.toDouble(), signal.takeProfit.toDouble(), signal.stopLoss.toDouble(),
                auditStatus, historical, SIGNAL_EXPIRY_SESSIONS, signal.entryTriggeredPrice
            )
            var decision = completedDecision
            var auditSource = "STOCKBIT_DAILY"

            if (completedDecision.status !in setOf(SignalStatus.TAKE_PROFIT, SignalStatus.STOP_LOSS, SignalStatus.EXPIRED, SignalStatus.AMBIGUOUS)) {
                val recent = tickCache.getOrPut(signal.ticker) { stockbit.fetchRecentTrades(signal.ticker) }
                if (recent is DataResult.Success) {
                    val floor = maxOf(signal.lastAuditAt?.div(1_000L) ?: 0L, signal.referenceTradingDate)
                    val orderedTicks = recent.value.filter { it.epochSeconds > floor }
                    if (orderedTicks.isNotEmpty()) {
                        val tickDecision = AuditEngine.evaluateTicks(
                            signal.entryLow.toDouble(), signal.entryHigh.toDouble(), signal.takeProfit.toDouble(), signal.stopLoss.toDouble(),
                            completedDecision.status, orderedTicks, completedDecision.entryPrice ?: signal.entryTriggeredPrice,
                            completedDecision.entryEpoch ?: signal.entryTriggeredDate
                        )
                        if (tickDecision.status != completedDecision.status || tickDecision.entryPrice != completedDecision.entryPrice) {
                            decision = tickDecision
                            auditSource = "STOCKBIT_RUNNING_TRADE"
                        }
                    }
                }

                // Session high/low is the catch-up fail-safe when the recent tape
                // no longer includes an earlier touch. Ambiguous order stays honest.
                val intraday = liveCache.getOrPut(signal.ticker) { stockbit.fetchSessionCandle(signal.ticker) }
                val todayHistorical = completed.lastOrNull { day(it.epochSeconds) == today }
                val currentSession = todayHistorical ?: (intraday as? DataResult.Success)?.value
                if (decision == completedDecision && currentSession != null && day(currentSession.epochSeconds) > referenceDay) {
                    val candleDecision = AuditEngine.evaluateCompletedThenLive(
                        signal.entryLow.toDouble(), signal.entryHigh.toDouble(), signal.takeProfit.toDouble(), signal.stopLoss.toDouble(),
                        completedDecision.status, emptyList(), listOf(currentSession), SIGNAL_EXPIRY_SESSIONS,
                        completedDecision.entryPrice ?: signal.entryTriggeredPrice
                    )
                    if (candleDecision.status != completedDecision.status || candleDecision.entryPrice != completedDecision.entryPrice) {
                        decision = candleDecision
                        auditSource = "STOCKBIT_SESSION_OHLC"
                    }
                }
            }
            val auditedAt = System.currentTimeMillis()
            if (decision.status != signal.status || (signal.entryTriggeredPrice == null && decision.entryPrice != null)) {
                val changed = db.signalDao().transition(signal.uuid, signal.status, decision.status, decision.entryEpoch, decision.entryPrice, auditedAt, decision.exitEpoch, decision.exitPrice, decision.status.name, decision.returnPct)
                if (changed == 1) {
                    val detail = decision.entryPrice?.let { "Entry aktual Rp${it.toInt().formatIdr()} • $auditSource" }
                        ?: "Audit berbasis $auditSource"
                    db.auditDao().insert(AuditResultEntity("${signal.uuid}-${decision.status.name}", signal.uuid, auditedAt, signal.status, decision.status, decision.exitPrice, decision.returnPct, detail))
                    log("${signal.ticker} → ${decision.status.name.replace('_',' ')}")
                    updated++
                }
            }
            db.signalDao().markAudited(signal.uuid, auditedAt, auditSource)
        }
        val summary = "Audit selesai. $updated dari ${rows.size} signal berubah status${if (failures > 0) "; $failures gagal diperiksa" else ""}."
        return AuditCycleReport(summary, rows.size, rows.map { it.ticker }.distinct().size, updated, failures)
    }

    suspend fun exportJson(): String {
        val rows = db.signalDao().all()
        return JSONObject().put("format", "Lumi Signal Backup v1").put("exportedAt", System.currentTimeMillis()).put("signals", JSONArray().apply {
            rows.forEach { s -> put(JSONObject().put("uuid",s.uuid).put("ticker",s.ticker).put("companyName",s.companyName).put("setup",s.setup).put("signalDate",s.signalDate).put("signalTimestamp",s.signalTimestamp).put("referenceTradingDate",s.referenceTradingDate).put("referenceClose",s.referenceClose).put("entryLow",s.entryLow).put("entryHigh",s.entryHigh).put("takeProfit",s.takeProfit).put("stopLoss",s.stopLoss).put("riskReward",s.riskReward).put("brokerScore",s.brokerScore?:JSONObject.NULL).put("volumeScore",s.volumeScore).put("anomalyScore",s.anomalyScore).put("technicalScore",s.technicalScore).put("distributionScore",s.distributionScore).put("chasingRisk",s.chasingRisk).put("finalScore",s.finalScore).put("reasons",s.reasons).put("telegramSent",s.telegramSent).put("telegramMessageId",s.telegramMessageId?:JSONObject.NULL).put("auditTelegramSent",s.auditTelegramSent).put("status",s.status.name).put("entryTriggeredDate",s.entryTriggeredDate?:JSONObject.NULL).put("entryTriggeredPrice",s.entryTriggeredPrice?:JSONObject.NULL).put("auditDate",s.auditDate?:JSONObject.NULL).put("exitDate",s.exitDate?:JSONObject.NULL).put("exitPrice",s.exitPrice?:JSONObject.NULL).put("result",s.result?:JSONObject.NULL).put("gainLoss",s.gainLoss?:JSONObject.NULL).put("scoringMode",s.scoringMode).put("strategyVersion",s.strategyVersion).put("evidenceJson",s.evidenceJson?:JSONObject.NULL).put("supersededBy",s.supersededBy?:JSONObject.NULL).put("lastAuditAt",s.lastAuditAt?:JSONObject.NULL).put("lastAuditSource",s.lastAuditSource?:JSONObject.NULL)) }
        }).toString(2)
    }

    suspend fun restoreJson(text: String): Int {
        val root=JSONObject(text)
        require(root.optString("format")=="Lumi Signal Backup v1") { "Format backup tidak dikenali." }
        val a=root.getJSONArray("signals")
        val restored=(0 until a.length()).map { i ->
            val j=a.getJSONObject(i)
            SignalEntity(
                uuid=j.getString("uuid"), ticker=j.getString("ticker"), companyName=j.getString("companyName"), setup=j.getString("setup"),
                signalDate=j.getLong("signalDate"), signalTimestamp=j.getLong("signalTimestamp"), referenceTradingDate=j.getLong("referenceTradingDate"), referenceClose=j.getDouble("referenceClose"),
                entryLow=j.getInt("entryLow"), entryHigh=j.getInt("entryHigh"), takeProfit=j.getInt("takeProfit"), stopLoss=j.getInt("stopLoss"), riskReward=j.getDouble("riskReward"),
                brokerScore=j.nullableDouble("brokerScore"), volumeScore=j.getDouble("volumeScore"), anomalyScore=j.getDouble("anomalyScore"), technicalScore=j.getDouble("technicalScore"),
                distributionScore=j.getDouble("distributionScore"), chasingRisk=j.getDouble("chasingRisk"), finalScore=j.getDouble("finalScore"), reasons=j.getString("reasons"),
                telegramSent=j.getBoolean("telegramSent"), telegramMessageId=j.nullableString("telegramMessageId"), auditTelegramSent=j.optBoolean("auditTelegramSent", false), status=SignalStatus.valueOf(j.getString("status")),
                entryTriggeredDate=j.nullableLong("entryTriggeredDate"), entryTriggeredPrice=j.nullableDouble("entryTriggeredPrice"), auditDate=j.nullableLong("auditDate"),
                exitDate=j.nullableLong("exitDate"), exitPrice=j.nullableDouble("exitPrice"), result=j.nullableString("result"), gainLoss=j.nullableDouble("gainLoss"), scoringMode=j.optString("scoringMode","FLOW_AWARE"),
                strategyVersion=j.optString("strategyVersion", STRATEGY_VERSION), evidenceJson=j.nullableString("evidenceJson"), supersededBy=j.nullableString("supersededBy"),
                lastAuditAt=j.nullableLong("lastAuditAt"), lastAuditSource=j.nullableString("lastAuditSource")
            )
        }
        val inserted=db.signalDao().insertImported(restored).count { it != -1L }
        log("Backup restored", "$inserted record baru; ${restored.size-inserted} duplikat dilewati")
        return inserted
    }

    suspend fun resetHistory() {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        log("History direset oleh pengguna", level = "WARN")
    }

    companion object {
        private val JAKARTA: ZoneId = ZoneId.of("Asia/Jakarta")
        const val TELEGRAM_TOKEN_KEY = "telegram_bot_token"
        const val TELEGRAM_CHAT_KEY = "telegram_chat_id"
        const val SIGNAL_EXPIRY_SESSIONS = 10
        const val BACKGROUND_AUDIT_KEY = "background_audit_enabled"
        const val AUDIT_HEARTBEAT_KEY = "background_audit_last_success"
        const val STRATEGY_VERSION = "1.12.3"
    }

    private fun encodeSeries(series: MarketSeries) = JSONObject()
        .put("ticker", series.ticker)
        .put("companyName", series.companyName)
        .put("currency", series.currency)
        .put("lastUpdated", series.lastUpdated)
        .put("candles", JSONArray().apply {
            series.candles.forEach { candle ->
                put(JSONObject()
                    .put("epoch", candle.epochSeconds).put("open", candle.open).put("high", candle.high)
                    .put("low", candle.low).put("close", candle.close).put("adjusted", candle.adjustedClose ?: JSONObject.NULL)
                    .put("volume", candle.volume).put("value", candle.tradedValue ?: JSONObject.NULL)
                    .put("frequency", candle.frequency ?: JSONObject.NULL))
            }
        })

    private fun decodeSeries(root: JSONObject, fallbackCompanyName: String): MarketSeries {
        val rows = root.getJSONArray("candles")
        val candles = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Candle(
                epochSeconds = row.getLong("epoch"), open = row.getDouble("open"), high = row.getDouble("high"),
                low = row.getDouble("low"), close = row.getDouble("close"),
                adjustedClose = row.nullableDouble("adjusted"), volume = row.getLong("volume"),
                tradedValue = row.nullableDouble("value"), frequency = row.nullableLong("frequency")
            )
        }
        require(candles.size >= 60)
        return MarketSeries(
            ticker = root.getString("ticker"),
            companyName = root.optString("companyName").ifBlank { fallbackCompanyName },
            currency = root.optString("currency", "IDR"), candles = candles,
            lastUpdated = root.optLong("lastUpdated", System.currentTimeMillis())
        )
    }
}

private fun JSONObject.nullableString(key:String)=if(isNull(key))null else getString(key)
private fun JSONObject.nullableDouble(key:String)=if(isNull(key))null else getDouble(key)
private fun JSONObject.nullableLong(key:String)=if(isNull(key))null else getLong(key)

object CandidateJson {
    fun encode(c: Candidate) = JSONObject()
        .put("ticker", c.ticker).put("companyName", c.companyName).put("referenceDate", c.referenceDate).put("referenceClose", c.referenceClose).put("setup", c.setup.name)
        .put("brokerScore", c.scores.broker ?: JSONObject.NULL).put("volumeScore", c.scores.volume).put("anomalyScore", c.scores.anomaly).put("technicalScore", c.scores.technical)
        .put("distribution", c.scores.distributionRisk).put("chasing", c.scores.chasingRisk).put("rawFinal", c.scores.rawFinal).put("adjustedFinal", c.scores.adjustedFinal).put("brokerAvailable", c.scores.brokerAvailable)
        .put("entryLow",c.tradePlan.entryLow).put("entryHigh",c.tradePlan.entryHigh).put("tp",c.tradePlan.takeProfit).put("sl",c.tradePlan.stopLoss).put("rr",c.tradePlan.riskReward).put("area",c.tradePlan.accumulationArea)
        .put("rvol5",c.rvol5).put("rvol20",c.rvol20).put("valueExpansion",c.valueExpansion).put("volumeZ",c.volumeZScore).put("why",JSONArray(c.why))
        .put("strategy", c.strategy.name).put("strategyScore", c.strategyScore)
        .put("matchedCriteria", c.matchedCriteria).put("totalCriteria", c.totalCriteria).put("passed", c.passed)
        .put("criteria", JSONArray().apply { c.criteria.forEach { put(JSONObject().put("label",it.label).put("state",it.state.name).put("evidence",it.evidence)) } })
        .put("indicators", JSONArray().apply { c.indicators.forEach { put(JSONObject().put("label",it.label).put("value",it.value).put("tone",it.tone)) } })
        .put("dataLimitations", JSONArray(c.dataLimitations))
        .put("calculationComplete", c.calculationComplete)
        .put("operationalGatesComplete", c.operationalGatesComplete)
        .put("potentiallyEligible", c.potentiallyEligible)
        .put("technicalSnapshot", JSONObject()
            .put("ema20",c.technical.ema20).put("ema50",c.technical.ema50).put("rsi14",c.technical.rsi14)
            .put("atr14",c.technical.atr14).put("dayReturn",c.technical.dayReturn).put("twentyDayReturn",c.technical.twentyDayReturn)
            .put("support",c.technical.support).put("resistance",c.technical.resistance).put("clv10",c.technical.clv10)
            .put("obvSlope",c.technical.obvSlope).put("adlSlope",c.technical.adlSlope)
            .put("atrCompression",c.technical.atrCompression).put("bollingerBandwidth",c.technical.bollingerBandwidth))
        .put("brokerAnalysis", encodeBrokerAnalysis(c.brokerAnalysis))
    fun decode(j: JSONObject): Candidate {
        val scores = ScoreBreakdown(if (j.isNull("brokerScore")) null else j.getDouble("brokerScore"), j.getDouble("volumeScore"), j.getDouble("anomalyScore"), j.getDouble("technicalScore"), j.getDouble("distribution"), j.getDouble("chasing"), j.getDouble("rawFinal"), j.getDouble("adjustedFinal"), j.getBoolean("brokerAvailable"))
        val plan = TradePlan(j.getInt("entryLow"),j.getInt("entryHigh"),j.getInt("tp"),j.getInt("sl"),j.getDouble("rr"),j.getDouble("area"))
        val whyArray=j.getJSONArray("why"); val why=(0 until whyArray.length()).map { whyArray.getString(it) }
        val broker = j.optJSONObject("brokerAnalysis")?.let(::decodeBrokerAnalysis)
            ?: BrokerAnalysis(scores.brokerAvailable, score = scores.broker)
        val technical = j.optJSONObject("technicalSnapshot")?.let {
            TechnicalSnapshot(it.optDouble("ema20"),it.optDouble("ema50"),it.optDouble("rsi14",50.0),it.optDouble("atr14"),it.optDouble("dayReturn"),it.optDouble("twentyDayReturn"),
                it.optDouble("support"),it.optDouble("resistance"),it.optDouble("clv10"),it.optDouble("obvSlope"),it.optDouble("adlSlope"),it.optDouble("atrCompression",1.0),it.optDouble("bollingerBandwidth"))
        } ?: TechnicalSnapshot()
        val criteria = j.optJSONArray("criteria")?.let { array -> (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { row ->
            StrategyCriterion(row.optString("label"),runCatching { CriterionState.valueOf(row.optString("state")) }.getOrDefault(CriterionState.DATA_UNAVAILABLE),row.optString("evidence"))
        } } } ?: emptyList()
        val indicators = j.optJSONArray("indicators")?.let { array -> (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { row -> StrategyIndicator(row.optString("label"),row.optString("value"),row.optString("tone","NEUTRAL")) } } } ?: emptyList()
        val limitations = j.optJSONArray("dataLimitations")?.let { array -> (0 until array.length()).map { array.optString(it) } } ?: emptyList()
        val strategy = StrategyType.fromStored(j.optString("strategy", j.optString("setup")))
        return Candidate(j.getString("ticker"),j.getString("companyName"),j.getLong("referenceDate"),j.getDouble("referenceClose"),SetupType.valueOf(j.getString("setup")),scores,plan,j.getDouble("rvol5"),j.getDouble("rvol20"),j.getDouble("valueExpansion"),j.getDouble("volumeZ"),why,broker,technical,
            strategy,j.optDouble("strategyScore",scores.adjustedFinal),j.optInt("matchedCriteria"),j.optInt("totalCriteria",10),j.optBoolean("passed"),criteria,indicators,limitations,
            j.optBoolean("calculationComplete", criteria.none { it.state == CriterionState.DATA_UNAVAILABLE }),
            j.optBoolean("operationalGatesComplete", true),
            j.optBoolean("potentiallyEligible", j.optBoolean("passed")))
    }

    fun encodeBrokerAnalysis(b: BrokerAnalysis) = JSONObject()
        .put("available", b.available)
        .put("failureKind", b.failureKind?.name ?: JSONObject.NULL)
        .put("score", b.score ?: JSONObject.NULL)
        .put("netBuy", b.netBuy ?: JSONObject.NULL)
        .put("persistence", b.persistenceDays ?: JSONObject.NULL)
        .put("windows", b.windowDays ?: JSONObject.NULL)
        .put("buyerConcentration", b.buyerConcentration ?: JSONObject.NULL)
        .put("sellerConcentration", b.sellerConcentration ?: JSONObject.NULL)
        .put("foreignPeriods", JSONObject().apply { b.foreignPeriodNetBuy.forEach { (period, value) -> put(period.toString(), value) } })
        .put("foreignNetBuy", b.foreignNetBuy ?: JSONObject.NULL)
        .put("foreignAvailable", b.foreignDataAvailable)
        .put("interpretation", b.flowInterpretation ?: JSONObject.NULL)
        .put("explanation", JSONArray(b.explanation))
        .put("periods", JSONObject().apply { b.periodNetBuy.forEach { (period, value) -> put(period.toString(), value) } })
        .put("buyers", JSONArray().apply { b.topBuyers.forEach { put(encodeFlowItem(it)) } })
        .put("sellers", JSONArray().apply { b.topSellers.forEach { put(encodeFlowItem(it)) } })
        .put("periodBuyers", encodeFlowPeriods(b.periodTopBuyers))
        .put("periodSellers", encodeFlowPeriods(b.periodTopSellers))
        .put("dailyFlows", JSONArray().apply { b.dailyFlows.forEach { day ->
            put(JSONObject().put("day", day.epochDay).put("buy", day.buyValue).put("sell", day.sellValue)
                .put("buyers", encodeDoubleMap(day.topBuyers)).put("sellers", encodeDoubleMap(day.topSellers)))
        } })
        .put("topBuyerPersistenceDays", b.topBuyerPersistenceDays ?: JSONObject.NULL)
        .put("correctionNetBuyDays", b.correctionNetBuyDays ?: JSONObject.NULL)

    private fun encodeFlowItem(item: BrokerFlowItem) = JSONObject()
        .put("code", item.code).put("value", item.netValue)
        .put("average", item.averagePrice ?: JSONObject.NULL)
        .put("class", item.investorClass ?: JSONObject.NULL)

    private fun encodeFlowPeriods(values: Map<Int, List<BrokerFlowItem>>) = JSONObject().apply {
        values.forEach { (period, rows) ->
            put(period.toString(), JSONArray().apply { rows.forEach { put(encodeFlowItem(it)) } })
        }
    }

    private fun encodeDoubleMap(values: Map<String, Double>) = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }

    fun decodeBrokerAnalysis(j: JSONObject): BrokerAnalysis {
        val periods = linkedMapOf<Int, Double>()
        j.optJSONObject("periods")?.let { values -> values.keys().forEach { key -> key.toIntOrNull()?.let { periods[it] = values.optDouble(key) } } }
        val foreignPeriods = linkedMapOf<Int, Double>()
        j.optJSONObject("foreignPeriods")?.let { values -> values.keys().forEach { key -> key.toIntOrNull()?.let { foreignPeriods[it] = values.optDouble(key) } } }
        fun items(array: JSONArray?): List<BrokerFlowItem> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { row ->
                    BrokerFlowItem(row.optString("code"), row.optDouble("value"), row.optDoubleOrNull("average"), row.optString("class").takeIf { it.isNotBlank() })
                }
            }
        }
        fun periodItems(key: String): Map<Int, List<BrokerFlowItem>> {
            val root = j.optJSONObject(key) ?: return emptyMap()
            return buildMap {
                root.keys().forEach { period -> period.toIntOrNull()?.let { put(it, items(root.optJSONArray(period))) } }
            }
        }
        val buyers = items(j.optJSONArray("buyers"))
        val sellers = items(j.optJSONArray("sellers"))
        val periodBuyers = periodItems("periodBuyers")
        val periodSellers = periodItems("periodSellers")
        val explanations = j.optJSONArray("explanation")?.let { array -> (0 until array.length()).map { array.optString(it) } } ?: emptyList()
        fun valueMap(root: JSONObject?): Map<String, Double> = buildMap {
            root?.keys()?.forEach { key -> put(key, root.optDouble(key)) }
        }
        val dailyFlows = j.optJSONArray("dailyFlows")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { row ->
                BrokerDay(row.optLong("day"), row.optDouble("buy"), row.optDouble("sell"), valueMap(row.optJSONObject("buyers")), valueMap(row.optJSONObject("sellers")))
            } }
        } ?: emptyList()
        return BrokerAnalysis(
            available = j.optBoolean("available"),
            score = j.optDoubleOrNull("score"), netBuy = j.optDoubleOrNull("netBuy"),
            persistenceDays = j.optInt("persistence").takeIf { !j.isNull("persistence") },
            windowDays = j.optInt("windows").takeIf { !j.isNull("windows") },
            buyerConcentration = j.optDoubleOrNull("buyerConcentration"),
            sellerConcentration = j.optDoubleOrNull("sellerConcentration"),
            explanation = explanations,
            periodNetBuy = periods, topBuyers = buyers, topSellers = sellers,
            periodTopBuyers = periodBuyers.ifEmpty { mapOf(10 to buyers) },
            periodTopSellers = periodSellers.ifEmpty { mapOf(10 to sellers) },
            foreignPeriodNetBuy = foreignPeriods.ifEmpty { j.optDoubleOrNull("foreignNetBuy")?.let { mapOf(10 to it) } ?: emptyMap() },
            foreignNetBuy = j.optDoubleOrNull("foreignNetBuy"),
            foreignDataAvailable = j.optBoolean("foreignAvailable"),
            flowInterpretation = if (j.isNull("interpretation")) null else j.optString("interpretation"),
            dailyFlows = dailyFlows,
            topBuyerPersistenceDays = j.optInt("topBuyerPersistenceDays").takeIf { !j.isNull("topBuyerPersistenceDays") },
            correctionNetBuyDays = j.optInt("correctionNetBuyDays").takeIf { !j.isNull("correctionNetBuyDays") },
            failureKind = j.optString("failureKind").takeIf { it.isNotBlank() }
                ?.let { runCatching { BrokerFailureKind.valueOf(it) }.getOrNull() }
        )
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

fun Candidate.signalUuid(): String = "signal-v2-${strategy.name}-${ticker.uppercase()}-$referenceDate"

private fun Candidate.toEntity(uuid: String) = SignalEntity(uuid, ticker, companyName, strategy.name, referenceDate, System.currentTimeMillis(), referenceDate, referenceClose,
    tradePlan.entryLow, tradePlan.entryHigh, tradePlan.takeProfit, tradePlan.stopLoss, tradePlan.riskReward,
    scores.broker, scores.volume, scores.anomaly, scores.technical, scores.distributionRisk, scores.chasingRisk, scores.adjustedFinal, why.joinToString("\n"),
    scoringMode = if (scores.brokerAvailable) "STOCKBIT_FLOW_AWARE" else "STOCKBIT_MARKET_NO_BROKER",
    strategyVersion = AppRepository.STRATEGY_VERSION,
    evidenceJson = CandidateJson.encode(this).toString())

object TelegramFormatter {
    private val date = DateTimeFormatter.ofPattern("dd MMMM yyyy").withZone(ZoneId.of("Asia/Jakarta"))
    private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    fun signal(s: SignalEntity) = buildString {
        appendLine("🚨 <b>LUMI SIGNAL — IDX</b>")
        appendLine()
        appendLine("📊 <b>${s.ticker.removeSuffix(".JK")}</b>")
        appendLine("🔥 Strategi: ${StrategyType.fromStored(s.setup).number} — ${StrategyType.fromStored(s.setup).label}")
        appendLine("💰 Last Price: Rp${s.referenceClose.toInt().formatIdr()}")
        appendLine()
        appendLine("🎯 <b>ENTRY</b>: Rp${s.entryLow.formatIdr()} – Rp${s.entryHigh.formatIdr()}")
        appendLine("✅ <b>TAKE PROFIT</b>: Rp${s.takeProfit.formatIdr()}")
        appendLine("🛑 <b>STOP LOSS</b>: Rp${s.stopLoss.formatIdr()}")
        appendLine("⚖️ Risk/Reward: 1 : ${"%.2f".format(s.riskReward)}")
        appendLine()
        appendLine("📌 <b>SCORE</b>")
        appendLine("Final: ${s.finalScore.toInt()}/100")
        appendLine("Broker: ${s.brokerScore?.toInt()?.toString() ?: "Unavailable"}/100")
        appendLine("Volume: ${s.volumeScore.toInt()}/100")
        appendLine("Anomaly: ${s.anomalyScore.toInt()}/100")
        appendLine("Technical: ${s.technicalScore.toInt()}/100")
        appendLine()
        appendLine("🔎 <b>WHY LUMI PICKED THIS</b>")
        s.reasons.lines().filter { it.isNotBlank() }.forEach { appendLine("• ${it.trim()}") }
        appendLine()
        appendLine("📅 Reference Closing: ${date.format(Instant.ofEpochSecond(s.referenceTradingDate))}")
        append("<b>Lumi Signal - IDX Screener</b>")
    }

    fun audit(rows: List<SignalEntity>, summary: String) = buildString {
        appendLine("🔎 <b>LUMI SIGNAL — AUDIT REPORT</b>")
        appendLine("<i>${html(summary.trim())}</i>")
        appendLine("━━━━━━━━━━━━━━━━")
        rows.forEachIndexed { index, row ->
            val strategy = StrategyType.fromStored(row.setup)
            val title = when (row.status) {
                SignalStatus.TAKE_PROFIT -> "✅ TAKE PROFIT"
                SignalStatus.STOP_LOSS -> "🛑 STOP LOSS"
                SignalStatus.HOLDING -> "🟢 HOLDING"
                SignalStatus.AMBIGUOUS -> "⚠️ AMBIGUOUS"
                SignalStatus.EXPIRED -> "⚪ EXPIRED"
                SignalStatus.WAITING_ENTRY -> "🟡 WAITING ENTRY"
                SignalStatus.SUPERSEDED -> "↪️ SUPERSEDED"
            }
            appendLine("$title • <b>${html(row.ticker.removeSuffix(".JK"))}</b>")
            appendLine("🏢 ${html(row.companyName)}")
            appendLine("🔥 Strategi ${strategy.number}: ${html(strategy.label)}")
            if (row.entryTriggeredPrice != null) {
                appendLine("🎯 Entry aktual: <b>Rp${row.entryTriggeredPrice.toInt().formatIdr()}</b>${row.entryTriggeredDate?.let { " • ${date.format(Instant.ofEpochSecond(it))}" }.orEmpty()}")
            } else {
                appendLine("🎯 Area entry: Rp${row.entryLow.formatIdr()}–Rp${row.entryHigh.formatIdr()}")
            }
            when (row.status) {
                SignalStatus.HOLDING -> {
                    appendLine("📈 Target: Rp${row.takeProfit.formatIdr()}  |  🛡 SL: Rp${row.stopLoss.formatIdr()}")
                    appendLine("📍 Posisi aktif — menunggu TP atau SL")
                }
                SignalStatus.TAKE_PROFIT, SignalStatus.STOP_LOSS -> {
                    appendLine("🏁 Exit: <b>Rp${row.exitPrice?.toInt()?.formatIdr() ?: "—"}</b>${row.exitDate?.let { " • ${date.format(Instant.ofEpochSecond(it))}" }.orEmpty()}")
                    appendLine("💹 Hasil: <b>${row.gainLoss?.let { "%+.2f%%".format(it) } ?: "—"}</b>")
                }
                SignalStatus.AMBIGUOUS -> appendLine("⚖️ TP dan SL tersentuh pada candle yang sama; urutan belum dapat dipastikan.")
                SignalStatus.EXPIRED -> appendLine("⌛ Area entry tidak tersentuh sampai batas masa berlaku.")
                SignalStatus.WAITING_ENTRY -> appendLine("👀 Belum tersentuh — pantau area entry.")
                SignalStatus.SUPERSEDED -> appendLine("↪️ Digantikan screening yang lebih baru sebelum dikirim.")
            }
            if (index != rows.lastIndex) appendLine("────────────────")
        }
        appendLine("━━━━━━━━━━━━━━━━")
        append("✨ <b>Lumi Signal • IDX Screener</b>")
    }
}

fun Int.formatIdr(): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale("id","ID")).format(this)
