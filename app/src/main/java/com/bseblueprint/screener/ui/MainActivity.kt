package com.bseblueprint.screener.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.WatchlistItem
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

class MainActivity : AppCompatActivity(), HomeFragment.Callback {

    private lateinit var subtitle: TextView
    private lateinit var bottomNav: BottomNavigationView
    private val gson = Gson()
    private var homeFragment: HomeFragment? = null
    private var newsFragment: NewsFragment? = null

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
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, newsFragment!!, TAG_NEWS)
                .hide(newsFragment!!)
                .add(R.id.fragmentContainer, homeFragment!!, TAG_HOME)
                .commit()
        } else {
            homeFragment = supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment
            newsFragment = supportFragmentManager.findFragmentByTag(TAG_NEWS) as? NewsFragment
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showFragment(TAG_HOME)
                    supportActionBar?.title = getString(R.string.tab_home)
                    true
                }
                R.id.nav_news -> {
                    showFragment(TAG_NEWS)
                    supportActionBar?.title = getString(R.string.tab_news)
                    newsFragment?.loadNews()
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

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_run -> {
                refreshDashboard(runScreen = true)
                true
            }
            R.id.action_share -> {
                shareSummary(homeFragment?.getActionableItems().orEmpty())
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showFragment(tag: String) {
        val home = homeFragment ?: return
        val news = newsFragment ?: return
        val tx = supportFragmentManager.beginTransaction()
        if (tag == TAG_HOME) {
            tx.show(home).hide(news)
        } else {
            tx.show(news).hide(home)
        }
        tx.commit()
    }

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

    override fun onRefreshRequested() {
        refreshDashboard(runScreen = true)
    }

    override fun onLoadDashboard(seedIfEmpty: Boolean) {
        loadDashboard(seedIfEmpty)
    }

    private fun loadDashboard(seedIfEmpty: Boolean) {
        homeFragment?.setLoading(true)
        lifecycleScope.launch {
            try {
                val dash = withContext(Dispatchers.IO) { PythonBridge.getDashboard() }
                val arr = dash.getAsJsonArray("watchlist")
                val type = object : TypeToken<List<WatchlistItem>>() {}.type
                var items: List<WatchlistItem> = gson.fromJson(arr, type) ?: emptyList()
                if (items.isEmpty() && seedIfEmpty) {
                    subtitle.text = "Seeding demo screen…"
                    withContext(Dispatchers.IO) {
                        PythonBridge.runDailyScreen(useLive = false, forceDemo = true)
                    }
                    val dash2 = withContext(Dispatchers.IO) { PythonBridge.getDashboard() }
                    items = gson.fromJson(dash2.getAsJsonArray("watchlist"), type) ?: emptyList()
                    updateSubtitle(dash2, items)
                } else {
                    updateSubtitle(dash, items)
                }
                homeFragment?.bindItems(items)
            } catch (t: Throwable) {
                t.printStackTrace()
                Toast.makeText(this@MainActivity, "Load failed: ${t.message}", Toast.LENGTH_LONG).show()
                homeFragment?.showEmpty()
            } finally {
                homeFragment?.setLoading(false)
                homeFragment?.setRefreshing(false)
            }
        }
    }

    private fun refreshDashboard(runScreen: Boolean) {
        if (bottomNav.selectedItemId != R.id.nav_home) {
            bottomNav.selectedItemId = R.id.nav_home
            showFragment(TAG_HOME)
            supportActionBar?.title = getString(R.string.tab_home)
        }
        homeFragment?.setRefreshing(true)
        subtitle.text = "Running EOD screen…"
        lifecycleScope.launch {
            try {
                if (runScreen) {
                    val result = withContext(Dispatchers.IO) {
                        PythonBridge.runDailyScreen(useLive = true, forceDemo = false)
                    }
                    val mode = result.get("mode")?.asString ?: "?"
                    val flagged = result.get("flagged_count")?.asInt ?: 0
                    val newsNote = if (mode == "live") "live news" else "demo headlines"
                    Toast.makeText(
                        this@MainActivity,
                        "Screen done ($mode, $newsNote): $flagged flags",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadDashboard(seedIfEmpty = false)
                newsFragment?.loadNews()
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "Run failed: ${t.message}", Toast.LENGTH_LONG).show()
                homeFragment?.setRefreshing(false)
                homeFragment?.setLoading(false)
            }
        }
    }

    private fun updateSubtitle(dash: JsonObject, items: List<WatchlistItem>) {
        val run = dash.getAsJsonObject("latest_run")
        val health = dash.getAsJsonObject("data_health")
        val mode = health?.get("mode")?.asString
            ?: run?.get("message")?.asString?.let { parseMode(it) }
            ?: "demo"
        val actionable = items.count { it.severity_tag == "CANDIDATE" }
        val date = items.firstOrNull()?.date ?: "—"
        val finished = formatIstTime(run?.get("finished_at")?.asString)
        val modeLabel = if (mode == "live") {
            getString(R.string.subtitle_live_news)
        } else {
            getString(R.string.subtitle_demo_news)
        }
        val yahoo = healthLabel(health?.get("yahoo")?.asString)
        val pulse = healthLabel(health?.get("pulse")?.asString)
        val nse = healthLabel(health?.get("nse")?.asString)
        subtitle.text = "$modeLabel · $finished · $actionable actionable · $date · " +
            "${getString(R.string.health_yahoo)} $yahoo · " +
            "${getString(R.string.health_pulse)} $pulse · " +
            "${getString(R.string.health_nse)} $nse"
    }

    private fun healthLabel(status: String?): String =
        if (status == "ok") getString(R.string.health_ok) else getString(R.string.health_fail)

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
            putExtra(Intent.EXTRA_SUBJECT, "BSE Blueprint flags")
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
            append("\n")
            append("Today ")
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
    }
}
