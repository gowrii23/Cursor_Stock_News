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
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.ScreenerStock
import com.bseblueprint.screener.data.ScreenerTierCounts
import com.bseblueprint.screener.data.ScreenerTierFilter
import com.bseblueprint.screener.data.ScreenerThemeFilter
import com.bseblueprint.screener.data.ScreenerTopReview
import com.bseblueprint.screener.data.ScreenerUiState
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class ScreenerFragment : Fragment() {

    interface Callback {
        fun onScreenerRunScan()
        fun onScreenerStockClick(stock: ScreenerStock)
        fun onScreenerLoad()
    }

    private var callback: Callback? = null
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: ScreenerAdapter
    private lateinit var topReviewSection: View
    private lateinit var chipAll: Chip
    private lateinit var chipHigh: Chip
    private lateinit var chipWatch: Chip
    private lateinit var chipLow: Chip
    private lateinit var chipBlueprint: Chip
    private val topPickViews = mutableListOf<View>()
    private var allItems: List<ScreenerStock> = emptyList()
    private var topReview: List<ScreenerTopReview> = emptyList()
    private var currentFilter = ScreenerTierFilter.ALL
    private var themeFilter = ScreenerThemeFilter.ALL

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_screener, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.screenerRecycler)
        emptyView = view.findViewById(R.id.screenerEmpty)
        subtitle = view.findViewById(R.id.screenerSubtitle)
        progress = view.findViewById(R.id.screenerProgress)
        topReviewSection = view.findViewById(R.id.topReviewSection)
        val tierChips = view.findViewById<ChipGroup>(R.id.tierChips)
        chipAll = view.findViewById(R.id.chipAll)
        chipHigh = view.findViewById(R.id.chipHigh)
        chipWatch = view.findViewById(R.id.chipWatch)
        chipLow = view.findViewById(R.id.chipLow)
        chipBlueprint = view.findViewById(R.id.chipBlueprint)
        topPickViews.add(view.findViewById(R.id.topPick1))
        topPickViews.add(view.findViewById(R.id.topPick2))
        topPickViews.add(view.findViewById(R.id.topPick3))

        adapter = ScreenerAdapter { stock -> callback?.onScreenerStockClick(stock) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnRunScan).setOnClickListener {
            callback?.onScreenerRunScan()
        }

        tierChips.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipHigh -> ScreenerTierFilter.HIGH
                R.id.chipWatch -> ScreenerTierFilter.WATCH
                R.id.chipLow -> ScreenerTierFilter.LOW
                else -> ScreenerTierFilter.ALL
            }
            applyFilter()
        }

        chipBlueprint.setOnCheckedChangeListener { _, isChecked ->
            themeFilter = if (isChecked) {
                ScreenerThemeFilter.BLUEPRINT_ONLY
            } else {
                ScreenerThemeFilter.ALL
            }
            applyFilter()
        }

        if (savedInstanceState == null) {
            callback?.onScreenerLoad()
        }
    }

    fun bindState(state: ScreenerUiState) {
        allItems = state.stocks
        topReview = state.topReview
        subtitle.text = state.metaLine
        updateChipCounts(state.counts)
        bindTopReview(state.topReview)
        applyFilter()
    }

    private fun updateChipCounts(counts: ScreenerTierCounts) {
        chipAll.text = getString(R.string.screener_tier_all_count, counts.all)
        chipHigh.text = getString(R.string.screener_tier_high_count, counts.high)
        chipWatch.text = getString(R.string.screener_tier_watch_count, counts.watch)
        chipLow.text = getString(R.string.screener_tier_low_count, counts.low)
        chipBlueprint.text = getString(R.string.screener_theme_blueprint_count, counts.blueprint)
    }

    private fun bindTopReview(items: List<ScreenerTopReview>) {
        if (items.isEmpty()) {
            topReviewSection.visibility = View.GONE
            return
        }
        topReviewSection.visibility = View.VISIBLE
        topPickViews.forEachIndexed { index, pickView ->
            val item = items.getOrNull(index)
            if (item == null) {
                pickView.visibility = View.GONE
                return@forEachIndexed
            }
            pickView.visibility = View.VISIBLE
            pickView.findViewById<TextView>(R.id.topSymbol).text =
                "${item.symbol} · ${String.format("%.0f", item.score_total ?: 0.0)}"
            pickView.findViewById<TextView>(R.id.topBadge).text =
                item.review_badge ?: item.name ?: item.symbol
            pickView.findViewById<TextView>(R.id.topScore).text =
                item.cmp?.let { "₹%.0f".format(it) } ?: "—"
            pickView.setOnClickListener {
                callback?.onScreenerStockClick(
                    ScreenerStock(
                        symbol = item.symbol,
                        name = item.name,
                        cmp = item.cmp,
                        score_total = item.score_total,
                        tier = item.tier
                    )
                )
            }
        }
    }

    private fun applyFilter() {
        val filtered = allItems.filter { item ->
            val tierOk = when (currentFilter) {
                ScreenerTierFilter.HIGH -> item.tier == "high"
                ScreenerTierFilter.WATCH -> item.tier == "watch"
                ScreenerTierFilter.LOW -> item.tier == "low"
                ScreenerTierFilter.ALL -> true
            }
            val themeOk = when (themeFilter) {
                ScreenerThemeFilter.ALL -> true
                ScreenerThemeFilter.BLUEPRINT_ONLY ->
                    item.blueprint_match || item.blueprint_tags.isNotEmpty()
            }
            tierOk && themeOk
        }
        adapter.submit(filtered)
        val hasScan = allItems.isNotEmpty() || topReview.isNotEmpty()
        val empty = filtered.isEmpty()
        emptyView.visibility = if (empty && hasScan) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty && hasScan) {
            emptyView.text = when {
                themeFilter == ScreenerThemeFilter.BLUEPRINT_ONLY ->
                    getString(R.string.screener_filter_empty_blueprint)
                currentFilter == ScreenerTierFilter.HIGH ->
                    getString(R.string.screener_filter_empty_high)
                currentFilter == ScreenerTierFilter.WATCH ->
                    getString(R.string.screener_filter_empty_watch)
                currentFilter == ScreenerTierFilter.LOW ->
                    getString(R.string.screener_filter_empty_low)
                else -> getString(R.string.screener_list_empty)
            }
        }
    }

    fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onDetach() {
        callback = null
        super.onDetach()
    }
}
