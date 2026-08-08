package com.bseblueprint.screener.ui

import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.bridge.RunProgressReporter
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared Ask AI UI helpers for detail screens. */
object AskAiHelper {

    fun bind(
        activity: AppCompatActivity,
        symbolProvider: () -> String,
        btnAsk: MaterialButton,
        progress: ProgressBar,
        txtStatus: TextView,
        card: View,
        txtVerdict: TextView,
        txtReasoning: TextView,
        txtRisk: TextView,
        txtDisclaimer: TextView
    ) {
        txtDisclaimer.text = activity.getString(R.string.ask_ai_disclaimer)
        card.visibility = View.GONE
        progress.visibility = View.GONE
        txtStatus.visibility = View.GONE

        btnAsk.setOnClickListener {
            val symbol = symbolProvider().trim()
            if (symbol.isEmpty()) return@setOnClickListener
            progress.visibility = View.VISIBLE
            txtStatus.visibility = View.VISIBLE
            txtStatus.text = activity.getString(R.string.ask_ai_loading)
            btnAsk.isEnabled = false
            activity.lifecycleScope.launch {
                try {
                    val reporter = RunProgressReporter { _, message ->
                        txtStatus.text = message
                    }
                    val result = withContext(Dispatchers.IO) {
                        PythonBridge.askAiVerdict(
                            symbol,
                            forceRefresh = false,
                            reporter = reporter
                        )
                    }
                    card.visibility = View.VISIBLE
                    val status = JsonSafe.string(result, "status")
                    val verdict = JsonSafe.string(result, "verdict") ?: "ERROR"
                    val confidence = JsonSafe.int(result, "confidence") ?: 0
                    val reasoning = JsonSafe.string(result, "reasoning").orEmpty()
                    val risk = JsonSafe.string(result, "key_risk").orEmpty()
                    val cached = JsonSafe.bool(result, "cached") == true

                    when (status) {
                        "no_token" -> {
                            txtVerdict.text = activity.getString(R.string.ask_ai_no_token)
                            txtReasoning.text = ""
                            txtRisk.text = ""
                        }
                        "unavailable", "error" -> {
                            txtVerdict.text = activity.getString(R.string.ask_ai_unavailable)
                            txtReasoning.text = reasoning
                            txtRisk.text = risk
                        }
                        else -> {
                            val cacheNote = if (cached) {
                                " " + activity.getString(R.string.ask_ai_cached)
                            } else ""
                            txtVerdict.text = "$verdict · $confidence%$cacheNote"
                            txtReasoning.text = reasoning
                            txtRisk.text = if (risk.isBlank()) "" else "Risk: $risk"
                        }
                    }
                } catch (t: Throwable) {
                    card.visibility = View.VISIBLE
                    txtVerdict.text = activity.getString(R.string.ask_ai_unavailable)
                    txtReasoning.text = t.message.orEmpty()
                    txtRisk.text = ""
                } finally {
                    progress.visibility = View.GONE
                    txtStatus.visibility = View.GONE
                    btnAsk.isEnabled = true
                }
            }
        }
    }
}
