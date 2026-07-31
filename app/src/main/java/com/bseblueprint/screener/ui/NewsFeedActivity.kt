package com.bseblueprint.screener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.NewsItem
import com.google.android.material.appbar.MaterialToolbar
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewsFeedActivity : AppCompatActivity() {
    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_feed)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "News Feed"
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
                val items: List<NewsItem> =
                    gson.fromJson(payload.getAsJsonArray("news"), type) ?: emptyList()
                adapter.submit(items)
                empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (t: Throwable) {
                empty.text = "Failed: ${t.message}"
                empty.visibility = View.VISIBLE
            }
        }
    }
}

class NewsAdapter : RecyclerView.Adapter<NewsAdapter.VH>() {
    private val items = mutableListOf<NewsItem>()

    fun submit(data: List<NewsItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_news, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.txtHeadline)
        private val meta: TextView = itemView.findViewById(R.id.txtMeta)

        fun bind(item: NewsItem) {
            title.text = item.headline ?: "—"
            meta.text = listOfNotNull(
                item.ticker,
                item.source,
                item.severity_tag,
                item.date
            ).joinToString(" · ")
        }
    }
}
