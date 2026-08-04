package com.bseblueprint.screener.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.PattasStock
import com.bseblueprint.screener.data.PattasUiState
import com.google.android.material.button.MaterialButton

class PattasFragment : Fragment() {

    interface Callback {
        fun onPattasRunScan()
        fun onPattasStockClick(stock: PattasStock)
        fun onPattasLoad()
        fun onPattasManageList()
        fun onPattasAddCandidate(stock: PattasStock)
    }

    private var callback: Callback? = null
    private lateinit var recycler: RecyclerView
    private lateinit var candidatesRecycler: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var candidatesEmpty: TextView
    private lateinit var candidatesTitle: TextView
    private lateinit var subtitle: TextView
    private lateinit var healthBanner: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: PattasAdapter
    private lateinit var candidatesAdapter: PattasAdapter

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? Callback
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pattas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.pattasRecycler)
        candidatesRecycler = view.findViewById(R.id.pattasCandidatesRecycler)
        emptyView = view.findViewById(R.id.pattasEmpty)
        candidatesEmpty = view.findViewById(R.id.pattasCandidatesEmpty)
        candidatesTitle = view.findViewById(R.id.pattasCandidatesTitle)
        subtitle = view.findViewById(R.id.pattasSubtitle)
        healthBanner = view.findViewById(R.id.pattasHealthBanner)
        progress = view.findViewById(R.id.pattasProgress)

        adapter = PattasAdapter(onClick = { stock -> callback?.onPattasStockClick(stock) })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        candidatesAdapter = PattasAdapter(
            onClick = { stock -> callback?.onPattasStockClick(stock) },
            onAddCandidate = { stock -> callback?.onPattasAddCandidate(stock) }
        )
        candidatesRecycler.layoutManager = LinearLayoutManager(requireContext())
        candidatesRecycler.adapter = candidatesAdapter

        view.findViewById<MaterialButton>(R.id.btnRunPattasScan).setOnClickListener {
            callback?.onPattasRunScan()
        }
        view.findViewById<ImageButton>(R.id.btnEditList).setOnClickListener {
            callback?.onPattasManageList()
        }

        if (savedInstanceState == null) {
            callback?.onPattasLoad()
        }
    }

    fun bindState(state: PattasUiState) {
        subtitle.text = state.metaLine.ifBlank {
            getString(R.string.pattas_empty)
        }
        val banner = state.healthBanner
        if (!banner.isNullOrBlank() && banner.startsWith("missing:")) {
            val count = banner.removePrefix("missing:").toIntOrNull() ?: 0
            if (count > 0) {
                healthBanner.visibility = View.VISIBLE
                healthBanner.text = getString(R.string.pattas_scrape_health_banner, count)
            } else {
                healthBanner.visibility = View.GONE
            }
        } else {
            healthBanner.visibility = View.GONE
        }
        adapter.submit(state.stocks)
        val hasStocks = state.stocks.isNotEmpty()
        emptyView.visibility = if (!hasStocks) View.VISIBLE else View.GONE
        recycler.visibility = if (hasStocks) View.VISIBLE else View.GONE

        val candidates = state.candidates
        if (candidates.isNotEmpty()) {
            candidatesTitle.visibility = View.VISIBLE
            candidatesTitle.text = getString(R.string.pattas_candidates_title, candidates.size)
            candidatesRecycler.visibility = View.VISIBLE
            candidatesEmpty.visibility = View.GONE
            candidatesAdapter.submit(candidates, candidates = true)
        } else {
            candidatesTitle.visibility = View.GONE
            candidatesRecycler.visibility = View.GONE
            candidatesEmpty.visibility = View.VISIBLE
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
