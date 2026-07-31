package com.bseblueprint.screener.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bseblueprint.screener.R
import com.bseblueprint.screener.ScreenerApp
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.ui.MainActivity
import java.util.concurrent.TimeUnit

class DailyScreenWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            PythonBridge.init(applicationContext)
            val result = PythonBridge.runDailyScreen(useLive = true, forceDemo = false)
            val status = result.get("status")?.asString ?: "error"
            if (status != "ok" && status != "partial") {
                return Result.retry()
            }
            val flagged = result.get("flagged_count")?.asInt ?: 0
            notifyResult(flagged)
            Result.success()
        } catch (t: Throwable) {
            t.printStackTrace()
            Result.retry()
        }
    }

    private fun notifyResult(flagged: Int) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, ScreenerApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BSE Blueprint Screener")
            .setContentText(
                if (flagged > 0) "$flagged overreaction candidate(s) today"
                else "Daily screen complete — no flags"
            )
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(1001, notif)
    }
}

object DailyScreenScheduler {
    private const val UNIQUE = "daily_eod_screen"

    fun ensureScheduled(context: Context) {
        val prefs = context.getSharedPreferences("screener_prefs", Context.MODE_PRIVATE)
        val requireWifi = prefs.getBoolean("require_wifi", true)
        val requireCharging = prefs.getBoolean("require_charging", false)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresCharging(requireCharging)
            .build()

        val request = PeriodicWorkRequestBuilder<DailyScreenWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun runNow(context: Context) {
        val request = androidx.work.OneTimeWorkRequestBuilder<DailyScreenWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
