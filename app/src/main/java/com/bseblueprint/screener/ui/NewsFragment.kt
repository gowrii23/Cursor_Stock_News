package com.bseblueprint.screener.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.data.NewsItem
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
    private var loaded = false

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
        if (!loaded) loadNews()
    }

    fun loadNews() {
        if (!isAdded) return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { PythonBridge.getNews() }
                val type = object : TypeToken<List<NewsItem>>() {}.type
                val items: List<NewsItem> =
                    gson.fromJson(payload.getAsJsonArray("news"), type) ?: emptyList()
                adapter.submit(items)
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                loaded = true
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
        private val severity: TextView = itemView.findViewById(R.id.txtSeverity)

        fun bind(item: NewsItem) {
            title.text = item.headline ?: "—"
            meta.text = listOfNotNull(item.ticker, item.source, item.date).joinToString(" · ")
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
}
