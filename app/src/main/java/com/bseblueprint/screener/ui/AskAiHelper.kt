package com.bseblueprint.screener.ui

import android.content.Intent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.bridge.RunProgressReporter
import com.bseblueprint.screener.util.AskAiProviderPrefs
import com.bseblueprint.screener.util.AskAiSourceFooter
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared Ask AI UI helpers for detail screens. */
object AskAiHelper {

    fun bind(
        activity: AppCompatActivity,
        symbolProvider: () -> String,
        switchHf: MaterialSwitch,
        switchGemini: MaterialSwitch,
        btnAsk: MaterialButton,
        btnClear: MaterialButton,
        progress: ProgressBar,
        txtStatus: TextView,
        card: View,
        txtVerdict: TextView,
        txtReasoning: TextView,
        txtRisk: TextView,
        txtSources: TextView,
        txtQual: TextView,
        txtDisclaimer: TextView,
        webViewLauncher: ActivityResultLauncher<Intent>
    ) {
        txtDisclaimer.text = activity.getString(R.string.ask_ai_disclaimer)
        card.visibility = View.GONE
        progress.visibility = View.GONE
        txtStatus.visibility = View.GONE

        switchHf.isChecked = AskAiProviderPrefs.isHfEnabled(activity)
        switchGemini.isChecked = AskAiProviderPrefs.isGeminiEnabled(activity)
        switchHf.setOnCheckedChangeListener { _, checked ->
            AskAiProviderPrefs.setHfEnabled(activity, checked)
            updateAskButtons(activity, switchHf, switchGemini, btnAsk, btnClear)
        }
        switchGemini.setOnCheckedChangeListener { _, checked ->
            AskAiProviderPrefs.setGeminiEnabled(activity, checked)
            updateAskButtons(activity, switchHf, switchGemini, btnAsk, btnClear)
        }
        updateAskButtons(activity, switchHf, switchGemini, btnAsk, btnClear)

        btnClear.setOnClickListener {
            val symbol = symbolProvider().trim()
            if (symbol.isEmpty()) return@setOnClickListener
            runAskAi(activity, symbol, forceRefresh = true, clearFirst = true,
                symbolProvider, switchHf, switchGemini, btnAsk, btnClear, progress, txtStatus, card,
                txtVerdict, txtReasoning, txtRisk, txtSources, txtQual, webViewLauncher)
        }

        btnAsk.setOnClickListener {
            val symbol = symbolProvider().trim()
            if (symbol.isEmpty()) return@setOnClickListener
            runAskAi(activity, symbol, forceRefresh = false, clearFirst = false,
                symbolProvider, switchHf, switchGemini, btnAsk, btnClear, progress, txtStatus, card,
                txtVerdict, txtReasoning, txtRisk, txtSources, txtQual, webViewLauncher)
        }
    }

    private fun updateAskButtons(
        activity: AppCompatActivity,
        switchHf: MaterialSwitch,
        switchGemini: MaterialSwitch,
        btnAsk: MaterialButton,
        btnClear: MaterialButton
    ) {
        val anyOn = switchHf.isChecked || switchGemini.isChecked
        btnAsk.isEnabled = anyOn
        btnClear.isEnabled = anyOn
        btnAsk.alpha = if (anyOn) 1f else 0.5f
        btnClear.alpha = if (anyOn) 1f else 0.5f
        if (!anyOn) {
            btnAsk.contentDescription = activity.getString(R.string.ask_ai_both_off_hint)
        }
    }

    private fun runAskAi(
        activity: AppCompatActivity,
        symbol: String,
        forceRefresh: Boolean,
        clearFirst: Boolean,
        symbolProvider: () -> String,
        switchHf: MaterialSwitch,
        switchGemini: MaterialSwitch,
        btnAsk: MaterialButton,
        btnClear: MaterialButton,
        progress: ProgressBar,
        txtStatus: TextView,
        card: View,
        txtVerdict: TextView,
        txtReasoning: TextView,
        txtRisk: TextView,
        txtSources: TextView,
        txtQual: TextView,
        webViewLauncher: ActivityResultLauncher<Intent>
    ) {
        if (!switchHf.isChecked && !switchGemini.isChecked) {
            card.visibility = View.VISIBLE
            txtVerdict.text = activity.getString(R.string.ask_ai_both_off_hint)
            txtReasoning.text = ""
            txtRisk.text = ""
            txtSources.text = ""
            txtQual.visibility = View.GONE
            return
        }
        val useHf = switchHf.isChecked
        val useGemini = switchGemini.isChecked
        progress.visibility = View.VISIBLE
        txtStatus.visibility = View.VISIBLE
        txtStatus.text = activity.getString(R.string.ask_ai_loading)
        btnAsk.isEnabled = false
        btnClear.isEnabled = false
        activity.lifecycleScope.launch {
            try {
                if (clearFirst) {
                    withContext(Dispatchers.IO) { PythonBridge.clearAiCache(symbol) }
                }
                val reporter = RunProgressReporter { _, message ->
                    txtStatus.text = message
                }
                var result = withContext(Dispatchers.IO) {
                    PythonBridge.askAiVerdict(
                        symbol,
                        forceRefresh = forceRefresh || clearFirst,
                        reporter = reporter,
                        useHf = useHf,
                        useGemini = useGemini
                    )
                }
                if (JsonSafe.string(result, "status") == "needs_webview") {
                    val url = JsonSafe.string(result, "transcript_url").orEmpty()
                    if (url.isNotBlank()) {
                        txtStatus.text = activity.getString(R.string.concall_scan_loading)
                        val intent = Intent(activity, ConcallScanActivity::class.java)
                            .putExtra(ConcallScanActivity.EXTRA_URL, url)
                        pendingWebViewAsk = PendingWebViewAsk(
                            symbol, forceRefresh || clearFirst, useHf, useGemini, reporter,
                            btnAsk, btnClear, progress, txtStatus, card,
                            txtVerdict, txtReasoning, txtRisk, txtSources, txtQual
                        )
                        webViewLauncher.launch(intent)
                        return@launch
                    }
                }
                showResult(activity, result, card, txtVerdict, txtReasoning, txtRisk, txtSources, txtQual)
            } catch (t: Throwable) {
                card.visibility = View.VISIBLE
                txtVerdict.text = activity.getString(R.string.ask_ai_unavailable)
                txtReasoning.text = t.message.orEmpty()
                txtRisk.text = ""
                txtSources.text = ""
                txtQual.visibility = View.GONE
            } finally {
                if (pendingWebViewAsk?.symbol != symbol) {
                    progress.visibility = View.GONE
                    txtStatus.visibility = View.GONE
                    btnAsk.isEnabled = true
                    btnClear.isEnabled = true
                }
            }
        }
    }

        fun onWebViewCancelled() {
        val pending = pendingWebViewAsk ?: return
        pendingWebViewAsk = null
        pending.progress.visibility = View.GONE
        pending.txtStatus.visibility = View.GONE
        pending.btnAsk.isEnabled = true
        pending.btnClear.isEnabled = true
    }

    private var pendingWebViewAsk: PendingWebViewAsk? = null

    fun onWebViewResult(
        activity: AppCompatActivity,
        transcriptText: String?,
        pdfBase64: String?
    ) {
        val pending = pendingWebViewAsk ?: return
        pendingWebViewAsk = null
        activity.lifecycleScope.launch {
            try {
                pending.txtStatus.text = activity.getString(R.string.ask_ai_loading)
                val result = withContext(Dispatchers.IO) {
                    PythonBridge.askAiVerdict(
                        pending.symbol,
                        forceRefresh = true,
                        reporter = pending.reporter,
                        webviewTranscriptText = transcriptText.orEmpty(),
                        webviewPdfBase64 = pdfBase64.orEmpty(),
                        useHf = pending.useHf,
                        useGemini = pending.useGemini
                    )
                }
                showResult(
                    activity, result, pending.card, pending.txtVerdict, pending.txtReasoning,
                    pending.txtRisk, pending.txtSources, pending.txtQual
                )
            } catch (t: Throwable) {
                pending.card.visibility = View.VISIBLE
                pending.txtVerdict.text = activity.getString(R.string.ask_ai_unavailable)
                pending.txtReasoning.text = t.message.orEmpty()
            } finally {
                pending.progress.visibility = View.GONE
                pending.txtStatus.visibility = View.GONE
                pending.btnAsk.isEnabled = true
                pending.btnClear.isEnabled = true
            }
        }
    }

    private fun showResult(
        activity: AppCompatActivity,
        result: JsonObject,
        card: View,
        txtVerdict: TextView,
        txtReasoning: TextView,
        txtRisk: TextView,
        txtSources: TextView,
        txtQual: TextView
    ) {
        card.visibility = View.VISIBLE
        val status = JsonSafe.string(result, "status")
        val verdict = JsonSafe.string(result, "verdict") ?: "ERROR"
        val confidence = JsonSafe.int(result, "confidence") ?: 0
        val reasoning = JsonSafe.string(result, "reasoning").orEmpty()
        val risk = JsonSafe.string(result, "key_risk").orEmpty()
        val cached = JsonSafe.bool(result, "cached") == true
        val sources = result.getAsJsonObject("sources_used")
        val qual = result.getAsJsonObject("qual_context")
        val footer = AskAiSourceFooter.format(sources)
        val qualText = AskAiSourceFooter.formatQualContext(qual)

        when (status) {
            "no_token" -> {
                txtVerdict.text = activity.getString(R.string.ask_ai_no_token)
                txtReasoning.text = ""
                txtRisk.text = ""
                txtSources.text = ""
                txtQual.visibility = View.GONE
            }
            "hf_disabled" -> {
                txtVerdict.text = activity.getString(R.string.ask_ai_hf_disabled)
                txtReasoning.text = reasoning
                txtRisk.text = ""
                txtSources.text = footer
                bindQual(txtQual, qualText)
            }
            "gemini_only" -> {
                txtVerdict.text = activity.getString(R.string.ask_ai_gemini_only)
                txtReasoning.text = reasoning
                txtRisk.text = risk
                txtSources.text = footer
                bindQual(txtQual, qualText)
            }
            "unavailable", "error", "needs_webview" -> {
                txtVerdict.text = activity.getString(R.string.ask_ai_unavailable)
                txtReasoning.text = reasoning
                txtRisk.text = risk
                txtSources.text = footer
                bindQual(txtQual, qualText)
            }
            else -> {
                val cacheNote = if (cached) {
                    " " + activity.getString(R.string.ask_ai_cached)
                } else ""
                txtVerdict.text = "$verdict · $confidence%$cacheNote"
                txtReasoning.text = reasoning
                txtRisk.text = if (risk.isBlank()) "" else "Risk: $risk"
                txtSources.text = footer
                bindQual(txtQual, qualText)
            }
        }
    }

    private fun bindQual(txtQual: TextView, qualText: String) {
        if (qualText.isBlank()) {
            txtQual.visibility = View.GONE
        } else {
            txtQual.visibility = View.VISIBLE
            txtQual.text = qualText
        }
    }

    private data class PendingWebViewAsk(
        val symbol: String,
        val forceRefresh: Boolean,
        val useHf: Boolean,
        val useGemini: Boolean,
        val reporter: RunProgressReporter,
        val btnAsk: MaterialButton,
        val btnClear: MaterialButton,
        val progress: ProgressBar,
        val txtStatus: TextView,
        val card: View,
        val txtVerdict: TextView,
        val txtReasoning: TextView,
        val txtRisk: TextView,
        val txtSources: TextView,
        val txtQual: TextView
    )
}
