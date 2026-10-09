package com.lumisignal.idxscreener.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lumisignal.idxscreener.LumiApplication
import com.lumisignal.idxscreener.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Restores a user-enabled audit after a reboot; it never enables audit by itself. */
class AuditBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repo = (context.applicationContext as LumiApplication).repository
                val enabled = repo.setting(AppRepository.BACKGROUND_AUDIT_KEY, "false").toBoolean()
                if (enabled && repo.stockbit.hasSession()) {
                    try {
                        ContextCompat.startForegroundService(context, Intent(context, AuditForegroundService::class.java))
                    } catch (e: Throwable) {
                        repo.putSetting(AppRepository.BACKGROUND_AUDIT_KEY, "false")
                        repo.log("Audit gagal dipulihkan setelah boot", e.message, "ERROR")
                    }
                }
            } catch (_: Throwable) {
                // A boot receiver must never take down the application process.
            } finally {
                pending.finish()
            }
        }
    }
}
