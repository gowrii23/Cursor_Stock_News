package com.bseblueprint.screener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.NewsItem
import com.bseblueprint.screener.util.JsonSafe
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewsFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: NewsAdapter
    private val gson = Gson()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_news, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recyclerNews)
        emptyView = view.findViewById(R.id.emptyView)
        progress = view.findViewById(R.id.progress)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = NewsAdapter()
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadNews()
    }

    fun loadNews() {
        if (!isAdded) return
        progress.visibility = View.VISIBLE
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
                val hasContent = rows.isNotEmpty()
                emptyView.visibility = if (hasContent) View.GONE else View.VISIBLE
                recycler.visibility = if (hasContent) View.VISIBLE else View.GONE
            } catch (t: Throwable) {
                emptyView.text = "Failed: ${t.message}"
                emptyView.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }
}

sealed class NewsRow {
    data class SectionHeader(val title: String) : NewsRow()
    data class Item(val news: NewsItem) : NewsRow()
}

class NewsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<NewsRow>()

    fun submit(data: List<NewsRow>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is NewsRow.SectionHeader -> VIEW_HEADER
        is NewsRow.Item -> VIEW_NEWS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_HEADER) {
            HeaderVH(inflater.inflate(R.layout.item_news_section, parent, false))
        } else {
            NewsVH(inflater.inflate(R.layout.item_news, parent, false))
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is NewsRow.SectionHeader -> (holder as HeaderVH).bind(row.title)
            is NewsRow.Item -> (holder as NewsVH).bind(row.news)
        }
    }

    class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.txtSectionHeader)
        fun bind(text: String) {
            title.text = text
        }
    }

    class NewsVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.txtHeadline)
        private val meta: TextView = itemView.findViewById(R.id.txtMeta)
        private val severity: TextView = itemView.findViewById(R.id.txtSeverity)

        fun bind(item: NewsItem) {
            title.text = item.headline ?: "—"
            val tickerLabel = item.ticker?.takeIf { it.isNotBlank() } ?: "—"
            val timing = when (item.timing_vs_close) {
                "before_close" -> itemView.context.getString(R.string.timing_before_close)
                "after_close" -> itemView.context.getString(R.string.timing_after_close)
                else -> itemView.context.getString(R.string.timing_unknown)
            }
            meta.text = listOfNotNull(tickerLabel, item.source, timing, item.date).joinToString(" · ")
            val tag = item.severity_tag ?: "UNKNOWN"
            severity.text = tag
            val color = when (tag) {
                "CANDIDATE" -> R.color.severity_candidate
                "EXCLUDE" -> R.color.severity_exclude
                else -> R.color.severity_unknown
            }
            severity.setTextColor(ContextCompat.getColor(itemView.context, color))
        }
    }

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_NEWS = 1
    }
}
