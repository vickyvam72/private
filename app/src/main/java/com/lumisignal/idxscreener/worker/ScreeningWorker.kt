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
            val startedAt = System.currentTimeMillis()
            val trafficBefore = repo.stockbit.trafficStats()
            val noBroker = BrokerAnalysis(
                false,
                explanation = listOf("Broker summary belum diambil pada tahap awal"),
                failureKind = BrokerFailureKind.UNKNOWN
            )

            // ---- Pipelined stages -------------------------------------------------------
            // OHLCVF sync (general lane) feeds the broker pipeline (broker lane) as soon as a
            // ticker passes the technical upper-bound prefilter, so both Stockbit lanes work at
            // the same time instead of one stage idling while the other finishes. Workers pull
            // from a shared queue, so one slow ticker no longer blocks a whole chunk.
            val seriesByTicker = ConcurrentHashMap<String, MarketSeries>()
            val checked = AtomicInteger(0)
            val technicalFailures = AtomicInteger(0)
            val latestEpoch = java.util.concurrent.atomic.AtomicLong(0L)
            val potentialTickers = ConcurrentHashMap.newKeySet<String>()
            val brokerQueue = kotlinx.coroutines.channels.Channel<MarketSeries>(kotlinx.coroutines.channels.Channel.UNLIMITED)

            val seedByTicker = ConcurrentHashMap<String, BrokerAnalysis>()
            val seedChecked = AtomicInteger(0)
            val seedValid = AtomicInteger(0)
            val detailStarted = AtomicInteger(0)
            val detailChecked = AtomicInteger(0)
            val detailValid = AtomicInteger(0)
            // ConcurrentHashMap rejects null values, so a completed detail is stored as Optional.empty().
            val detailOutcome = ConcurrentHashMap<String, java.util.Optional<BrokerFailureKind>>()
            val contextLimited = ConcurrentHashMap.newKeySet<String>()
            val enrichedByTicker = ConcurrentHashMap<String, List<Candidate>>()
            val staleSkipped = AtomicInteger(0)
            val technicalDone = java.util.concurrent.atomic.AtomicBoolean(false)

            val publishMutex = kotlinx.coroutines.sync.Mutex()
            var lastPublish = 0L
            suspend fun progress(force: Boolean = false) {
                val now = System.currentTimeMillis()
                if (!force && now - lastPublish < PROGRESS_INTERVAL_MS) return
                publishMutex.withLock {
                    if (!force && System.currentTimeMillis() - lastPublish < PROGRESS_INTERVAL_MS) return@withLock
                    lastPublish = System.currentTimeMillis()
                    val brokerPart = "broker seed ${seedChecked.get()}/${potentialTickers.size} • detail ${detailChecked.get()}/${detailStarted.get()}"
                    val (message, current, total) = if (!technicalDone.get()) {
                        Triple("OHLCVF ${checked.get()}/${universe.size} • $brokerPart", checked.get(), universe.size)
                    } else {
                        val total = potentialTickers.size + detailStarted.get()
                        Triple("Analisis broker • $brokerPart", seedChecked.get() + detailChecked.get(), total)
                    }
                    publish(message, current, total) {
                        it.copy(
                            technicalAttempted = checked.get(), technicalValid = seriesByTicker.size, technicalFailed = technicalFailures.get(),
                            brokerSeedAttempted = seedChecked.get(), brokerSeedValid = seedValid.get(), brokerSeedFailed = seedChecked.get() - seedValid.get(),
                            detailAttempted = detailChecked.get(), detailValid = detailValid.get(), detailFailed = detailChecked.get() - detailValid.get()
                        )
                    }
                }
            }

            suspend fun brokerPipeline(series: MarketSeries) {
                val ticker = series.ticker
                val lastEpoch = series.candles.last().epochSeconds
                // A newer session has already been seen for other tickers: this one is stale and
                // would be excluded anyway, so do not spend broker requests on it.
                if (lastEpoch < latestEpoch.get()) { staleSkipped.incrementAndGet(); return }
                val seed = try {
                    withTimeoutOrNull(BROKER_SEED_TICKER_TIMEOUT_MS) {
                        repo.brokerSeedAnalysis(ticker, series.candles.map { it.epochSeconds })
                    } ?: BrokerAnalysis(false, explanation = listOf("Broker seed timeout"), failureKind = BrokerFailureKind.TIMEOUT)
                } finally { seedChecked.incrementAndGet() }
                seedByTicker[ticker] = seed
                if (!seed.available) { progress(); return }
                seedValid.incrementAndGet()

                val strategies = StrategyEngine.analyze(series, seed).filter { it.potentiallyEligible }.map { it.strategy }.toSet()
                if (strategies.isEmpty()) { progress(); return }
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
                        // Market gates (general lane) and broker history (broker lane) in parallel.
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
                    if (!contextComplete) contextLimited += ticker
                    enrichedByTicker[ticker] = StrategyEngine.analyze(series, broker, market)
                } finally {
                    detailChecked.incrementAndGet()
                    progress()
                }
            }

            var technicalCompleted = true
            var brokerCompleted = true
            coroutineScope {
                val brokerWorkers = launch {
                    brokerCompleted = withTimeoutOrNull(BROKER_STAGE_TIMEOUT_MS) {
                        coroutineScope {
                            repeat(BROKER_TICKER_CONCURRENCY) {
                                launch(Dispatchers.IO) {
                                    for (series in brokerQueue) {
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
                        true
                    } ?: false
                }
                technicalCompleted = withTimeoutOrNull(MARKET_STAGE_TIMEOUT_MS) {
                    val next = AtomicInteger(0)
                    coroutineScope {
                        repeat(minOf(STOCKBIT_CONCURRENCY, universe.size)) {
                            launch(Dispatchers.IO) {
                                while (true) {
                                    val item = universe.getOrNull(next.getAndIncrement()) ?: break
                                    try {
                                        when (val data = withTimeoutOrNull(STOCKBIT_TICKER_TIMEOUT_MS) { repo.screeningSeries(item.ticker, item.companyName) }) {
                                            is DataResult.Success -> {
                                                val series = data.value
                                                seriesByTicker[item.ticker] = series
                                                latestEpoch.accumulateAndGet(series.candles.last().epochSeconds, ::maxOf)
                                                // Upper-bound filter: missing broker criteria count as satisfiable, so
                                                // nothing that could still pass is discarded before broker data arrives.
                                                if (StrategyEngine.analyze(series, noBroker).any { it.potentiallyEligible }) {
                                                    potentialTickers += item.ticker
                                                    brokerQueue.send(series)
                                                }
                                            }
                                            else -> technicalFailures.incrementAndGet()
                                        }
                                    } finally {
                                        checked.incrementAndGet()
                                        progress()
                                    }
                                }
                            }
                        }
                    }
                    true
                } ?: false
                technicalDone.set(true)
                brokerQueue.close()
                progress(force = true)
                brokerWorkers.join()
            }
            if (!technicalCompleted) repo.log("Tahap OHLCVF mencapai batas waktu", "${checked.get()}/${universe.size} selesai", "WARN")
            if (!brokerCompleted) repo.log("Tahap broker Stockbit mencapai batas waktu", "hasil yang selesai ditandai parsial", "WARN")
            if (seriesByTicker.isEmpty()) return fail("Tidak ada ticker dengan minimal 60 sesi OHLCVF Stockbit.", checked.get(), universe.size)

            // ---- Final accounting on active (non-stale) tickers only ----------------------
            val referenceEpoch = seriesByTicker.values.maxOf { it.candles.last().epochSeconds }
            val activeTickers = seriesByTicker.values.filter { it.candles.last().epochSeconds == referenceEpoch }.map { it.ticker }.toSet()
            val staleExcluded = seriesByTicker.size - activeTickers.size
            val activePotential = potentialTickers.filter { it in activeTickers }
            val seedResults = activePotential.mapNotNull { seedByTicker[it] }
            val seedValidCount = seedResults.count { it.available }
            val seedFailedCount = activePotential.size - seedValidCount
            val detailTickers = detailOutcome.keys.filter { it in activeTickers }
            val detailValidCount = detailTickers.count { detailOutcome[it]?.isPresent == false }
            val detailFailedCount = detailTickers.size - detailValidCount
            val enriched = enrichedByTicker.filterKeys { it in activeTickers }.values.flatten()

            val saved = StrategyType.entries.flatMap { StrategyEngine.top(enriched, it, 5) }
            val complete = technicalCompleted && brokerCompleted && checked.get() == universe.size && technicalFailures.get() == 0 &&
                seedResults.size == activePotential.size && seedFailedCount == 0 && detailFailedCount == 0
            val coverage = if (complete) "COMPLETE" else "PARTIAL"
            repo.saveScreening(runId, saved, checked.get(), detailValidCount, coverage)
            val counts = StrategyType.entries.joinToString { strategy -> "${strategy.number}:${saved.count { it.strategy == strategy }}" }
            fun failureSummary(values: List<BrokerFailureKind>): String = values.groupingBy { it }.eachCount().entries
                .sortedBy { it.key.ordinal }
                .joinToString { "${it.key.label} ${it.value}" }
            val notAttempted = activePotential.size - seedResults.size
            val diagnostics = buildList {
                val seedKinds = seedResults.filter { !it.available }.map { it.failureKind ?: BrokerFailureKind.UNKNOWN }
                failureSummary(seedKinds).takeIf { it.isNotBlank() }?.let { add("seed: $it") }
                if (notAttempted > 0) add("seed belum diproses $notAttempted")
                failureSummary(detailTickers.mapNotNull { detailOutcome[it]?.orElse(null) }).takeIf { it.isNotBlank() }?.let { add("detail: $it") }
                val limited = contextLimited.count { it in activeTickers }
                if (limited > 0) add("konteks opsional belum lengkap $limited")
            }.joinToString(" • ")
            val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000
            val duration = "%d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
            val finalMessage = if (complete) "Screening lengkap ($duration). ${saved.size} kandidat valid di 10 strategi."
                else "Screening parsial ($duration). ${saved.size} kandidat valid${if (diagnostics.isBlank()) "." else " • $diagnostics"}"
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
                "$coverage • $duration • ${checked.get()}/${universe.size} OHLCVF • seed $seedValidCount/${activePotential.size} • detail $detailValidCount/${detailTickers.size} • " +
                    "request umum ${traffic.generalCompleted - trafficBefore.generalCompleted}, broker ${traffic.brokerCompleted - trafficBefore.brokerCompleted}, " +
                    "429 ${traffic.rateLimitHits - trafficBefore.rateLimitHits}, challenge ${traffic.challengeHits - trafficBefore.challengeHits}, " +
                    "kosong ${traffic.emptyResponses - trafficBefore.emptyResponses} (pulih ${traffic.emptyRecovered - trafficBefore.emptyRecovered}), " +
                    "jeda broker ${traffic.brokerSpacingMs}ms • Top [$counts]"
            )
            if (traffic.emptyResponses > trafficBefore.emptyResponses) {
                repo.log("Contoh respons broker kosong Stockbit", traffic.lastEmptySample ?: "-", "WARN")
            }
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
        private const val BROKER_SEED_TICKER_TIMEOUT_MS = 3 * 60_000L
        private const val BROKER_TICKER_TIMEOUT_MS = 6 * 60_000L
        private const val MARKET_CONTEXT_TIMEOUT_MS = 60_000L
        private const val BROKER_STAGE_TIMEOUT_MS = 85 * 60_000L
        private const val STOCKBIT_CONNECTION_TIMEOUT_MS = 25_000L
        private const val STOCKBIT_CONCURRENCY = 8
        private const val BROKER_TICKER_CONCURRENCY = 6
        private const val PROGRESS_INTERVAL_MS = 1_200L
        private const val SCREENING_CHANNEL = "lumi_screening"
        private const val SCREENING_NOTIFICATION_ID = 7201
    }
}
