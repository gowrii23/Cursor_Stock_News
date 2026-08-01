package com.bseblueprint.screener.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.bridge.RunProgressReporter
import com.bseblueprint.screener.data.ScreenerStock
import com.bseblueprint.screener.data.SwingHit
import com.bseblueprint.screener.data.WatchlistItem
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity(),
    HomeFragment.Callback,
    ScreenerFragment.Callback,
    SwingFragment.Callback,
    RunProgressBottomSheet.Listener {

    private lateinit var subtitle: TextView
    private lateinit var bottomNav: BottomNavigationView
    private val gson = Gson()
    private var homeFragment: HomeFragment? = null
    private var newsFragment: NewsFragment? = null
    private var screenerFragment: ScreenerFragment? = null
    private var swingFragment: SwingFragment? = null
    private var runSheet: RunProgressBottomSheet? = null
    private var screenerRunActive = false
    private var swingRunActive = false

    private val screenerScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(ScreenerScanActivity.EXTRA_ROWS_JSON)
            if (!json.isNullOrBlank()) {
                processScreenerRows(json)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        subtitle = findViewById(R.id.subtitle)
        bottomNav = findViewById(R.id.bottomNav)

        if (savedInstanceState == null) {
            homeFragment = HomeFragment()
            newsFragment = NewsFragment()
            screenerFragment = ScreenerFragment()
            swingFragment = SwingFragment()
            val fm = supportFragmentManager
            fm.beginTransaction()
                .add(R.id.fragmentContainer, swingFragment!!, TAG_SWING)
                .hide(swingFragment!!)
                .add(R.id.fragmentContainer, screenerFragment!!, TAG_SCREENER)
                .hide(screenerFragment!!)
                .add(R.id.fragmentContainer, newsFragment!!, TAG_NEWS)
                .hide(newsFragment!!)
                .add(R.id.fragmentContainer, homeFragment!!, TAG_HOME)
                .commit()
        } else {
            homeFragment = supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment
            newsFragment = supportFragmentManager.findFragmentByTag(TAG_NEWS) as? NewsFragment
            screenerFragment = supportFragmentManager.findFragmentByTag(TAG_SCREENER) as? ScreenerFragment
            swingFragment = supportFragmentManager.findFragmentByTag(TAG_SWING) as? SwingFragment
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showTab(TAG_HOME)
                    supportActionBar?.title = getString(R.string.tab_home)
                    subtitle.visibility = View.VISIBLE
                    true
                }
                R.id.nav_news -> {
                    showTab(TAG_NEWS)
                    supportActionBar?.title = getString(R.string.tab_news)
                    subtitle.visibility = View.VISIBLE
                    newsFragment?.loadNews()
                    true
                }
                R.id.nav_screener -> {
                    showTab(TAG_SCREENER)
                    supportActionBar?.title = getString(R.string.tab_screener)
                    subtitle.visibility = View.GONE
                    screenerFragment?.let { onScreenerLoad() }
                    true
                }
                R.id.nav_swing -> {
                    showTab(TAG_SWING)
                    supportActionBar?.title = getString(R.string.tab_swing)
                    subtitle.visibility = View.GONE
                    swingFragment?.let { onSwingLoad() }
                    true
                }
                else -> false
            }
        }
        bottomNav.selectedItemId = R.id.nav_home
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val tab = bottomNav.selectedItemId
        val onScreener = tab == R.id.nav_screener
        val onSwing = tab == R.id.nav_swing
        menu.findItem(R.id.action_share)?.isVisible = !onScreener && !onSwing
        menu.findItem(R.id.action_run)?.title = when {
            onScreener -> getString(R.string.screener_run_scan)
            onSwing -> getString(R.string.swing_run)
            else -> getString(R.string.action_run_screen)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_run -> {
                when (bottomNav.selectedItemId) {
                    R.id.nav_screener -> startScreenerScan()
                    R.id.nav_swing -> startSwingRun()
                    else -> startRunWithProgress()
                }
                true
            }
            R.id.action_share -> {
                shareSummary(homeFragment?.getActionableItems().orEmpty())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showTab(tag: String) {
        val home = homeFragment ?: return
        val news = newsFragment ?: return
        val screener = screenerFragment ?: return
        val swing = swingFragment ?: return
        val tx = supportFragmentManager.beginTransaction()
        tx.hide(home).hide(news).hide(screener).hide(swing)
        when (tag) {
            TAG_HOME -> tx.show(home)
            TAG_NEWS -> tx.show(news)
            TAG_SCREENER -> tx.show(screener)
            TAG_SWING -> tx.show(swing)
        }
        tx.commit()
    }

    // --- Home callbacks ---
    override fun onWatchlistItemClick(item: WatchlistItem) {
        if (item.ticker == "[TEST]") {
            Toast.makeText(this, R.string.demo_ticker_toast, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, StockDetailActivity::class.java)
                .putExtra(StockDetailActivity.EXTRA_TICKER, item.ticker)
        )
    }

    override fun onWatchlistItemShare(item: WatchlistItem) {
        shareSummary(listOf(item))
    }

    override fun onRefreshRequested() = startRunWithProgress()

    override fun onLoadDashboard(seedIfEmpty: Boolean) = loadDashboard(seedIfEmpty)

    // --- Screener callbacks ---
    override fun onScreenerRunScan() = startScreenerScan()

    override fun onScreenerStockClick(stock: ScreenerStock) {
        startActivity(
            Intent(this, ScreenerDetailActivity::class.java)
                .putExtra(ScreenerDetailActivity.EXTRA_SYMBOL, stock.symbol)
        )
    }

    override fun onScreenerLoad() = loadScreener()

    override fun onSwingRun() = startSwingRun()

    override fun onSwingHitClick(hit: SwingHit) {
        startActivity(
            Intent(this, SwingDetailActivity::class.java)
                .putExtra(SwingDetailActivity.EXTRA_SYMBOL, hit.symbol)
        )
    }

    override fun onSwingLoad() = loadSwing()

    override fun onRunDismissed() {
        runSheet = null
        when {
            bottomNav.selectedItemId == R.id.nav_swing || swingRunActive -> {
                swingRunActive = false
                loadSwing()
            }
            bottomNav.selectedItemId == R.id.nav_screener || screenerRunActive -> {
                screenerRunActive = false
                loadScreener()
            }
            else -> {
                loadDashboard(seedIfEmpty = false)
                newsFragment?.loadNews()
            }
        }
    }

    private fun startScreenerScan() {
        screenerScanLauncher.launch(Intent(this, ScreenerScanActivity::class.java))
    }

    private fun processScreenerRows(rowsJson: String) {
        screenerRunActive = true
        val sheet = RunProgressBottomSheet.newInstance(getString(R.string.screener_progress_title)).also {
            it.listener = this
            runSheet = it
        }
        sheet.show(supportFragmentManager, RunProgressBottomSheet.TAG)

        lifecycleScope.launch {
            try {
                val reporter = RunProgressReporter { percent, message ->
                    runSheet?.updateProgress(percent, message)
                }
                val result = withContext(Dispatchers.IO) {
                    PythonBridge.processScreenerCapture(rowsJson, reporter)
                }
                val status = JsonSafe.string(result, "status") ?: "error"
                val high = JsonSafe.int(result, "high_count") ?: 0
                val watch = JsonSafe.int(result, "watch_count") ?: 0
                val low = JsonSafe.int(result, "low_count") ?: 0
                val l1 = JsonSafe.int(result, "passed_l1") ?: 0
                val success = status == "ok"
                runSheet?.markComplete(
                    success,
                    "Scan done — $high high (80+) · $watch watch · $low low · $l1 L1 pass"
                )
                if (success) {
                    loadScreener()
                }
            } catch (t: Throwable) {
                runSheet?.markComplete(false, "Scan failed: ${t.message}")
            }
        }
    }

    private fun startSwingRun() {
        swingRunActive = true
        val sheet = RunProgressBottomSheet.newInstance(getString(R.string.swing_progress_title)).also {
            it.listener = this
            runSheet = it
        }
        sheet.show(supportFragmentManager, RunProgressBottomSheet.TAG)

        lifecycleScope.launch {
            try {
                val reporter = RunProgressReporter { percent, message ->
                    runSheet?.updateProgress(percent, message)
                }
                val result = withContext(Dispatchers.IO) {
                    PythonBridge.runSwingScreen(reporter)
                }
                val status = JsonSafe.string(result, "status") ?: "error"
                val momentum = JsonSafe.int(result, "momentum_count") ?: 0
                val sleeping = JsonSafe.int(result, "sleeping_count") ?: 0
                val hits = JsonSafe.int(result, "hit_count") ?: 0
                val asOf = JsonSafe.string(result, "as_of") ?: ""
                val priced = JsonSafe.int(result, "priced_count") ?: 0
                val universe = JsonSafe.int(result, "universe_size") ?: 0
                val success = status == "ok"
                val asOfBit = if (asOf.isNotBlank()) " · as of $asOf" else ""
                runSheet?.markComplete(
                    success,
                    "Done — $hits ranked$asOfBit · $priced/$universe priced · $momentum mom · $sleeping sleep"
                )
                if (success) {
                    loadSwing()
                }
            } catch (t: Throwable) {
                runSheet?.markComplete(false, "Swing screen failed: ${t.message}")
            }
        }
    }

    private fun loadSwing() {
        swingFragment?.setLoading(true)
        lifecycleScope.launch {
            try {
                val dash = withContext(Dispatchers.IO) { PythonBridge.getSwingDashboard() }
                val state = SwingJsonParser.parseDashboard(dash, getString(R.string.swing_empty))
                val run = JsonSafe.obj(dash, "run")
                val at = JsonSafe.string(run, "run_at")
                val meta = if (!at.isNullOrBlank()) {
                    "${state.metaLine} · ${formatIstTime(at)}"
                } else {
                    state.metaLine
                }
                swingFragment?.bindState(state.copy(metaLine = meta))
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "Load failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                swingFragment?.setLoading(false)
            }
        }
    }

    private fun loadScreener() {
        screenerFragment?.setLoading(true)
        lifecycleScope.launch {
            try {
                val dash = withContext(Dispatchers.IO) { PythonBridge.getScreenerDashboard() }
                val state = ScreenerJsonParser.parseDashboard(
                    dash,
                    getString(R.string.screener_empty)
                )
                val scan = JsonSafe.obj(dash, "scan")
                val at = JsonSafe.string(scan, "scanned_at")
                val meta = if (!at.isNullOrBlank()) {
                    "${state.metaLine} · ${formatIstTime(at)}"
                } else {
                    state.metaLine
                }
                screenerFragment?.bindState(state.copy(metaLine = meta))
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "Load failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                screenerFragment?.setLoading(false)
            }
        }
    }

    private fun loadDashboard(seedIfEmpty: Boolean) {
        homeFragment?.setLoading(true)
        lifecycleScope.launch {
            try {
                val dash = withContext(Dispatchers.IO) { PythonBridge.getDashboard() }
                val type = object : TypeToken<List<WatchlistItem>>() {}.type
                val arr = JsonSafe.arr(dash, "watchlist")
                var items: List<WatchlistItem> =
                    if (arr != null) gson.fromJson(arr, type) ?: emptyList() else emptyList()
                if (items.isEmpty() && seedIfEmpty) {
                    subtitle.text = "Seeding demo screen…"
                    withContext(Dispatchers.IO) {
                        PythonBridge.runDailyScreen(useLive = false, forceDemo = true)
                    }
                    val dash2 = withContext(Dispatchers.IO) { PythonBridge.getDashboard() }
                    val arr2 = JsonSafe.arr(dash2, "watchlist")
                    items = if (arr2 != null) gson.fromJson(arr2, type) ?: emptyList() else emptyList()
                    updateSubtitle(dash2, items)
                } else {
                    updateSubtitle(dash, items)
                }
                homeFragment?.bindItems(items)
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "Load failed: ${t.message}", Toast.LENGTH_LONG).show()
                homeFragment?.showEmpty()
            } finally {
                homeFragment?.setLoading(false)
                homeFragment?.setRefreshing(false)
            }
        }
    }

    private fun startRunWithProgress() {
        if (bottomNav.selectedItemId != R.id.nav_home) {
            bottomNav.selectedItemId = R.id.nav_home
            showTab(TAG_HOME)
            supportActionBar?.title = getString(R.string.tab_home)
            subtitle.visibility = View.VISIBLE
        }

        val sheet = RunProgressBottomSheet.newInstance().also {
            it.listener = this
            runSheet = it
        }
        sheet.show(supportFragmentManager, RunProgressBottomSheet.TAG)

        lifecycleScope.launch {
            try {
                val reporter = RunProgressReporter { percent, message ->
                    runSheet?.updateProgress(percent, message)
                }
                val result = withContext(Dispatchers.IO) {
                    PythonBridge.runDailyScreen(useLive = true, forceDemo = false, reporter = reporter)
                }
                val mode = JsonSafe.string(result, "mode") ?: "?"
                val flagged = JsonSafe.int(result, "flagged_count") ?: 0
                val status = JsonSafe.string(result, "status") ?: "error"
                val success = status == "ok" || status == "partial"
                runSheet?.markComplete(success, "Finished ($mode): $flagged flag(s)")
            } catch (t: Throwable) {
                runSheet?.markComplete(false, "Run failed: ${t.message}")
            }
        }
    }

    private fun updateSubtitle(dash: JsonObject, items: List<WatchlistItem>) {
        val run = JsonSafe.obj(dash, "latest_run")
        val health = JsonSafe.obj(dash, "data_health")
        val mode = JsonSafe.string(health, "mode")
            ?: JsonSafe.string(run, "message")?.let { parseMode(it) }
            ?: "demo"
        val actionable = items.count { it.severity_tag == "CANDIDATE" }
        val date = items.firstOrNull()?.date ?: "—"
        val finished = formatIstTime(JsonSafe.string(run, "finished_at"))
        val modeLabel = when (mode) {
            "live" -> getString(R.string.subtitle_live_news)
            "cached" -> "Cached run · showing last good screen"
            else -> getString(R.string.subtitle_demo_news)
        }
        val bhavcopy = healthLabel(JsonSafe.string(health, "bhavcopy"))
        val pulse = healthLabel(JsonSafe.string(health, "pulse"))
        val nse = healthLabel(JsonSafe.string(health, "nse"))
        val gnews = healthLabel(JsonSafe.string(health, "gnews"))
        subtitle.text = "$modeLabel · $finished · $actionable actionable · $date · " +
            "${getString(R.string.health_bhavcopy)} $bhavcopy · " +
            "${getString(R.string.health_pulse)} $pulse · " +
            "${getString(R.string.health_nse)} $nse · " +
            "${getString(R.string.health_gnews)} $gnews"
    }

    private fun healthLabel(status: String?): String = when (status) {
        "ok" -> getString(R.string.health_ok)
        "skip" -> getString(R.string.health_skip)
        "partial" -> getString(R.string.health_partial)
        "cached" -> getString(R.string.health_cached)
        "fail" -> getString(R.string.health_fail)
        else -> getString(R.string.health_fail)
    }

    private fun formatIstTime(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return try {
            val instant = Instant.parse(iso)
            val fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Kolkata"))
            fmt.format(instant) + " IST"
        } catch (_: Exception) {
            iso.take(16)
        }
    }

    private fun parseMode(message: String): String {
        val token = message.split(" ").firstOrNull { it.startsWith("mode=") } ?: return "demo"
        return token.removePrefix("mode=")
    }

    private fun shareSummary(items: List<WatchlistItem>) {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.filter_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val body = items.joinToString("\n\n") { formatShareLine(it) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Gowri Screener flags")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    private fun formatShareLine(item: WatchlistItem): String {
        val daily = item.daily_return?.let { String.format("%+.1f%%", it * 100) } ?: "—"
        val idio = item.idiosyncratic_return?.let { String.format("%+.1f%%", it * 100) } ?: "—"
        val z = item.z_score?.let { String.format("%.2f", it) } ?: "—"
        return buildString {
            append(item.ticker)
            append(" · score ")
            append(String.format("%.0f", item.conviction_score ?: 0.0))
            append(" · ")
            append(item.severity_tag ?: "?")
            append("\nToday ")
            append(daily)
            append(" · idio ")
            append(idio)
            append(" · z ")
            append(z)
            append("\n")
            append(item.headline ?: "—")
        }
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_NEWS = "news"
        private const val TAG_SCREENER = "screener"
        private const val TAG_SWING = "swing"
    }
}
