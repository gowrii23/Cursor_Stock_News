package com.bseblueprint.screener.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.MetricPoint
import com.bseblueprint.screener.data.NewsItem
import com.bseblueprint.screener.data.ScoreBreakdown
import com.bseblueprint.screener.data.WatchlistItem
import com.bseblueprint.screener.util.JsonSafe
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StockDetailActivity : AppCompatActivity() {

    private val gson = Gson()
    private var shareText: String = ""

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
        val txtScoreBreakdown = findViewById<TextView>(R.id.txtScoreBreakdown)
        val txtHistory = findViewById<TextView>(R.id.txtHistory)
        val txtNews = findViewById<TextView>(R.id.txtNews)
        val chart = findViewById<LineChart>(R.id.priceChart)
        val btnShare = findViewById<MaterialButton>(R.id.btnShare)

        val concallLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                AskAiHelper.onWebViewResult(
                    this,
                    data?.getStringExtra(ConcallScanActivity.EXTRA_TRANSCRIPT_TEXT),
                    data?.getStringExtra(ConcallScanActivity.EXTRA_PDF_BASE64)
                )
            } else {
                AskAiHelper.onWebViewCancelled()
            }
        }

        AskAiHelper.bind(
            activity = this,
            symbolProvider = { ticker },
            btnAsk = findViewById(R.id.btnAskAi),
            btnClear = findViewById(R.id.btnClearAskAi),
            progress = findViewById(R.id.askAiProgress),
            txtStatus = findViewById(R.id.txtAskAiStatus),
            card = findViewById(R.id.askAiCard),
            txtVerdict = findViewById(R.id.txtAiVerdict),
            txtReasoning = findViewById(R.id.txtAiReasoning),
            txtRisk = findViewById(R.id.txtAiRisk),
            txtSources = findViewById(R.id.txtAiSources),
            txtQual = findViewById(R.id.txtAiQual),
            txtDisclaimer = findViewById(R.id.txtAiDisclaimer),
            webViewLauncher = concallLauncher
        )

        btnShare.setOnClickListener {
            if (shareText.isBlank()) return@setOnClickListener
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "$ticker flag")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.action_share_item)))
        }

        lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { PythonBridge.getStockDetail(ticker) }
                val meta = JsonSafe.obj(detail, "meta")
                val name = JsonSafe.string(meta, "name") ?: ticker
                val membership = JsonSafe.string(meta, "index_membership") ?: ""
                txtMeta.text = "$name · $membership"

                val tagsType = object : TypeToken<List<String>>() {}.type
                val tagsArr = JsonSafe.arr(detail, "blueprint_tags")
                val tags: List<String> =
                    if (tagsArr != null) gson.fromJson(tagsArr, tagsType) ?: emptyList() else emptyList()
                txtTags.text = if (tags.isEmpty()) "No personal theme tags" else tags.joinToString(" · ")

                val breakdownType = object : TypeToken<ScoreBreakdown>() {}.type
                val breakdownEl = detail.get("score_breakdown")
                val breakdown: ScoreBreakdown? =
                    if (breakdownEl != null && !breakdownEl.isJsonNull) {
                        gson.fromJson(breakdownEl, breakdownType)
                    } else {
                        null
                    }
                txtScoreBreakdown.text = formatBreakdown(breakdown)

                val metricsType = object : TypeToken<List<MetricPoint>>() {}.type
                val metricsArr = JsonSafe.arr(detail, "metrics")
                val metrics: List<MetricPoint> =
                    if (metricsArr != null) gson.fromJson(metricsArr, metricsType) ?: emptyList() else emptyList()
                bindChart(chart, metrics)

                val histType = object : TypeToken<List<WatchlistItem>>() {}.type
                val histArr = JsonSafe.arr(detail, "watch_history")
                val hist: List<WatchlistItem> =
                    if (histArr != null) gson.fromJson(histArr, histType) ?: emptyList() else emptyList()
                txtHistory.text = if (hist.isEmpty()) {
                    "No prior idiosyncratic flags"
                } else {
                    hist.joinToString("\n") {
                        "${it.date}: z=${fmt(it.z_score)} score=${fmt(it.conviction_score)} ${it.severity_tag}"
                    }
                }

                val newsType = object : TypeToken<List<NewsItem>>() {}.type
                val newsArr = JsonSafe.arr(detail, "news")
                val news: List<NewsItem> =
                    if (newsArr != null) gson.fromJson(newsArr, newsType) ?: emptyList() else emptyList()
                txtNews.text = if (news.isEmpty()) {
                    "No matched headlines"
                } else {
                    news.take(12).joinToString("\n\n") { formatNewsLine(it) }
                }

                val latest = hist.firstOrNull()
                val latestMetric = metrics.lastOrNull()
                supportActionBar?.subtitle = buildString {
                    append("β ${fmt(latest?.beta_1y ?: latestMetric?.beta_1y)}")
                    append(" · α ${fmt(latest?.alpha_1y ?: latestMetric?.alpha_1y)}")
                    append(" · ₹${fmt(latestMetric?.close)}")
                }
                shareText = buildShareText(ticker, latest, breakdown)
            } catch (t: Throwable) {
                txtMeta.text = "Failed to load: ${t.message}"
            }
        }
    }

    private fun formatBreakdown(breakdown: ScoreBreakdown?): String {
        if (breakdown == null) return "No score breakdown for latest flag"
        val c = breakdown.components.orEmpty()
        val lines = listOf(
            "Total: ${fmt(breakdown.total)}",
            "Z-drop: ${fmt(c["z_drop"])}",
            "Alpha pct: ${fmt(c["alpha_percentile"])}",
            "Low beta: ${fmt(c["low_beta"])}",
            "News: ${fmt(c["news_severity"])}",
            "Themes: ${fmt(c["blueprint"])}"
        )
        return lines.joinToString("\n")
    }

    private fun formatNewsLine(item: NewsItem): String {
        val timing = when (item.timing_vs_close) {
            "before_close" -> getString(R.string.timing_before_close)
            "after_close" -> getString(R.string.timing_after_close)
            else -> getString(R.string.timing_unknown)
        }
        return "[${item.severity_tag ?: "?"} · $timing] ${item.headline}"
    }

    private fun buildShareText(ticker: String, item: WatchlistItem?, breakdown: ScoreBreakdown?): String {
        if (item == null) return ticker
        return buildString {
            append(ticker)
            append(" · score ")
            append(fmt(item.conviction_score))
            append(" · ")
            append(item.severity_tag)
            append("\nToday ")
            append(item.daily_return?.let { String.format("%+.1f%%", it * 100) } ?: "—")
            append(" · idio ")
            append(item.idiosyncratic_return?.let { String.format("%+.1f%%", it * 100) } ?: "—")
            append(" · z ")
            append(fmt(item.z_score))
            append("\n")
            append(item.headline ?: "—")
            append("\n\n")
            append(formatBreakdown(breakdown))
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
            color = Color.parseColor("#9EB4C8")
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
        chart.axisLeft.textColor = Color.parseColor("#8B9AAB")
        chart.xAxis.textColor = Color.parseColor("#8B9AAB")
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
