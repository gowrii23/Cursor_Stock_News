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
import com.bseblueprint.screener.data.WatchlistFilter
import com.bseblueprint.screener.data.WatchlistItem
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class HomeFragment : Fragment() {

    interface Callback {
        fun onWatchlistItemClick(item: WatchlistItem)
        fun onWatchlistItemShare(item: WatchlistItem)
        fun onRefreshRequested()
        fun onLoadDashboard(seedIfEmpty: Boolean)
    }

    private var callback: Callback? = null
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var filterChips: ChipGroup
    private lateinit var adapter: WatchlistAdapter
    private var allItems: List<WatchlistItem> = emptyList()
    private var currentFilter: WatchlistFilter = WatchlistFilter.ACTIONABLE

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
        filterChips = view.findViewById(R.id.filterChips)

        adapter = WatchlistAdapter(
            onClick = { item -> callback?.onWatchlistItemClick(item) },
            onShare = { item -> callback?.onWatchlistItemShare(item) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipe.setColorSchemeResources(R.color.metallic_highlight, R.color.accent)
        swipe.setOnRefreshListener { callback?.onRefreshRequested() }

        filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipReview -> WatchlistFilter.REVIEW
                R.id.chipBlocked -> WatchlistFilter.BLOCKED
                else -> WatchlistFilter.ACTIONABLE
            }
            applyFilter()
        }
        view.findViewById<Chip>(R.id.chipActionable).isChecked = true

        if (savedInstanceState == null) {
            callback?.onLoadDashboard(seedIfEmpty = true)
        }
    }

    fun bindItems(items: List<WatchlistItem>) {
        allItems = items
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = allItems.filter { item ->
            when (currentFilter) {
                WatchlistFilter.ACTIONABLE -> item.severity_tag == "CANDIDATE"
                WatchlistFilter.REVIEW -> item.severity_tag == "UNKNOWN"
                WatchlistFilter.BLOCKED -> item.severity_tag == "EXCLUDE"
            }
        }
        adapter.submit(filtered)
        val empty = filtered.isEmpty()
        emptyView.text = if (allItems.isEmpty()) {
            getString(R.string.watchlist_empty)
        } else {
            getString(R.string.filter_empty)
        }
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    fun getActionableItems(): List<WatchlistItem> =
        allItems.filter { it.severity_tag == "CANDIDATE" }

    fun showEmpty() {
        allItems = emptyList()
        adapter.submit(emptyList())
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
