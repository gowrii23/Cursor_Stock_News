package com.bseblueprint.screener.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.WatchlistItem

class HomeFragment : Fragment() {

    interface Callback {
        fun onWatchlistItemClick(item: WatchlistItem)
        fun onRefreshRequested()
        fun onLoadDashboard(seedIfEmpty: Boolean)
    }

    private var callback: Callback? = null
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: WatchlistAdapter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.recyclerWatchlist)
        emptyView = view.findViewById(R.id.emptyView)
        progress = view.findViewById(R.id.progress)
        swipe = view.findViewById(R.id.swipeRefresh)

        adapter = WatchlistAdapter { item -> callback?.onWatchlistItemClick(item) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipe.setColorSchemeResources(R.color.metallic_highlight, R.color.accent)
        swipe.setOnRefreshListener { callback?.onRefreshRequested() }

        if (savedInstanceState == null) {
            callback?.onLoadDashboard(seedIfEmpty = true)
        }
    }

    fun bindItems(items: List<WatchlistItem>) {
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    fun showEmpty() {
        emptyView.visibility = View.VISIBLE
        recycler.visibility = View.GONE
    }

    fun setLoading(loading: Boolean) {
        progress.visibility = if (loading && !swipe.isRefreshing) View.VISIBLE else View.GONE
    }

    fun setRefreshing(refreshing: Boolean) {
        swipe.isRefreshing = refreshing
        if (refreshing) progress.visibility = View.GONE
    }

    override fun onDetach() {
        callback = null
        super.onDetach()
    }
}
