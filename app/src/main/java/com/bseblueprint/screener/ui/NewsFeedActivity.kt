package com.bseblueprint.screener.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.NewsItem
import com.bseblueprint.screener.util.JsonSafe
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Legacy entry point — prefer MainActivity News tab. */
class NewsFeedActivity : AppCompatActivity() {
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_feed)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.tab_news)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.recyclerNews)
        val empty = findViewById<TextView>(R.id.emptyView)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = NewsAdapter()
        recycler.adapter = adapter

        lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { PythonBridge.getNews() }
                val type = object : TypeToken<List<NewsItem>>() {}.type
                val newsArr = JsonSafe.arr(payload, "news")
                val primary: List<NewsItem> =
                    if (newsArr != null) gson.fromJson(newsArr, type) ?: emptyList() else emptyList()
                val pulseArr = JsonSafe.arr(payload, "pulse_feed")
                val pulse: List<NewsItem> =
                    if (pulseArr != null) gson.fromJson(pulseArr, type) ?: emptyList() else emptyList()
                val rows = mutableListOf<NewsRow>()
                primary.forEach { rows.add(NewsRow.Item(it)) }
                if (pulse.isNotEmpty()) {
                    rows.add(NewsRow.SectionHeader(getString(R.string.news_pulse_section)))
                    pulse.forEach { rows.add(NewsRow.Item(it)) }
                }
                adapter.submit(rows)
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            } catch (t: Throwable) {
                empty.text = "Failed: ${t.message}"
                empty.visibility = View.VISIBLE
            }
        }
    }
}
