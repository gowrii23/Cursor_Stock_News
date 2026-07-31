package com.bseblueprint.screener.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.WatchlistItem
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        startActivity(
            Intent(this, StockDetailActivity::class.java)
                .putExtra(StockDetailActivity.EXTRA_TICKER, item.ticker)
        )
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
        val mode = run?.get("message")?.asString?.let { parseMode(it) } ?: "demo"
        val date = items.firstOrNull()?.date ?: "—"
        val newsLine = if (mode == "live") {
            getString(R.string.subtitle_live_news)
        } else {
            getString(R.string.subtitle_demo_news)
        }
        subtitle.text = "$newsLine · $date · ${items.size} flags"
    }

    private fun parseMode(message: String): String {
        val token = message.split(" ").firstOrNull { it.startsWith("mode=") } ?: return "demo"
        return token.removePrefix("mode=")
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_NEWS = "news"
    }
}
