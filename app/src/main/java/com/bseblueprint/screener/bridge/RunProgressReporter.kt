package com.bseblueprint.screener.bridge

import android.os.Handler
import android.os.Looper

/** Chaquopy-callable progress sink from Python pipeline. */
class RunProgressReporter(
    private val onUpdate: (Int, String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @Suppress("unused")
    fun onProgress(percent: Int, message: String) {
        handler.post { onUpdate(percent.coerceIn(0, 100), message) }
    }
}
