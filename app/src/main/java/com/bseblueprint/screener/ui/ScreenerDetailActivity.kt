package com.bseblueprint.screener.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenerDetailActivity : AppCompatActivity() {

    private val gson = Gson()
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
        val stock = json.getAsJsonObject("stock") ?: return
        findViewById<TextView>(R.id.detailSymbol).text = stock.get("symbol")?.asString
        findViewById<TextView>(R.id.detailName).text = stock.get("name")?.asString
        val score = stock.get("score_total")?.asDouble ?: 0.0
        val tier = stock.get("tier")?.asString ?: ""
        findViewById<TextView>(R.id.detailScore).text =
            "${String.format("%.0f", score)}/100 · $tier"

        val breakdown = stock.getAsJsonObject("score_breakdown")
        val bdLines = breakdown?.entrySet()?.joinToString("\n") {
            "  ${it.key}: ${String.format("%.1f", it.value.asDouble)}"
        } ?: "—"
        findViewById<TextView>(R.id.detailBreakdown).text = bdLines

        val l3 = stock.getAsJsonObject("layer3")
        val signals = l3?.getAsJsonArray("signals")
        val l3Text = if (signals != null && signals.size() > 0) {
            (0 until signals.size()).joinToString("\n") { "• ${signals[it].asString}" }
        } else {
            getString(R.string.screener_layer3_none)
        }
        findViewById<TextView>(R.id.detailLayer3).text = l3Text

        val manual = stock.getAsJsonArray("manual_notes")
        val manualText = if (manual != null && manual.size() > 0) {
            (0 until manual.size()).joinToString("\n") { "☐ ${manual[it].asString}" }
        } else "—"
        findViewById<TextView>(R.id.detailManual).text = manualText

        val raw = stock.getAsJsonObject("raw_columns")
        val rawText = raw?.entrySet()?.joinToString("\n") {
            "${it.key}: ${it.value}"
        } ?: "—"
        findViewById<TextView>(R.id.detailRaw).text = rawText

        val check = findViewById<MaterialCheckBox>(R.id.checkVerified)
        check.isChecked = stock.get("user_verified")?.asInt == 1
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
