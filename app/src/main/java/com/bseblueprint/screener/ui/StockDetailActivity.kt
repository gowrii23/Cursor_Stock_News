package com.bseblueprint.screener.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.MetricPoint
import com.bseblueprint.screener.data.NewsItem
import com.bseblueprint.screener.data.WatchlistItem
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StockDetailActivity : AppCompatActivity() {

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stock_detail)

        val ticker = intent.getStringExtra(EXTRA_TICKER) ?: run {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ticker
        toolbar.setNavigationOnClickListener { finish() }

        val txtMeta = findViewById<TextView>(R.id.txtMeta)
        val txtTags = findViewById<TextView>(R.id.txtTags)
        val txtHistory = findViewById<TextView>(R.id.txtHistory)
        val txtNews = findViewById<TextView>(R.id.txtNews)
        val chart = findViewById<LineChart>(R.id.priceChart)

        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { PythonBridge.getStockDetail(ticker) }
                val meta = detail.getAsJsonObject("meta")
                val name = meta?.get("name")?.asString ?: ticker
                val membership = meta?.get("index_membership")?.asString ?: ""
                txtMeta.text = "$name · $membership"

                val tagsType = object : TypeToken<List<String>>() {}.type
                val tags: List<String> = gson.fromJson(detail.getAsJsonArray("blueprint_tags"), tagsType) ?: emptyList()
                txtTags.text = if (tags.isEmpty()) "No BSE Blueprint tags" else tags.joinToString(" · ")

                val metricsType = object : TypeToken<List<MetricPoint>>() {}.type
                val metrics: List<MetricPoint> =
                    gson.fromJson(detail.getAsJsonArray("metrics"), metricsType) ?: emptyList()
                bindChart(chart, metrics)

                val histType = object : TypeToken<List<WatchlistItem>>() {}.type
                val hist: List<WatchlistItem> =
                    gson.fromJson(detail.getAsJsonArray("watch_history"), histType) ?: emptyList()
                txtHistory.text = if (hist.isEmpty()) {
                    "No prior idiosyncratic flags"
                } else {
                    hist.joinToString("\n") {
                        "${it.date}: z=${fmt(it.z_score)} score=${fmt(it.conviction_score)} ${it.severity_tag}"
                    }
                }

                val newsType = object : TypeToken<List<NewsItem>>() {}.type
                val news: List<NewsItem> =
                    gson.fromJson(detail.getAsJsonArray("news"), newsType) ?: emptyList()
                txtNews.text = if (news.isEmpty()) {
                    "No matched headlines"
                } else {
                    news.take(12).joinToString("\n\n") {
                        "[${it.severity_tag ?: "?"}] ${it.headline}"
                    }
                }

                val latest = metrics.lastOrNull()
                supportActionBar?.subtitle = buildString {
                    append("β ${fmt(latest?.beta_1y)}")
                    append(" · α ${fmt(latest?.alpha_1y)}")
                    append(" · ₹${fmt(latest?.close)}")
                }
            } catch (t: Throwable) {
                txtMeta.text = "Failed to load: ${t.message}"
            }
        }
    }

    private fun bindChart(chart: LineChart, metrics: List<MetricPoint>) {
        val entries = metrics.mapIndexedNotNull { idx, m ->
            m.close?.let { Entry(idx.toFloat(), it.toFloat()) }
        }
        if (entries.isEmpty()) {
            chart.clear()
            chart.setNoDataText("No price history yet — run a screen first")
            return
        }
        val set = LineDataSet(entries, "Close").apply {
            color = Color.parseColor("#0D9488")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        chart.data = LineData(set)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawLabels(false)
        chart.axisLeft.textColor = Color.parseColor("#94A3B8")
        chart.xAxis.textColor = Color.parseColor("#94A3B8")
        chart.setBackgroundColor(Color.TRANSPARENT)
        chart.setTouchEnabled(true)
        chart.animateX(700)
        chart.invalidate()
    }

    private fun fmt(v: Double?): String =
        if (v == null) "—" else String.format("%.2f", v)

    companion object {
        const val EXTRA_TICKER = "ticker"
    }
}
