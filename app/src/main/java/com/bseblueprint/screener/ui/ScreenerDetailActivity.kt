package com.bseblueprint.screener.ui

import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenerDetailActivity : AppCompatActivity() {

    private var symbol: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screener_detail)

        symbol = intent.getStringExtra(EXTRA_SYMBOL).orEmpty()
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = symbol

        AskAiHelper.bind(
            activity = this,
            symbolProvider = { symbol },
            btnAsk = findViewById(R.id.btnAskAi),
            progress = findViewById(R.id.askAiProgress),
            txtStatus = findViewById(R.id.txtAskAiStatus),
            card = findViewById(R.id.askAiCard),
            txtVerdict = findViewById(R.id.txtAiVerdict),
            txtReasoning = findViewById(R.id.txtAiReasoning),
            txtRisk = findViewById(R.id.txtAiRisk),
            txtDisclaimer = findViewById(R.id.txtAiDisclaimer)
        )

        loadDetail()
    }

    private fun loadDetail() {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    PythonBridge.getScreenerDetail(symbol)
                }
                bindDetail(json)
            } catch (_: Throwable) {
                findViewById<TextView>(R.id.detailSymbol).text = symbol
            }
        }
    }

    private fun bindDetail(json: JsonObject) {
        val stock = JsonSafe.obj(json, "stock") ?: return
        findViewById<TextView>(R.id.detailSymbol).text =
            JsonSafe.string(stock, "symbol") ?: symbol
        findViewById<TextView>(R.id.detailName).text =
            JsonSafe.string(stock, "name") ?: "—"
        val score = JsonSafe.double(stock, "score_total") ?: 0.0
        val tier = JsonSafe.string(stock, "tier") ?: ""
        val tagsArr = JsonSafe.arr(stock, "blueprint_tags")
        val tags = if (tagsArr != null && tagsArr.size() > 0) {
            (0 until tagsArr.size()).mapNotNull { JsonSafe.string(tagsArr[it]) }
                .joinToString(", ")
        } else ""
        findViewById<TextView>(R.id.detailScore).text =
            if (tags.isNotEmpty()) {
                "${String.format("%.0f", score)}/100 · $tier · Blueprint: $tags"
            } else {
                "${String.format("%.0f", score)}/100 · $tier"
            }

        val breakdown = JsonSafe.obj(stock, "score_breakdown")
        val bdLines = breakdown?.entrySet()?.joinToString("\n") {
            val v = JsonSafe.double(it.value) ?: 0.0
            "  ${it.key}: ${String.format("%.1f", v)}"
        } ?: "—"
        findViewById<TextView>(R.id.detailBreakdown).text = bdLines

        val l3 = JsonSafe.obj(stock, "layer3")
        val signals = JsonSafe.arr(l3, "signals")
        val l3Text = if (signals != null && signals.size() > 0) {
            (0 until signals.size()).mapNotNull { JsonSafe.string(signals[it]) }
                .joinToString("\n") { "• $it" }
                .ifBlank { getString(R.string.screener_layer3_none) }
        } else {
            getString(R.string.screener_layer3_none)
        }
        findViewById<TextView>(R.id.detailLayer3).text = l3Text

        val manual = JsonSafe.arr(stock, "manual_notes")
        val manualText = if (manual != null && manual.size() > 0) {
            (0 until manual.size()).mapNotNull { JsonSafe.string(manual[it]) }
                .joinToString("\n") { "☐ $it" }
                .ifBlank { "—" }
        } else {
            "—"
        }
        findViewById<TextView>(R.id.detailManual).text = manualText

        val raw = JsonSafe.obj(stock, "raw_columns")
        val rawText = raw?.entrySet()?.joinToString("\n") {
            "${it.key}: ${it.value}"
        } ?: "—"
        findViewById<TextView>(R.id.detailRaw).text = rawText

        val check = findViewById<MaterialCheckBox>(R.id.checkVerified)
        check.isChecked = (JsonSafe.int(stock, "user_verified") ?: 0) == 1
        check.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch(Dispatchers.IO) {
                PythonBridge.setScreenerVerified(symbol, isChecked)
            }
        }
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }
}
