package com.bseblueprint.screener.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.SwingCounts
import com.bseblueprint.screener.data.SwingHit
import com.bseblueprint.screener.data.SwingScreenFilter
import com.bseblueprint.screener.data.SwingUiState
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class SwingFragment : Fragment() {

    interface Callback {
        fun onSwingRun()
        fun onSwingHitClick(hit: SwingHit)
        fun onSwingLoad()
    }

    private var callback: Callback? = null
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var subtitle: TextView
    private lateinit var regimeText: TextView
    private lateinit var regimeBanner: MaterialCardView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: SwingAdapter
    private lateinit var chipAll: Chip
    private lateinit var chipMomentum: Chip
    private lateinit var chipSleeping: Chip
    private var allItems: List<SwingHit> = emptyList()
    private var hasRun = false
    private var topN = 8
    private var currentFilter = SwingScreenFilter.ALL

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_swing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.swingRecycler)
        emptyView = view.findViewById(R.id.swingEmpty)
        subtitle = view.findViewById(R.id.swingSubtitle)
        regimeText = view.findViewById(R.id.regimeText)
        regimeBanner = view.findViewById(R.id.regimeBanner)
        progress = view.findViewById(R.id.swingProgress)
        chipAll = view.findViewById(R.id.chipSwingAll)
        chipMomentum = view.findViewById(R.id.chipSwingMomentum)
        chipSleeping = view.findViewById(R.id.chipSwingSleeping)

        adapter = SwingAdapter { hit -> callback?.onSwingHitClick(hit) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnRunSwing).setOnClickListener {
            callback?.onSwingRun()
        }

        view.findViewById<ChipGroup>(R.id.swingChips).setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipSwingMomentum -> SwingScreenFilter.MOMENTUM
                R.id.chipSwingSleeping -> SwingScreenFilter.SLEEPING
                else -> SwingScreenFilter.ALL
            }
            applyFilter()
        }
    }

    fun bindState(state: SwingUiState) {
        allItems = state.hits
        topN = state.coverage.topN.coerceAtLeast(1)
        hasRun = state.hits.isNotEmpty() ||
            state.coverage.pricedCount > 0 ||
            state.metaLine != getString(R.string.swing_empty)
        subtitle.text = state.metaLine
        updateChipCounts(state.counts)
        bindRegime(state.regime.state, state.regime.label)
        applyFilter()
    }

    private fun bindRegime(state: String, label: String) {
        regimeText.text = label
        val color = when (state) {
            "bullish" -> R.color.severity_candidate
            "bearish" -> R.color.severity_exclude
            else -> R.color.severity_unknown
        }
        regimeText.setTextColor(ContextCompat.getColor(requireContext(), color))
    }

    private fun updateChipCounts(counts: SwingCounts) {
        chipAll.text = getString(R.string.swing_filter_all_count, counts.all)
        chipMomentum.text = getString(R.string.swing_filter_momentum_count, counts.momentum)
        chipSleeping.text = getString(R.string.swing_filter_sleeping_count, counts.sleeping)
    }

    private fun applyFilter() {
        val filtered = allItems.filter { item ->
            when (currentFilter) {
                SwingScreenFilter.MOMENTUM -> item.screen == "momentum"
                SwingScreenFilter.SLEEPING -> item.screen == "sleeping"
                SwingScreenFilter.ALL -> true
            }
        }.sortedByDescending { it.score ?: 0.0 }
            .take(topN)
        adapter.submit(filtered)
        val empty = filtered.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            emptyView.text = when {
                !hasRun -> getString(R.string.swing_empty)
                currentFilter == SwingScreenFilter.MOMENTUM -> getString(R.string.swing_empty_momentum)
                currentFilter == SwingScreenFilter.SLEEPING -> getString(R.string.swing_empty_sleeping)
                else -> getString(R.string.swing_list_empty)
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
