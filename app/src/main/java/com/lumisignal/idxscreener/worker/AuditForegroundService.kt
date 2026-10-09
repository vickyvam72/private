package com.lumisignal.idxscreener.worker

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lumisignal.idxscreener.LumiApplication
import com.lumisignal.idxscreener.MainActivity
import com.lumisignal.idxscreener.R
import com.lumisignal.idxscreener.data.AppRepository
import com.lumisignal.idxscreener.model.SignalStatus
import com.lumisignal.idxscreener.model.DataResult
import kotlinx.coroutines.*
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AuditForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { (application as LumiApplication).repository }

    override fun onCreate() {
        super.onCreate()
        try { createChannels() } catch (_: Throwable) { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, "false");stopSelf() }
            return START_NOT_STICKY
        }
        try {
            createChannels()
            startForeground(ONGOING_ID, ongoing("Menyiapkan audit strategi..."))
        } catch (_: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.coroutineContext.cancelChildren()
        scope.launch {
            try {
                runAuditLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                try { repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, "false") } catch (_: Throwable) { }
                try { repo.log("Audit latar belakang berhenti", e.message, "ERROR") } catch (_: Throwable) { }
                try { notifyEvent("Audit dihentikan", e.message ?: "Layanan audit mengalami kesalahan.") } catch (_: Throwable) { }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun runAuditLoop() {
        val capabilities = if (repo.stockbit.hasSession()) repo.stockbit.checkCapabilities() else null
        if (capabilities?.marketData != "Connected" || !capabilities.accountTier.strategiesUnlocked) {
            notifyEvent("Audit dihentikan", "Login Stockbit wajib untuk mengaktifkan audit.")
            repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, "false")
            stopSelf(); return
        }
        val added = repo.trackLatestCandidates()
        updateOngoing("10 strategi aktif • $added sinyal baru")
        while (currentCoroutineContext().isActive) {
            val cycleStart = System.currentTimeMillis()
            if (MarketClock.isOpen()) {
                repo.trackLatestCandidates()
                val before = repo.db.signalDao().all().associateBy { it.uuid }
                updateOngoing("Audit harga per 1 menit • seluruh strategi")
                // Audit events are local-only. Telegram is reserved for signals
                // explicitly selected by the user through the Send action.
                val report = repo.auditAll()
                val after = repo.db.signalDao().all().associateBy { it.uuid }
                val changed = after.values.mapNotNull { current ->
                    val old = before[current.uuid]
                    if (old != null && (old.status != current.status || old.entryTriggeredPrice == null && current.entryTriggeredPrice != null)) {
                        val event = when (current.status) {
                            SignalStatus.HOLDING -> "ENTRY tersentuh"
                            SignalStatus.TAKE_PROFIT -> "TAKE PROFIT tersentuh"
                            SignalStatus.STOP_LOSS -> "STOP LOSS tersentuh"
                            SignalStatus.EXPIRED -> "Sinyal kedaluwarsa"
                            SignalStatus.AMBIGUOUS -> "TP/SL ambigu"
                            SignalStatus.SUPERSEDED -> null
                            SignalStatus.WAITING_ENTRY -> null
                        }
                        event?.let { current to it }
                    } else null
                }
                changed.groupBy { it.first.ticker }.forEach { (ticker, events) ->
                    val details = events.joinToString(" • ") { (signal, event) ->
                        val strategy = com.lumisignal.idxscreener.model.StrategyType.fromStored(signal.setup)
                        "S${strategy.number} $event"
                    }
                    notifyEvent(ticker.removeSuffix(".JK"), details)
                }
                val heartbeat = ZonedDateTime.now(ZoneId.of("Asia/Jakarta")).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                repo.putSetting(AppRepository.AUDIT_HEARTBEAT_KEY, System.currentTimeMillis().toString())
                updateOngoing("${report.scannedSignals} sinyal • ${report.uniqueTickers} saham • sukses $heartbeat${if(report.failures>0) " • ${report.failures} gagal" else ""}")
            } else {
                updateOngoing("Menunggu sesi BEI • 10 strategi")
            }
            delay((60_000L - (System.currentTimeMillis() - cycleStart)).coerceAtLeast(1_000L))
        }
    }

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 11, Intent(this, AuditForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Lumi Signal • Audit aktif")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Matikan", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateOngoing(text: String) = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ONGOING_ID, ongoing(text))
    private fun notifyEvent(title: String, text: String) {
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(text)
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Audit harga aktif", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_EVENTS, "Entry, TP, dan SL", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.lumisignal.idxscreener.STOP_AUDIT"
        private const val CHANNEL_ONGOING = "lumi_audit_ongoing"
        private const val CHANNEL_EVENTS = "lumi_audit_events"
        private const val ONGOING_ID = 7101
    }
}

object MarketClock {
    private val zone = ZoneId.of("Asia/Jakarta")
    fun isOpen(now: ZonedDateTime = ZonedDateTime.now(zone)): Boolean {
        if (now.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) return false
        val time = now.toLocalTime()
        val friday = now.dayOfWeek == DayOfWeek.FRIDAY
        val morningEnd = if (friday) LocalTime.of(11, 30) else LocalTime.NOON
        val afternoonStart = if (friday) LocalTime.of(14, 0) else LocalTime.of(13, 30)
        val morning = !time.isBefore(LocalTime.of(9, 0)) && time.isBefore(morningEnd)
        val afternoon = !time.isBefore(afternoonStart) && time.isBefore(LocalTime.of(15, 50))
        return morning || afternoon
    }
}
