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
import com.bseblueprint.screener.data.ScreenerTierFilter
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
    private var allItems: List<ScreenerStock> = emptyList()
    private var currentFilter = ScreenerTierFilter.HIGH

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
        val tierChips = view.findViewById<ChipGroup>(R.id.tierChips)

        adapter = ScreenerAdapter { stock -> callback?.onScreenerStockClick(stock) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnRunScan).setOnClickListener {
            callback?.onScreenerRunScan()
        }

        tierChips.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipWatch -> ScreenerTierFilter.WATCH
                R.id.chipAll -> ScreenerTierFilter.ALL
                else -> ScreenerTierFilter.HIGH
            }
            applyFilter()
        }
        view.findViewById<Chip>(R.id.chipHigh).isChecked = true

        if (savedInstanceState == null) {
            callback?.onScreenerLoad()
        }
    }

    fun bindData(items: List<ScreenerStock>, metaLine: String) {
        allItems = items
        subtitle.text = metaLine
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = allItems.filter { item ->
            when (currentFilter) {
                ScreenerTierFilter.HIGH -> item.tier == "high"
                ScreenerTierFilter.WATCH -> item.tier == "watch"
                ScreenerTierFilter.ALL -> true
            }
        }
        adapter.submit(filtered)
        val empty = filtered.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    override fun onDetach() {
        callback = null
        super.onDetach()
    }
}
