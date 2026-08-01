package com.bseblueprint.screener.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SwingDetailActivity : AppCompatActivity() {

    private var symbol: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_swing_detail)

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
                    PythonBridge.getSwingDetail(symbol)
                }
                bindDetail(json)
            } catch (_: Throwable) {
                findViewById<TextView>(R.id.detailSymbol).text = symbol
            }
        }
    }

    private fun bindDetail(json: JsonObject) {
        val hit = JsonSafe.obj(json, "hit") ?: return
        findViewById<TextView>(R.id.detailSymbol).text =
            JsonSafe.string(hit, "symbol") ?: symbol
        findViewById<TextView>(R.id.detailName).text =
            JsonSafe.string(hit, "name") ?: "—"
        val score = JsonSafe.double(hit, "score") ?: 0.0
        val screen = JsonSafe.string(hit, "screen") ?: ""
        val screenLabel = when (screen) {
            "momentum" -> "Momentum First"
            "sleeping" -> "Sleeping Giant"
            else -> screen
        }
        val close = JsonSafe.double(hit, "close")
        findViewById<TextView>(R.id.detailScore).text = buildString {
            append(String.format("%.0f", score))
            append(" · ")
            append(screenLabel)
            if (close != null) append(" · ₹${String.format("%.2f", close)}")
        }

        val signals = JsonSafe.arr(hit, "signals")
        val signalText = if (signals != null && signals.size() > 0) {
            (0 until signals.size()).mapNotNull { JsonSafe.string(signals[it]) }
                .joinToString("\n") { "• $it" }
        } else {
            "—"
        }
        findViewById<TextView>(R.id.detailSignals).text = signalText

        val metrics = JsonSafe.obj(hit, "metrics")
        val metricsText = metrics?.entrySet()?.joinToString("\n") {
            val v = JsonSafe.double(it.value)
            "  ${it.key}: ${if (v != null) String.format("%.2f", v) else it.value}"
        } ?: "—"
        findViewById<TextView>(R.id.detailMetrics).text = metricsText
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }
}
