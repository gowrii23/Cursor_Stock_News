package com.bseblueprint.screener

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.work.DailyScreenScheduler
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class ScreenerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        PythonBridge.init(this)
        createNotificationChannel()
        DailyScreenScheduler.ensureScheduled(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screener updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily EOD overreaction screener results"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "bse_screener"
    }
}
