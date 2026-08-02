package com.bseblueprint.screener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.PattasStock
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

class PattasAdapter(
    private val onClick: (PattasStock) -> Unit,
    private val onAddCandidate: ((PattasStock) -> Unit)? = null
) : RecyclerView.Adapter<PattasAdapter.VH>() {

    private var items: List<PattasStock> = emptyList()
    private var showAddButton = false

    fun submit(list: List<PattasStock>, candidates: Boolean = false) {
        items = list
        showAddButton = candidates
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pattas_stock, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], showAddButton, onClick, onAddCandidate)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val symbol = itemView.findViewById<TextView>(R.id.pattasSymbol)
        private val name = itemView.findViewById<TextView>(R.id.pattasName)
        private val cmp = itemView.findViewById<TextView>(R.id.pattasCmp)
        private val pillars = itemView.findViewById<TextView>(R.id.pattasPillars)
        private val scoreChip = itemView.findViewById<Chip>(R.id.pattasScoreChip)
        private val sectorChip = itemView.findViewById<Chip>(R.id.pattasSectorChip)
        private val addBtn = itemView.findViewById<MaterialButton>(R.id.btnAddToPattas)

        fun bind(
            item: PattasStock,
            showAdd: Boolean,
            onClick: (PattasStock) -> Unit,
            onAdd: ((PattasStock) -> Unit)?
        ) {
            symbol.text = item.symbol
            name.text = item.name ?: item.symbol
            cmp.text = item.cmp?.let { "₹%.2f".format(it) } ?: "—"
            val total = item.pillar_count.takeIf { it > 0 } ?: 4
            scoreChip.text = itemView.context.getString(
                R.string.pattas_stars,
                item.pattas_score,
                total
            )
            val isFinancial = item.sector == "financial"
            sectorChip.visibility = View.VISIBLE
            sectorChip.text = itemView.context.getString(
                if (isFinancial) R.string.pattas_sector_financial
                else R.string.pattas_sector_non_financial
            )
            pillars.text = formatPillars(item, isFinancial)
            addBtn.visibility = if (showAdd && onAdd != null) View.VISIBLE else View.GONE
            addBtn.setOnClickListener { onAdd?.invoke(item) }
            itemView.setOnClickListener { onClick(item) }
        }

        private fun formatPillars(item: PattasStock, isFinancial: Boolean): String {
            fun line(label: String, field: String, value: Double?): String {
                val med = item.peer_medians[field]
                val beat = item.pillars[field]
                val valStr = value?.let { "%.2f".format(it) } ?: "—"
                val medStr = med?.let { "%.2f".format(it) } ?: "—"
                val mark = when (beat) {
                    true -> "✓"
                    false -> "✗"
                    else -> "?"
                }
                return "$mark $label $valStr vs $medStr"
            }

            if (isFinancial) {
                return listOf(
                    line("P/B", "pb", item.pb),
                    line("Div%", "div_yield", item.div_yield),
                    line("NNPA", "net_npa", item.net_npa),
                    line("ROE", "roe_3y", item.roe_3y)
                ).joinToString("\n")
            }

            val growthMark = when (item.pillars["growth_consistency"]) {
                true -> "✓"
                false -> "✗"
                else -> "?"
            }
            val fcfMark = when (item.pillars["fcf_yield"]) {
                true -> "✓"
                false -> "✗"
                else -> "?"
            }
            val fcfMed = item.peer_medians["fcf_yield"]?.let { "%.2f".format(it) } ?: "—"
            return listOf(
                line("PE", "pe", item.pe),
                line("Div%", "div_yield", item.div_yield),
                line("D/E", "debt_eq", item.debt_eq),
                line("ROE", "roe_3y", item.roe_3y),
                "$fcfMark FCF yld vs $fcfMed",
                "$growthMark Growth3y"
            ).joinToString("\n")
        }
    }
}

class PattasManageAdapter(
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<PattasManageAdapter.VH>() {

    private var items: List<Pair<String, String?>> = emptyList()

    fun submit(symbols: List<Pair<String, String?>>) {
        items = symbols
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pattas_manage, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onDelete)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val symbol = itemView.findViewById<TextView>(R.id.manageSymbol)
        private val name = itemView.findViewById<TextView>(R.id.manageName)
        private val delete = itemView.findViewById<ImageButton>(R.id.btnDelete)

        fun bind(item: Pair<String, String?>, onDelete: (String) -> Unit) {
            symbol.text = item.first
            name.text = item.second ?: item.first
            delete.setOnClickListener { onDelete(item.first) }
        }
    }
}
