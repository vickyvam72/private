package com.lumisignal.idxscreener.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lumisignal.idxscreener.LumiApplication
import com.lumisignal.idxscreener.R
import com.lumisignal.idxscreener.data.ScreeningRunEntity
import com.lumisignal.idxscreener.data.TickerCacheEntity
import com.lumisignal.idxscreener.engine.StrategyEngine
import com.lumisignal.idxscreener.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Runs one market-wide pass and stores an independent Top 5 for each strategy. */
class ScreeningWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Menyiapkan screening pasar", 0, 0)

    override suspend fun doWork(): Result = try {
        runScreening()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(stage("Screening tidak dapat dimulai: ${e.message ?: "kesalahan worker Android"}", 0, 0))
    }

    private suspend fun runScreening(): Result {
        val repo = (applicationContext as LumiApplication).repository
        val runId = id.toString()
        val runDao = repo.db.screeningRunDao()
        var run = runDao.get(runId) ?: ScreeningRunEntity(runId, System.currentTimeMillis(), System.currentTimeMillis())

        suspend fun publish(message: String, current: Int = 0, total: Int = 0, update: (ScreeningRunEntity) -> ScreeningRunEntity = { it }) {
            setProgress(stage(message, current, total))
            setForeground(foreground(message, current, total))
            run = update(run).copy(stage = message, updatedAt = System.currentTimeMillis())
            runDao.upsert(run)
        }

        suspend fun fail(message: String, current: Int = 0, total: Int = 0): Result {
            publish(message, current, total) { it.copy(status = "FAILED", completedAt = System.currentTimeMillis(), message = message) }
            return Result.failure(stage(message, current, total))
        }

        createChannel()
        setForeground(getForegroundInfo())
        runDao.upsert(run)
        try {
            if (!repo.stockbit.hasSession()) return fail("Stockbit wajib. Login terlebih dahulu.")
            publish("Memvalidasi sesi dan akses Stockbit PRO...")
            val capabilities = withTimeoutOrNull(STOCKBIT_CONNECTION_TIMEOUT_MS) { repo.stockbit.checkCapabilities() }
                ?: return fail("Validasi Stockbit PRO timeout.")
            if (capabilities.marketData != "Connected") return fail(capabilities.accountMessage)
            if (!capabilities.accountTier.strategiesUnlocked) return fail(capabilities.accountMessage)

            publish("Mengambil universe saham IDX...")
            val snapshot = when (val result = repo.idxUniverse.fetch()) {
                is DataResult.Success -> {
                    repo.db.cacheDao().markAllTickersInactive()
                    repo.db.cacheDao().upsertTickers(result.value.tickers.mapIndexed { index, ticker ->
                        TickerCacheEntity(ticker.ticker, ticker.companyName, result.value.source, result.value.fetchedAt, true,
                            ticker.averageVolume10d, ticker.estimatedAverageValue10d, index + 1)
                    })
                    result.value
                }
                is DataResult.Error -> {
                    val cached = repo.db.cacheDao().tickers()
                    val newest = cached.maxOfOrNull { it.lastUpdated } ?: 0L
                    if (cached.isEmpty() || System.currentTimeMillis() - newest > MAX_UNIVERSE_CACHE_AGE_MS) return fail(result.userMessage)
                    UniverseSnapshot(cached.map { UniverseTicker(it.ticker, it.companyName, it.averageVolume10d, it.estimatedAverageValue10d) }, cached.size, "LOCAL_CACHE", newest, true)
                }
            }

            val universe = snapshot.tickers
                .distinctBy { it.ticker.uppercase() }
                .filter { it.ticker.removeSuffix(".JK").matches(Regex("^[A-Z]{4}$")) }
            if (universe.isEmpty()) return fail("Universe saham IDX kosong.")
            publish("Universe ${universe.size} saham ditemukan", 0, universe.size) { it.copy(universeDiscovered = universe.size) }
            repo.log("IDX universe loaded", "${snapshot.totalCount} instrumen; ${universe.size} saham biasa diperiksa")

            repo.purgeExpiredCache()
            repo.stockbit.resetBrokerHealth()
            val startedAt = System.currentTimeMillis()
            val trafficBefore = repo.stockbit.trafficStats()
            val noBroker = BrokerAnalysis(
                false,
                explanation = listOf("Broker summary belum diambil pada tahap awal"),
                failureKind = BrokerFailureKind.UNKNOWN
            )
            val publishMutex = kotlinx.coroutines.sync.Mutex()
            var lastPublish = 0L
            suspend fun throttledPublish(message: String, current: Int, total: Int, force: Boolean = false, update: (ScreeningRunEntity) -> ScreeningRunEntity = { it }) {
                if (!force && System.currentTimeMillis() - lastPublish < PROGRESS_INTERVAL_MS) return
                publishMutex.withLock {
                    if (!force && System.currentTimeMillis() - lastPublish < PROGRESS_INTERVAL_MS) return@withLock
                    lastPublish = System.currentTimeMillis()
                    publish(message, current, total, update)
                }
            }

            // ---- Stage 1: daily OHLCVF ------------------------------------------------------
            // Primary source: IDX market-wide daily summary (one request per session for every
            // stock, cached). Stockbit per-ticker history is only the fallback.
            val seriesByTicker = ConcurrentHashMap<String, MarketSeries>()
            val checked = AtomicInteger(0)
            val technicalFailures = AtomicInteger(0)
            val names = universe.associate { it.ticker to it.companyName }
            var foreignByTicker: Map<String, List<Pair<java.time.LocalDate, Double>>> = emptyMap()
            var seriesSource = "STOCKBIT"
            publish("Memuat ringkasan harian IDX (seluruh pasar)...", 0, 72)
            // Fast path: IDX days already cached on the phone plus at most a few new sessions.
            var idxSeeding: Job? = null
            when (val idx = withTimeoutOrNull(IDX_QUICK_TIMEOUT_MS) {
                repo.idxDaily.buildSeries(names, maxNetworkDays = IDX_QUICK_NETWORK_DAYS) { done: Int, target: Int ->
                    throttledPublish("Ringkasan harian IDX $done/$target sesi", done, target)
                }
            }) {
                is DataResult.Success -> {
                    universe.forEach { item -> idx.value.series[item.ticker]?.let { seriesByTicker[item.ticker] = it } }
                    foreignByTicker = idx.value.foreignNet
                    seriesSource = "IDX"
                    repo.log("Data harian IDX dipakai", "${seriesByTicker.size}/${universe.size} saham • ${idx.value.sessions.size} sesi • ${idx.value.networkRequests} request jaringan • via ${repo.idxDaily.lastTransport}")
                }
                else -> {
                    val reason = (idx as? DataResult.Error)?.userMessage ?: "timeout"
                    repo.log("Data harian IDX belum siap, screening ini memakai Stockbit", "$reason • sinkron IDX dilanjutkan di latar belakang", "WARN")
                    // Slow path: this run uses Stockbit, while the IDX history is downloaded
                    // gently in parallel and cached, so later screenings take the fast path.
                    idxSeeding = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]) + Dispatchers.IO).launch {
                        val seeded = withTimeoutOrNull(IDX_SEED_BUDGET_MS) { repo.idxDaily.buildSeries(names) }
                        repo.log(
                            if (seeded is DataResult.Success) "Sinkron IDX selesai — screening berikutnya memakai IDX" else "Sinkron IDX berlanjut di screening berikutnya",
                            "${repo.idxDaily.lastCachedSessions}+ sesi tersimpan • via ${repo.idxDaily.lastTransport}" +
                                ((seeded as? DataResult.Error)?.userMessage?.let { " • $it" } ?: ""),
                            if (seeded is DataResult.Success) "INFO" else "WARN"
                        )
                    }
                }
            }
            // Fast free prefilter: Yahoo Finance OHLCV (no frequency/broker) decides which stocks are
            // worth a full Stockbit OHLCVF read. Generous by design (missing inputs count as possible,
            // one extra miss tolerated); if Yahoo is unavailable every stock goes to Stockbit as before.
            var stockbitUniverse = universe
            var yahooSkipped = 0
            if (seriesSource != "IDX") {
                val yahooSeries = ConcurrentHashMap<String, MarketSeries>()
                val yahooDone = AtomicInteger(0)
                withTimeoutOrNull(YAHOO_STAGE_TIMEOUT_MS) {
                    val nextYahoo = AtomicInteger(0)
                    coroutineScope {
                        repeat(minOf(YAHOO_CONCURRENCY, universe.size)) {
                            launch(Dispatchers.IO) {
                                while (true) {
                                    val item = universe.getOrNull(nextYahoo.getAndIncrement()) ?: break
                                    try {
                                        val data = withTimeoutOrNull(YAHOO_TICKER_TIMEOUT_MS) { repo.yahoo.fetch(item.ticker, item.companyName) }
                                        if (data is DataResult.Success) yahooSeries[item.ticker] = data.value
                                    } finally {
                                        yahooDone.incrementAndGet()
                                        throttledPublish("Prefilter cepat Yahoo Finance ${yahooDone.get()}/${universe.size}", yahooDone.get(), universe.size)
                                    }
                                }
                            }
                        }
                    }
                }
                if (yahooSeries.size >= universe.size * 0.6) {
                    stockbitUniverse = universe.filter { item ->
                        yahooSeries[item.ticker]?.let { StrategyEngine.prefilterCouldQualify(it) } ?: true
                    }
                    yahooSkipped = universe.size - stockbitUniverse.size
                    repo.log("Prefilter Yahoo Finance", "${yahooSeries.size}/${universe.size} saham terbaca • ${stockbitUniverse.size} diteruskan ke Stockbit • $yahooSkipped jelas tidak memenuhi syarat teknikal")
                } else {
                    repo.log("Yahoo Finance tidak memadai", "${yahooSeries.size}/${universe.size} saham terbaca; semua saham diambil dari Stockbit", "WARN")
                }
            }
            val missing = stockbitUniverse.filter { !seriesByTicker.containsKey(it.ticker) }
            // With IDX data only a handful of tickers (new listings, suspensions) still need
            // Stockbit; without it this is the original per-ticker sync.
            checked.set(universe.size - missing.size)
            val technicalCompleted = withTimeoutOrNull(MARKET_STAGE_TIMEOUT_MS) {
                val next = AtomicInteger(0)
                coroutineScope {
                    repeat(minOf(STOCKBIT_CONCURRENCY, missing.size)) {
                        launch(Dispatchers.IO) {
                            while (true) {
                                val item = missing.getOrNull(next.getAndIncrement()) ?: break
                                try {
                                    when (val data = withTimeoutOrNull(STOCKBIT_TICKER_TIMEOUT_MS) { repo.screeningSeries(item.ticker, item.companyName) }) {
                                        is DataResult.Success -> seriesByTicker[item.ticker] = data.value
                                        else -> technicalFailures.incrementAndGet()
                                    }
                                } finally {
                                    checked.incrementAndGet()
                                    throttledPublish("OHLCVF ${checked.get()}/${universe.size}", checked.get(), universe.size) {
                                        it.copy(technicalAttempted = checked.get(), technicalValid = seriesByTicker.size, technicalFailed = technicalFailures.get())
                                    }
                                }
                            }
                        }
                    }
                }
                true
            } ?: false
            if (!technicalCompleted) repo.log("Tahap OHLCVF mencapai batas waktu", "${checked.get()}/${universe.size} selesai", "WARN")
            if (seriesByTicker.isEmpty()) return fail("Tidak ada ticker dengan minimal 60 sesi OHLCVF.", checked.get(), universe.size)

            val referenceEpoch = seriesByTicker.values.maxOf { it.candles.last().epochSeconds }
            val activeSeries = seriesByTicker.values.filter { it.candles.last().epochSeconds == referenceEpoch }
            val activeTickers = activeSeries.map { it.ticker }.toSet()
            val staleExcluded = seriesByTicker.size - activeSeries.size

            // ---- Stage 2: technical ranking ---------------------------------------------------
            // Upper-bound filter (missing broker criteria count as satisfiable), then rank by the
            // strongest technical reading so the scarce broker budget goes to the best setups first.
            val technicalRank = activeSeries.mapNotNull { series ->
                val potentials = StrategyEngine.analyze(series, noBroker).filter { it.potentiallyEligible }
                if (potentials.isEmpty()) null else series to potentials.maxOf { it.matchedCriteria * 10.0 + it.strategyScore / 10.0 }
            }.sortedByDescending { it.second }.map { it.first }
            publish("${technicalRank.size} saham lolos prefilter teknikal", technicalRank.size, activeSeries.size) {
                it.copy(technicalAttempted = checked.get(), technicalValid = seriesByTicker.size, technicalFailed = technicalFailures.get(),
                    staleExcluded = staleExcluded, referenceDate = referenceEpoch)
            }

            // ---- Stage 3: budgeted broker verification ----------------------------------------
            val seedByTicker = ConcurrentHashMap<String, BrokerAnalysis>()
            val seedChecked = AtomicInteger(0)
            val seedValid = AtomicInteger(0)
            val detailStarted = AtomicInteger(0)
            val detailChecked = AtomicInteger(0)
            val detailValid = AtomicInteger(0)
            // ConcurrentHashMap rejects null values, so a completed detail is Optional.empty().
            val detailOutcome = ConcurrentHashMap<String, java.util.Optional<BrokerFailureKind>>()
            val contextLimited = ConcurrentHashMap.newKeySet<String>()
            val enrichedByTicker = ConcurrentHashMap<String, List<Candidate>>()
            val bestBroker = ConcurrentHashMap<String, BrokerAnalysis>()
            val brokerStageStart = System.currentTimeMillis()
            val stopReason = java.util.concurrent.atomic.AtomicReference<String?>(null)

            suspend fun brokerProgress(force: Boolean = false) = throttledPublish(
                "Verifikasi broker • seed ${seedChecked.get()}/${technicalRank.size} • detail ${detailChecked.get()}/${detailStarted.get()}",
                seedChecked.get(), technicalRank.size, force
            ) {
                it.copy(brokerSeedAttempted = seedChecked.get(), brokerSeedValid = seedValid.get(), brokerSeedFailed = seedChecked.get() - seedValid.get(),
                    detailAttempted = detailChecked.get(), detailValid = detailValid.get(), detailFailed = detailChecked.get() - detailValid.get())
            }

            suspend fun brokerPipeline(series: MarketSeries) {
                val ticker = series.ticker
                val seed = try {
                    withTimeoutOrNull(BROKER_SEED_TICKER_TIMEOUT_MS) {
                        repo.brokerSeedAnalysis(ticker, series.candles.map { it.epochSeconds })
                    } ?: BrokerAnalysis(false, explanation = listOf("Broker seed timeout"), failureKind = BrokerFailureKind.TIMEOUT)
                } finally { seedChecked.incrementAndGet() }
                seedByTicker[ticker] = seed
                if (!seed.available) { brokerProgress(); return }
                seedValid.incrementAndGet()
                bestBroker[ticker] = seed

                val strategies = StrategyEngine.analyze(series, seed).filter { it.potentiallyEligible }.map { it.strategy }.toSet()
                if (strategies.isEmpty()) { brokerProgress(); return }
                detailStarted.incrementAndGet()
                try {
                    val needsEventWindow = StrategyType.ABSORPTION_AT_SUPPORT in strategies || StrategyType.SHAKEOUT_SPRING_RECLAIM in strategies
                    val needsDailyPersistence = StrategyType.BROKER_ACCUMULATION_PERSISTENCE in strategies || StrategyType.BROKER_PRICE_DIVERGENCE in strategies
                    val needsLatestBroker = strategies.any { it in setOf(
                        StrategyType.VOLATILITY_COMPRESSION,
                        StrategyType.MARKUP_IGNITION,
                        StrategyType.REACCUMULATION_AFTER_FIRST_MARKUP,
                        StrategyType.BREAKOUT_RETEST_CONFIRMATION
                    ) }
                    val expectedDailySessions = if (needsEventWindow) 20 else if (needsDailyPersistence) 10 else 0
                    val (broker, market) = coroutineScope {
                        val marketAsync = async { withTimeoutOrNull(MARKET_CONTEXT_TIMEOUT_MS) { repo.stockbit.fetchMarketContext(ticker, includeExtended = false) } }
                        val brokerResult = withTimeoutOrNull(BROKER_TICKER_TIMEOUT_MS) {
                            when {
                                expectedDailySessions > 0 -> repo.brokerAnalysis(
                                    ticker, series.candles.last().epochSeconds, expectedDailySessions,
                                    series.candles.map { it.epochSeconds }
                                )
                                needsLatestBroker -> repo.brokerPeriodAnalysis(
                                    ticker, series.candles.last().epochSeconds, setOf(1, 10),
                                    series.candles.map { it.epochSeconds }
                                )
                                else -> seed
                            }
                        } ?: BrokerAnalysis(false, explanation = listOf("Stockbit broker summary timeout"), failureKind = BrokerFailureKind.TIMEOUT)
                        brokerResult to marketAsync.await()
                    }
                    val contextComplete = market != null && market.uma != null && market.spreadTicks != null && market.blockingCorporateAction != null
                    val brokerComplete = when {
                        expectedDailySessions > 0 -> broker.available && broker.dailyFlows.size >= expectedDailySessions
                        needsLatestBroker -> broker.available && broker.periodNetBuy.containsKey(1)
                        else -> broker.available
                    }
                    if (brokerComplete) {
                        detailValid.incrementAndGet()
                        detailOutcome[ticker] = java.util.Optional.empty()
                    } else {
                        detailOutcome[ticker] = java.util.Optional.of(broker.failureKind ?: BrokerFailureKind.UNKNOWN)
                    }
                    if (broker.available) bestBroker[ticker] = broker
                    if (!contextComplete) contextLimited += ticker
                    enrichedByTicker[ticker] = StrategyEngine.analyze(series, broker, market)
                } finally {
                    detailChecked.incrementAndGet()
                    brokerProgress()
                }
            }

            fun shouldStop(): Boolean {
                if (stopReason.get() != null) return true
                val reason = when {
                    repo.stockbit.brokerDegraded() -> "Stockbit membatasi broker summary (respons kosong/429 beruntun)"
                    System.currentTimeMillis() - brokerStageStart > BROKER_TIME_BUDGET_MS -> "batas waktu verifikasi broker ${BROKER_TIME_BUDGET_MS / 60_000} menit tercapai"
                    else -> null
                }
                if (reason != null) stopReason.compareAndSet(null, reason)
                return reason != null
            }

            val brokerCompleted = withTimeoutOrNull(BROKER_TIME_BUDGET_MS + 3 * 60_000L) {
                val next = AtomicInteger(0)
                coroutineScope {
                    repeat(minOf(BROKER_TICKER_CONCURRENCY, technicalRank.size)) {
                        launch(Dispatchers.IO) {
                            while (!shouldStop()) {
                                val series = technicalRank.getOrNull(next.getAndIncrement()) ?: break
                                try {
                                    brokerPipeline(series)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    // One malformed ticker must not abort the market-wide run.
                                    detailOutcome.putIfAbsent(series.ticker, java.util.Optional.of(BrokerFailureKind.UNKNOWN))
                                    repo.log("Analisis broker ${series.ticker} gagal", e.message ?: e.javaClass.simpleName, "WARN")
                                }
                            }
                        }
                    }
                }
                stopReason.get() == null
            } ?: false
            stopReason.get()?.let { repo.log("Verifikasi broker dihentikan lebih awal", "$it • ${seedChecked.get()}/${technicalRank.size} kandidat diperiksa", "WARN") }
            brokerProgress(force = true)

            // ---- Stage 4: verified Top 5, then clearly-labelled provisional fill --------------
            val verified = enrichedByTicker.values.flatten()
            val verifiedTop = StrategyType.entries.flatMap { StrategyEngine.top(verified, it, 5) }
            val provisional = mutableListOf<Candidate>()
            val verifiedKeys = verifiedTop.map { it.ticker to it.strategy }.toSet()
            for (series in technicalRank) {
                val ticker = series.ticker
                val broker = bestBroker[ticker] ?: noBroker
                val reason = when {
                    detailOutcome[ticker]?.isPresent == true -> "detail broker gagal (${detailOutcome[ticker]?.get()?.label})"
                    seedByTicker[ticker]?.available == false -> "broker Stockbit ${seedByTicker[ticker]?.failureKind?.label ?: "tidak tersedia"}"
                    seedByTicker[ticker] == null -> "belum diperiksa (${stopReason.get() ?: "di luar prioritas"})"
                    else -> "broker belum lengkap"
                }
                val foreignLine = foreignByTicker[ticker]?.let { flows ->
                    val f5 = flows.takeLast(5).sumOf { it.second }
                    val f10 = flows.takeLast(10).sumOf { it.second }
                    "Foreign flow IDX 5D ${compactIdr(f5)} • 10D ${compactIdr(f10)}"
                }
                StrategyEngine.analyze(series, broker).forEach { c ->
                    if (c.passed || !c.potentiallyEligible || (c.ticker to c.strategy) in verifiedKeys) return@forEach
                    val unavailable = c.criteria.count { it.state == CriterionState.DATA_UNAVAILABLE }
                    if (unavailable > PROVISIONAL_MAX_UNAVAILABLE) return@forEach
                    provisional += c.copy(
                        calculationComplete = false,
                        why = listOfNotNull("⚠ KANDIDAT SEMENTARA — $reason. Bukan sinyal; konfirmasi broker di Stockbit sebelum entry.", foreignLine) + c.why,
                        dataLimitations = listOf("Broker Stockbit belum terverifikasi: $reason") + c.dataLimitations
                    )
                }
            }
            val provisionalTop = StrategyType.entries.flatMap { strategy ->
                val room = 5 - verifiedTop.count { it.strategy == strategy }
                if (room <= 0) emptyList()
                else provisional.filter { it.strategy == strategy }
                    .sortedWith(compareByDescending<Candidate> { it.matchedCriteria }.thenByDescending { it.strategyScore }.thenBy { it.ticker })
                    .take(room)
            }
            val saved = verifiedTop + provisionalTop

            // ---- Final accounting -------------------------------------------------------------
            val seedResults = technicalRank.mapNotNull { seedByTicker[it.ticker] }
            val seedValidCount = seedResults.count { it.available }
            val seedFailedCount = technicalRank.size - seedValidCount
            val detailTickers = detailOutcome.keys.filter { it in activeTickers }
            val detailValidCount = detailTickers.count { detailOutcome[it]?.isPresent == false }
            val detailFailedCount = detailTickers.size - detailValidCount
            val complete = technicalCompleted && brokerCompleted && checked.get() == universe.size && technicalFailures.get() == 0 &&
                seedResults.size == technicalRank.size && seedFailedCount == 0 && detailFailedCount == 0
            val coverage = if (complete) "COMPLETE" else "PARTIAL"
            repo.saveScreening(runId, saved, checked.get(), detailValidCount, coverage)
            val counts = StrategyType.entries.joinToString { strategy -> "${strategy.number}:${verifiedTop.count { it.strategy == strategy }}+${provisionalTop.count { it.strategy == strategy }}" }
            fun failureSummary(values: List<BrokerFailureKind>): String = values.groupingBy { it }.eachCount().entries
                .sortedBy { it.key.ordinal }
                .joinToString { "${it.key.label} ${it.value}" }
            val notAttempted = technicalRank.size - seedResults.size
            val diagnostics = buildList {
                val seedKinds = seedResults.filter { !it.available }.map { it.failureKind ?: BrokerFailureKind.UNKNOWN }
                failureSummary(seedKinds).takeIf { it.isNotBlank() }?.let { add("seed: $it") }
                if (notAttempted > 0) add("broker belum diperiksa $notAttempted${stopReason.get()?.let { " ($it)" }.orEmpty()}")
                failureSummary(detailTickers.mapNotNull { detailOutcome[it]?.orElse(null) }).takeIf { it.isNotBlank() }?.let { add("detail: $it") }
            }.joinToString(" • ")
            val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000
            val duration = "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
            val summary = "${verifiedTop.size} kandidat terverifikasi + ${provisionalTop.size} sementara"
            val finalMessage = if (complete) "Screening lengkap ($duration, data $seriesSource). $summary."
                else "Screening parsial ($duration, data $seriesSource). $summary${if (diagnostics.isBlank()) "." else " • $diagnostics"}"
            publish(finalMessage, checked.get(), universe.size) {
                it.copy(
                    status = coverage, completedAt = System.currentTimeMillis(), candidateCount = saved.size,
                    technicalAttempted = checked.get(), technicalValid = seriesByTicker.size,
                    technicalFailed = technicalFailures.get() + (universe.size - checked.get()), staleExcluded = staleExcluded,
                    brokerSeedAttempted = seedResults.size, brokerSeedValid = seedValidCount,
                    brokerSeedFailed = seedFailedCount,
                    detailAttempted = detailTickers.size, detailValid = detailValidCount,
                    detailFailed = detailFailedCount,
                    referenceDate = referenceEpoch, message = finalMessage
                )
            }
            val traffic = repo.stockbit.trafficStats()
            repo.log(
                "Screening 10 strategi selesai",
                "$coverage • $duration • data $seriesSource • ${seriesByTicker.size}/${universe.size} OHLCVF (prefilter Yahoo melewati $yahooSkipped) • seed $seedValidCount/${technicalRank.size} • detail $detailValidCount/${detailTickers.size} • " +
                    "request broker ${traffic.brokerCompleted - trafficBefore.brokerCompleted}, umum ${traffic.generalCompleted - trafficBefore.generalCompleted}, " +
                    "429 ${traffic.rateLimitHits - trafficBefore.rateLimitHits}, challenge ${traffic.challengeHits - trafficBefore.challengeHits}, " +
                    "kosong ${traffic.emptyResponses - trafficBefore.emptyResponses} (pulih ${traffic.emptyRecovered - trafficBefore.emptyRecovered}) • Top [$counts]"
            )
            if (traffic.emptyResponses > trafficBefore.emptyResponses) {
                repo.log("Contoh respons broker kosong Stockbit", traffic.lastEmptySample ?: "-", "WARN")
            }
            idxSeeding?.let { job -> withTimeoutOrNull(IDX_SEED_GRACE_MS) { job.join() } ?: job.cancelAndJoin() }
            return Result.success(stage(finalMessage, checked.get(), universe.size))
        } catch (e: CancellationException) {
            runDao.upsert(run.copy(status = "CANCELLED", completedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(), message = "Screening dibatalkan; cache checkpoint dipertahankan."))
            throw e
        } catch (e: Throwable) {
            repo.log("Screening gagal", e.message, "ERROR")
            return fail("Screening gagal: ${e.message ?: "kesalahan tak terduga"}")
        }
    }

    private fun stage(message: String, current: Int, total: Int) = Data.Builder()
        .putString("stage", message).putInt("current", current).putInt("total", total).build()

    private fun foreground(message: String, current: Int, total: Int): ForegroundInfo {
        createChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, SCREENING_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lumi Signal • Screening IDX")
            .setContentText(if (total > 0) "$message • $current/$total" else message)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(total.coerceAtLeast(0), current.coerceAtLeast(0), total <= 0)
            .addAction(0, "Batalkan", cancel).build()
        return ForegroundInfo(SCREENING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createChannel() {
        notificationManager.createNotificationChannel(NotificationChannel(SCREENING_CHANNEL, "Screening pasar", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val MAX_UNIVERSE_CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val STOCKBIT_TICKER_TIMEOUT_MS = 90_000L
        private const val MARKET_STAGE_TIMEOUT_MS = 45 * 60_000L
        // Per-ticker limits are generous on purpose: queueing behind the adaptive request lanes
        // is not a failure. Real network failures surface through the per-request retry policy.
        private const val BROKER_SEED_TICKER_TIMEOUT_MS = 90_000L
        private const val BROKER_TICKER_TIMEOUT_MS = 3 * 60_000L
        private const val MARKET_CONTEXT_TIMEOUT_MS = 60_000L
        private const val STOCKBIT_CONNECTION_TIMEOUT_MS = 25_000L
        private const val STOCKBIT_CONCURRENCY = 8
        private const val BROKER_TICKER_CONCURRENCY = 3
        private const val PROGRESS_INTERVAL_MS = 1_200L
        private const val IDX_QUICK_TIMEOUT_MS = 2 * 60_000L
        private const val YAHOO_STAGE_TIMEOUT_MS = 4 * 60_000L
        private const val YAHOO_TICKER_TIMEOUT_MS = 20_000L
        private const val YAHOO_CONCURRENCY = 8
        private const val IDX_QUICK_NETWORK_DAYS = 6
        private const val IDX_SEED_BUDGET_MS = 12 * 60_000L
        private const val IDX_SEED_GRACE_MS = 60_000L
        /** Broker verification never holds the run hostage: whatever is not verified in time is provisional. */
        private const val BROKER_TIME_BUDGET_MS = 8 * 60_000L
        private const val PROVISIONAL_MAX_UNAVAILABLE = 2

        private fun compactIdr(value: Double): String {
            val abs = kotlin.math.abs(value)
            val sign = if (value < 0) "-" else "+"
            return when {
                abs >= 1e12 -> "${sign}Rp%.2fT".format(abs / 1e12)
                abs >= 1e9 -> "${sign}Rp%.1fM".format(abs / 1e9)
                abs >= 1e6 -> "${sign}Rp%.0fjt".format(abs / 1e6)
                else -> "${sign}Rp%.0f".format(abs)
            }
        }
        private const val SCREENING_CHANNEL = "lumi_screening"
        private const val SCREENING_NOTIFICATION_ID = 7201
    }
}
