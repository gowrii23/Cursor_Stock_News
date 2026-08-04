package com.bseblueprint.screener.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PattasDetailActivity : AppCompatActivity() {

    private var symbol: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattas_detail)

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
                    PythonBridge.getPattasDetail(symbol)
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

        val pattas = JsonSafe.obj(stock, "pattas")
        val score = JsonSafe.int(stock, "pattas_score")
            ?: JsonSafe.int(pattas, "pattas_score")
            ?: 0
        val pillarCount = JsonSafe.int(stock, "pillar_count")
            ?: JsonSafe.int(pattas, "pillar_count")
            ?: 4
        findViewById<TextView>(R.id.detailScore).text =
            getString(R.string.pattas_stars, score, pillarCount)

        val sector = JsonSafe.string(stock, "sector") ?: JsonSafe.string(pattas, "sector")
        val sectorView = findViewById<TextView>(R.id.detailSector)
        if (!sector.isNullOrBlank()) {
            sectorView.visibility = View.VISIBLE
            sectorView.text = getString(
                if (sector == "financial") R.string.pattas_sector_financial
                else R.string.pattas_sector_non_financial
            )
        } else {
            sectorView.visibility = View.GONE
        }

        val fallback = JsonSafe.bool(stock, "used_basket_fallback")
            ?: JsonSafe.bool(pattas, "used_basket_fallback")
            ?: false
        val fallbackView = findViewById<TextView>(R.id.detailFallback)
        fallbackView.visibility = if (fallback) View.VISIBLE else View.GONE
        if (fallback) {
            fallbackView.text = getString(R.string.pattas_basket_fallback)
        }

        val pillars = JsonSafe.obj(stock, "pillars") ?: JsonSafe.obj(pattas, "pillars")
        val medians = JsonSafe.obj(stock, "peer_medians") ?: JsonSafe.obj(pattas, "peer_medians")
        val isFinancial = sector == "financial"
        findViewById<TextView>(R.id.detailPillars).text =
            formatPillars(stock, pillars, medians, isFinancial)

        val check = findViewById<MaterialCheckBox>(R.id.checkMoatVerified)
        check.isChecked = (JsonSafe.int(stock, "user_moat_verified") ?: 0) == 1
        check.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch(Dispatchers.IO) {
                PythonBridge.setPattasMoatVerified(symbol, isChecked)
            }
        }
    }

    private fun formatPillars(
        stock: JsonObject,
        pillars: JsonObject?,
        medians: JsonObject?,
        isFinancial: Boolean
    ): String {
        fun fieldVal(key: String): String {
            val v = JsonSafe.double(stock, key)
            return v?.let { "%.2f".format(it) } ?: "—"
        }
        fun medVal(key: String): String {
            val v = if (medians != null) JsonSafe.double(medians, key) else null
            return v?.let { "%.2f".format(it) } ?: "—"
        }
        fun mark(key: String): String = when {
            pillars == null -> "?"
            else -> when (JsonSafe.bool(pillars, key)) {
                true -> "✓"
                false -> "✗"
                else -> "?"
            }
        }

        return if (isFinancial) {
            listOf(
                "${mark("pb")} P/B ${fieldVal("pb")} vs ${medVal("pb")}",
                "${mark("div_yield")} Div% ${fieldVal("div_yield")} vs ${medVal("div_yield")}",
                "${mark("net_npa")} NNPA ${fieldVal("net_npa")} vs ${medVal("net_npa")}",
                "${mark("roe_3y")} ROE ${fieldVal("roe_3y")} vs ${medVal("roe_3y")}"
            ).joinToString("\n")
        } else {
            listOf(
                "${mark("pe")} PE ${fieldVal("pe")} vs ${medVal("pe")}",
                "${mark("div_yield")} Div% ${fieldVal("div_yield")} vs ${medVal("div_yield")}",
                "${mark("debt_eq")} D/E ${fieldVal("debt_eq")} vs ${medVal("debt_eq")}",
                "${mark("roe_3y")} ROE ${fieldVal("roe_3y")} vs ${medVal("roe_3y")}",
                "${mark("fcf_yield")} FCF yld vs ${medVal("fcf_yield")}",
                "${mark("growth_consistency")} Growth3y (3Y sales & profit > 0)"
            ).joinToString("\n")
        }
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }
}
