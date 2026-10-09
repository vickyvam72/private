package com.lumisignal.idxscreener.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lumisignal.idxscreener.LumiApplication
import com.lumisignal.idxscreener.data.*
import com.lumisignal.idxscreener.engine.ScreeningEngine
import com.lumisignal.idxscreener.model.*
import com.lumisignal.idxscreener.worker.ScreeningWorker
import com.lumisignal.idxscreener.worker.AuditForegroundService
import com.lumisignal.idxscreener.engine.StrategyEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ConnectionUi(
    val marketData: String = "Disconnected",
    val stockbit: String = "Disconnected",
    val brokerSummary: String = "Unknown",
    val runningTrade: String = "Unknown",
    val brokerage: String = "Locked",
    val telegram: String = "Disconnected",
    val stockbitTier: StockbitAccountTier = StockbitAccountTier.UNKNOWN,
    val stockbitTierMessage: String = "Login Stockbit untuk memeriksa akses Pro."
)
data class StoredCredentialsUi(
    val stockbitToken: String? = null,
    val telegramToken: String? = null,
    val telegramChatId: String? = null
)
data class SearchUi(
    val loading: Boolean = false,
    val result: MarketSeries? = null,
    val analyses: List<Candidate> = emptyList(),
    val error: String? = null,
    val warning: String? = null
)
data class WorkUi(val running: Boolean = false, val stage: String = "", val current: Int = 0, val total: Int = 0)
data class PortfolioUi(
    val loading: Boolean = false,
    val snapshot: PortfolioSnapshot? = null,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as LumiApplication).repository
    val signals = repo.signals.catch { emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latest = repo.latestScreening.catch { emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val latestRun = repo.latestScreeningRun.catch { emit(null) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val activity = repo.activity.catch { emit(emptyList()) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val performance = repo.performance.catch { emit(com.lumisignal.idxscreener.engine.PerformanceEngine.calculate(emptyList())) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.lumisignal.idxscreener.engine.PerformanceEngine.calculate(emptyList()))
    private val _connections = MutableStateFlow(ConnectionUi())
    val connections = _connections.asStateFlow()
    private val _storedCredentials = MutableStateFlow(StoredCredentialsUi())
    val storedCredentials = _storedCredentials.asStateFlow()
    private val _search = MutableStateFlow(SearchUi())
    val search = _search.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _pendingResend = MutableStateFlow<String?>(null)
    val pendingResend = _pendingResend.asStateFlow()
    private val _work = MutableStateFlow(WorkUi())
    val work = _work.asStateFlow()
    private val _backgroundAudit = MutableStateFlow(false)
    val backgroundAudit = _backgroundAudit.asStateFlow()
    private val _chart = MutableStateFlow<MarketSeries?>(null)
    val chart = _chart.asStateFlow()
    private val _marketContext = MutableStateFlow<StockbitMarketContext?>(null)
    val marketContext = _marketContext.asStateFlow()
    private val _portfolio = MutableStateFlow(PortfolioUi())
    val portfolio = _portfolio.asStateFlow()
    private var searchJob: Job? = null
    private var currentWorkId: java.util.UUID? = null
    private var notifiedFailureId: java.util.UUID? = null

    init {
        refreshStoredCredentialDisplay()
        viewModelScope.launch {
            try {
                repo.trackLatestCandidates()
                _backgroundAudit.value = repo.setting(AppRepository.BACKGROUND_AUDIT_KEY, "false").toBoolean()
                if (_backgroundAudit.value && repo.stockbit.hasSession()) {
                    startAuditServiceSafely(fromUser = false)
                }
            } catch (e: Throwable) {
                _backgroundAudit.value = false
                try { repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, "false") } catch (_: Throwable) { }
                _message.value = "Audit otomatis dinonaktifkan agar aplikasi tetap dapat dibuka: ${e.message ?: "inisialisasi gagal"}."
            }
        }
        _connections.update {
            it.copy(
                stockbit = if (repo.stockbit.hasSession()) "Checking" else "Disconnected",
                brokerage = if (repo.stockbit.hasSecuritiesSession()) "Checking" else "Locked",
                telegram = if (repo.hasTelegramConfiguration()) "Checking" else "Disconnected"
            )
        }
        if (repo.stockbit.hasSession()) validateStockbit(notify = false)
        if (repo.stockbit.hasSecuritiesSession()) refreshPortfolio(notify = false)
        if (repo.hasTelegramConfiguration()) validateTelegram(notify = false)
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfosForUniqueWorkFlow(WORK_NAME).catch {
                _work.value = WorkUi()
                _message.value = "Status worker Android tidak dapat dibaca: ${it.message ?: "kesalahan sistem"}."
            }.collect { list ->
                val info = currentWorkId?.let { id -> list.firstOrNull { it.id == id } }
                    ?: list.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: list.firstOrNull { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                    ?: list.lastOrNull()
                val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED
                if (currentWorkId == null && running) currentWorkId = info!!.id
                val progress = info?.progress
                val output = info?.outputData
                val fallbackStage = when (info?.state) {
                    WorkInfo.State.ENQUEUED -> "Screening berada dalam antrean Android..."
                    WorkInfo.State.BLOCKED -> "Screening tertahan: menunggu koneksi atau izin Android..."
                    WorkInfo.State.RUNNING -> "Memulai screening..."
                    else -> ""
                }
                _work.value = if (info == null || !running) WorkUi() else WorkUi(true,
                    progress?.getString("stage") ?: output?.getString("stage") ?: fallbackStage,
                    progress?.getInt("current",0) ?: 0, progress?.getInt("total",0) ?: 0)
                if (info?.state == WorkInfo.State.FAILED && currentWorkId == info.id && notifiedFailureId != info.id) {
                    notifiedFailureId = info.id
                    _message.value = info.outputData.getString("stage") ?: "Screening gagal."
                }
            }
        }
    }

    fun startScreening() = viewModelScope.launch {
        if (!repo.stockbit.hasSession()) {
            _message.value = "Login Stockbit wajib sebelum screening."
            return@launch
        }
        try {
            _message.value = "Memeriksa akses Stockbit PRO..."
            val capabilities = repo.stockbit.checkCapabilities()
            applyCapabilities(capabilities)
            if (!capabilities.accountTier.strategiesUnlocked) {
                _message.value = capabilities.accountMessage
                return@launch
            }
            val request = OneTimeWorkRequestBuilder<ScreeningWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(WORK_NAME).build()
            currentWorkId = request.id
            notifiedFailureId = null
            _work.value = WorkUi(true, "Screening berada dalam antrean Android...")
            WorkManager.getInstance(getApplication<Application>()).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            _message.value = null
        } catch (e: Throwable) {
            _work.value = WorkUi()
            _message.value = "Screening tidak dapat dijadwalkan: ${e.message ?: "worker Android gagal"}."
        }
    }

    fun cancelScreening() {
        val manager = WorkManager.getInstance(getApplication<Application>())
        currentWorkId?.let(manager::cancelWorkById) ?: manager.cancelUniqueWork(WORK_NAME)
        _work.value = WorkUi()
        _message.value = "Screening dibatalkan. Hasil screening sebelumnya tetap tersimpan."
    }

    fun searchTicker(value: String) {
        searchJob?.cancel(); searchJob = viewModelScope.launch {
            if (!repo.stockbit.hasSession()) {
                _search.value = SearchUi(error = "Stockbit connection required. Login melalui WebView sebelum menganalisis saham.")
                return@launch
            }
            if (!_connections.value.stockbitTier.strategiesUnlocked) {
                _search.value = SearchUi(error = _connections.value.stockbitTierMessage)
                return@launch
            }
            _marketContext.value = null
            _search.value = SearchUi(loading = true)
            when (val result = repo.stockbit.fetchSeries(value)) {
                is DataResult.Error -> _search.value = SearchUi(error = result.userMessage)
                is DataResult.Success -> {
                    val series = result.value
                    _search.value = SearchUi(loading = true, result = series)
                    repo.stockbit.resetBrokerHealth()
                    val broker = kotlinx.coroutines.withTimeoutOrNull(240_000) {
                        repo.brokerAnalysis(series.ticker, series.candles.last().epochSeconds, 20, series.candles.map { it.epochSeconds })
                    }
                        ?: BrokerAnalysis(false, explanation = listOf("Stockbit broker summary timeout"), failureKind = BrokerFailureKind.TIMEOUT)
                    val market = repo.stockbit.fetchMarketContext(series.ticker)
                    val analyses = StrategyEngine.analyze(series, broker, market)
                    _search.value = if (analyses.size != StrategyType.entries.size) {
                        SearchUi(result = series, error = "Data historis belum cukup untuk analisis lengkap.")
                    } else {
                        SearchUi(
                            result = series,
                            analyses = analyses,
                            warning = if (!broker.available || broker.score == null) {
                                "Broker summary ${series.ticker.removeSuffix(".JK")} belum lengkap (${broker.failureKind?.label ?: "penyebab belum terklasifikasi"}): ${broker.explanation.joinToString("\n").ifBlank { "respons kosong" }}" +
                                    repo.stockbit.trafficStats().let { t ->
                                        (t.lastEmptySample?.let { "\n\nRespons kosong: $it" } ?: "") +
                                        (t.lastOkSample?.let { "\nRespons berisi: $it" } ?: "") +
                                        t.brokerTimeline.takeIf { it.isNotBlank() }?.let { "\nTimeline: $it" }.orEmpty()
                                    } +
                                    "\n\nSkor teknikal tetap ditampilkan untuk diagnosis, tetapi tidak ada strategi yang dinyatakan lolos tanpa bukti broker."
                            } else null
                        )
                    }
                    _marketContext.value = market
                }
            }
        }
    }

    fun loadChart(ticker: String) = viewModelScope.launch {
        _chart.value = null
        _marketContext.value = null
        val r = repo.stockbit.fetchSeries(ticker)
        if (r is DataResult.Success) _chart.value = r.value
        _marketContext.value = repo.stockbit.fetchMarketContext(ticker)
    }

    fun unlockPortfolio(pin: String) = viewModelScope.launch {
        _portfolio.value = PortfolioUi(loading = true)
        _connections.update { it.copy(brokerage = "Checking") }
        when (val result = repo.stockbit.unlockSecurities(pin)) {
            is DataResult.Success -> {
                _connections.update { it.copy(brokerage = "Connected") }
                refreshPortfolio(notify = true)
            }
            is DataResult.Error -> {
                _connections.update { it.copy(brokerage = "Locked") }
                _portfolio.value = PortfolioUi(error = result.userMessage)
                _message.value = result.userMessage
            }
        }
    }

    fun refreshPortfolio(notify: Boolean = true) = viewModelScope.launch {
        if (!repo.stockbit.hasSecuritiesSession()) {
            _connections.update { it.copy(brokerage = "Locked") }
            _portfolio.value = PortfolioUi(error = "Portfolio terkunci. Buka dengan PIN sekuritas.")
            return@launch
        }
        _portfolio.value = _portfolio.value.copy(loading = true, error = null)
        when (val result = repo.stockbit.fetchPortfolio()) {
            is DataResult.Success -> {
                _portfolio.value = PortfolioUi(snapshot = result.value)
                _connections.update { it.copy(brokerage = "Connected") }
                if (notify) _message.value = "Portfolio berhasil disinkronkan secara baca-saja."
            }
            is DataResult.Error -> {
                _portfolio.value = PortfolioUi(error = result.userMessage)
                _connections.update { it.copy(brokerage = if (result.userMessage.contains("kedaluwarsa", true)) "Expired" else "Unavailable") }
                if (notify) _message.value = result.userMessage
            }
        }
    }

    fun lockPortfolio() {
        repo.stockbit.forgetSecurities()
        _portfolio.value = PortfolioUi()
        _connections.update { it.copy(brokerage = "Locked") }
        _message.value = "Sesi Portfolio lokal telah dikunci dan token sekuritas dihapus."
    }

    fun audit() = viewModelScope.launch {
        _message.value = "Audit berjalan..."
        _message.value = repo.auditAll().summary
    }

    fun setBackgroundAudit(enabled: Boolean) = viewModelScope.launch {
        if (enabled && !repo.stockbit.hasSession()) {
            _message.value = "Login Stockbit wajib sebelum audit latar belakang diaktifkan."
            return@launch
        }
        if (enabled && !_connections.value.stockbitTier.strategiesUnlocked) {
            _message.value = _connections.value.stockbitTierMessage
            return@launch
        }
        repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, enabled.toString())
        _backgroundAudit.value = enabled
        val context = getApplication<Application>()
        if (enabled) {
            if (startAuditServiceSafely(fromUser = true)) {
                _message.value = "Audit 1 menit aktif untuk seluruh sinyal dari 10 strategi pada jam pasar BEI."
            }
        } else {
            context.stopService(Intent(context, AuditForegroundService::class.java))
            _message.value = "Audit latar belakang dimatikan."
        }
    }

    fun send(screeningId: String, force: Boolean = false) = viewModelScope.launch {
        when (val result = repo.sendSignal(screeningId, force)) {
            AppRepository.SendOutcome.AlreadySent -> _pendingResend.value = screeningId
            is AppRepository.SendOutcome.Sent -> { _pendingResend.value = null; _message.value = if (result.resent) "Signal dikirim ulang." else "Signal berhasil dikirim dan snapshot disimpan." }
            is AppRepository.SendOutcome.Failed -> _message.value = result.message
        }
    }
    fun cancelResend() { _pendingResend.value = null }

    fun testTelegram(token: String, chat: String) = viewModelScope.launch {
        _message.value = "Menguji Telegram..."
        when (val r = repo.testTelegram(token, chat)) {
            is DataResult.Success -> {
                refreshStoredCredentialDisplay()
                _connections.update { it.copy(telegram="Connected") }
                _message.value="Telegram Connected ✓ Credential tersimpan."
            }
            is DataResult.Error -> {
                _connections.update { it.copy(telegram = if (repo.hasTelegramConfiguration()) "Configured" else "Disconnected") }
                _message.value=r.userMessage
            }
        }
    }

    fun testStockbit(token: String) = viewModelScope.launch {
        _message.value = "Menguji koneksi Stockbit..."
        when (val r=repo.saveAndTestStockbit(token)) {
            is DataResult.Success -> {
                refreshStoredCredentialDisplay()
                val capabilities = repo.stockbit.checkCapabilities()
                applyCapabilities(capabilities)
                _message.value="Stockbit Connected ✓ ${capabilities.accountMessage}"
            }
            is DataResult.Error -> { _connections.update { it.copy(stockbit="Disconnected", marketData="Unavailable") }; _message.value=r.userMessage }
        }
    }

    fun refreshStockbitConnection() = validateStockbit(notify = true)

    private fun validateStockbit(notify: Boolean) = viewModelScope.launch {
        if (!repo.stockbit.hasSession()) {
            _connections.update { it.copy(stockbit = "Disconnected", marketData = "Disconnected") }
            return@launch
        }
        _connections.update { it.copy(stockbit = "Checking") }
        if (notify) _message.value = "Memvalidasi sesi Stockbit..."
        when (val result = repo.stockbit.testConnection()) {
            is DataResult.Success -> {
                val capabilities = repo.stockbit.checkCapabilities()
                applyCapabilities(capabilities)
                if (notify) _message.value = "Stockbit Connected ✓ ${capabilities.accountMessage}"
            }
            is DataResult.Error -> {
                val status = if (result.userMessage.contains("expired", ignoreCase = true)) "Expired" else "Configured"
                _connections.update { it.copy(stockbit = status, marketData = "Unavailable") }
                if (notify) _message.value = result.userMessage
            }
        }
    }

    private fun validateTelegram(notify: Boolean) = viewModelScope.launch {
        if (!repo.hasTelegramConfiguration()) {
            _connections.update { it.copy(telegram = "Disconnected") }
            return@launch
        }
        _connections.update { it.copy(telegram = "Checking") }
        if (notify) _message.value = "Memvalidasi Telegram..."
        when (val result = repo.validateStoredTelegram()) {
            is DataResult.Success -> {
                _connections.update { it.copy(telegram = "Connected") }
                if (notify) _message.value = "Telegram Connected ✓"
            }
            is DataResult.Error -> {
                val invalid = result.userMessage.contains("unauthorized", true) ||
                    result.userMessage.contains("Chat ID", true)
                _connections.update { it.copy(telegram = if (invalid) "Disconnected" else "Configured") }
                if (notify) _message.value = result.userMessage
            }
        }
    }

    fun revalidateTelegram() = validateTelegram(notify = true)

    fun logoutStockbit() = viewModelScope.launch {
        getApplication<Application>().stopService(Intent(getApplication(), AuditForegroundService::class.java))
        repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, "false")
        _backgroundAudit.value = false
        repo.logoutStockbit()
        refreshStoredCredentialDisplay()
        _portfolio.value = PortfolioUi()
        _connections.update { it.copy(stockbit = "Disconnected", marketData = "Disconnected", brokerSummary = "Unknown", runningTrade = "Unknown", brokerage = "Locked", stockbitTier = StockbitAccountTier.UNKNOWN, stockbitTierMessage = "Login Stockbit untuk memeriksa akses Pro.") }
        _message.value = "Stockbit logout. Token lokal telah dihapus."
    }

    fun logoutTelegram() = viewModelScope.launch {
        repo.logoutTelegram()
        refreshStoredCredentialDisplay()
        _connections.update { it.copy(telegram = "Disconnected") }
        _message.value = "Credential Telegram telah dihapus."
    }

    private fun refreshStoredCredentialDisplay() {
        _storedCredentials.value = StoredCredentialsUi(
            stockbitToken = repo.secrets.masked(com.lumisignal.idxscreener.network.StockbitRepository.REFRESH_KEY),
            telegramToken = repo.secrets.masked(AppRepository.TELEGRAM_TOKEN_KEY),
            telegramChatId = repo.secrets.masked(AppRepository.TELEGRAM_CHAT_KEY)
        )
    }

    private fun applyCapabilities(capabilities: StockbitCapabilitySnapshot) {
        _connections.update {
            it.copy(
                stockbit = if (capabilities.marketData == "Connected") "Connected" else it.stockbit,
                marketData = capabilities.marketData,
                brokerSummary = capabilities.brokerSummary,
                runningTrade = capabilities.runningTrade,
                stockbitTier = capabilities.accountTier,
                stockbitTierMessage = capabilities.accountMessage
            )
        }
    }

    private suspend fun startAuditServiceSafely(fromUser: Boolean): Boolean {
        return try {
            val context = getApplication<Application>()
            ContextCompat.startForegroundService(context, Intent(context, AuditForegroundService::class.java))
            true
        } catch (e: Throwable) {
            _backgroundAudit.value = false
            try { repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, "false") } catch (_: Throwable) { }
            repo.log("Audit latar belakang gagal dimulai", e.message, "ERROR")
            if (fromUser) _message.value = "Audit tidak dapat dimulai: ${e.message ?: "izin layanan latar belakang ditolak"}."
            false
        }
    }

    suspend fun exportBackup(): String = repo.exportJson()
    fun restoreBackup(text:String)=viewModelScope.launch { _message.value=runCatching{"${repo.restoreJson(text)} record berhasil dipulihkan."}.getOrElse{"Restore gagal: ${it.message}"} }
    fun resetHistory() = viewModelScope.launch {
        _message.value = "Menghapus history..."
        getApplication<Application>().stopService(Intent(getApplication(), AuditForegroundService::class.java))
        _backgroundAudit.value = false
        val result = runCatching {
            val operation = WorkManager.getInstance(getApplication<Application>()).cancelUniqueWork(WORK_NAME)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { operation.result.get() }
            repo.resetHistory()
        }
        _message.value = result.fold(
            onSuccess = { "Seluruh history, hasil screening, audit, cache, dan settings telah dihapus." },
            onFailure = { "Reset history gagal: ${it.message ?: "database error"}" }
        )
    }
    fun clearMessage() { _message.value=null }

    companion object { const val WORK_NAME="lumi_idx_screening" }
}
