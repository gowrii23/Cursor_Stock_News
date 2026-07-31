package com.bseblueprint.screener.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.WatchlistItem
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: WatchlistAdapter
    private val gson = Gson()

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "BSE Blueprint"

        recycler = findViewById(R.id.recyclerWatchlist)
        emptyView = findViewById(R.id.emptyView)
        subtitle = findViewById(R.id.subtitle)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipeRefresh)

        adapter = WatchlistAdapter { item ->
            startActivity(
                Intent(this, StockDetailActivity::class.java)
                    .putExtra(StockDetailActivity.EXTRA_TICKER, item.ticker)
            )
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        swipe.setOnRefreshListener { refreshDashboard(runScreen = true) }

        maybeRequestNotifications()
        loadDashboard(seedIfEmpty = true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_news -> {
                startActivity(Intent(this, NewsFeedActivity::class.java))
                true
            }
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

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadDashboard(seedIfEmpty: Boolean) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
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
                    val run = dash2.getAsJsonObject("latest_run")
                    subtitle.text = "Demo mode · ${items.size} flags · ${run?.get("finished_at")?.asString ?: ""}"
                } else {
                    val run = dash.getAsJsonObject("latest_run")
                    val date = items.firstOrNull()?.date ?: "—"
                    subtitle.text = "Watchlist $date · ${items.size} flags"
                    if (run != null && run.has("message")) {
                        subtitle.append(" · ${run.get("message").asString}")
                    }
                }
                bindItems(items)
            } catch (t: Throwable) {
                t.printStackTrace()
                Toast.makeText(this@MainActivity, "Load failed: ${t.message}", Toast.LENGTH_LONG).show()
                emptyView.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
                swipe.isRefreshing = false
            }
        }
    }

    private fun refreshDashboard(runScreen: Boolean) {
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            subtitle.text = "Running EOD screen…"
            try {
                if (runScreen) {
                    val result = withContext(Dispatchers.IO) {
                        // Try live; Python falls back to demo if needed
                        PythonBridge.runDailyScreen(useLive = true, forceDemo = false)
                    }
                    val mode = result.get("mode")?.asString ?: "?"
                    val flagged = result.get("flagged_count")?.asInt ?: 0
                    Toast.makeText(
                        this@MainActivity,
                        "Screen done ($mode): $flagged flags",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                loadDashboard(seedIfEmpty = false)
            } catch (t: Throwable) {
                Toast.makeText(this@MainActivity, "Run failed: ${t.message}", Toast.LENGTH_LONG).show()
                progress.visibility = View.GONE
                swipe.isRefreshing = false
            }
        }
    }

    private fun bindItems(items: List<WatchlistItem>) {
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }
}
