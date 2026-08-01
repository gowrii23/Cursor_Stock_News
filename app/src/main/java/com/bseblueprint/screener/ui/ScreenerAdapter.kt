package com.bseblueprint.screener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.ScreenerStock
import com.google.android.material.chip.Chip

class ScreenerAdapter(
    private val onClick: (ScreenerStock) -> Unit
) : RecyclerView.Adapter<ScreenerAdapter.VH>() {

    private var items: List<ScreenerStock> = emptyList()

    fun submit(list: List<ScreenerStock>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_screener_stock, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val symbol = itemView.findViewById<TextView>(R.id.stockSymbol)
        private val name = itemView.findViewById<TextView>(R.id.stockName)
        private val score = itemView.findViewById<TextView>(R.id.stockScore)
        private val cmp = itemView.findViewById<TextView>(R.id.stockCmp)
        private val tierChip = itemView.findViewById<Chip>(R.id.tierChip)
        private val l3 = itemView.findViewById<TextView>(R.id.layer3Hint)

        fun bind(item: ScreenerStock, onClick: (ScreenerStock) -> Unit) {
            symbol.text = item.symbol
            name.text = item.name ?: item.symbol
            score.text = String.format("%.0f", item.score_total ?: 0.0)
            cmp.text = item.cmp?.let { "₹%.2f".format(it) } ?: "—"
            val tier = item.tier ?: "low"
            tierChip.text = when (tier) {
                "high" -> "80+"
                "watch" -> "60–79"
                "low" -> "<60"
                else -> tier
            }
            val l3s = item.layer3?.signals?.size ?: 0
            l3.text = if (l3s > 0) "L3: $l3s signals" else "L3: —"
            l3.setTextColor(
                itemView.context.getColor(
                    if (l3s > 0) R.color.severity_candidate else R.color.text_secondary
                )
            )
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
