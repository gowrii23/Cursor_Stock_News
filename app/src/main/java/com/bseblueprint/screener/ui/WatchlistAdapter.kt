package com.bseblueprint.screener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.WatchlistItem

class WatchlistAdapter(
    private val onClick: (WatchlistItem) -> Unit,
    private val onShare: (WatchlistItem) -> Unit
) : RecyclerView.Adapter<WatchlistAdapter.VH>() {

    private val items = mutableListOf<WatchlistItem>()

    fun submit(data: List<WatchlistItem>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_watchlist, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onClick, onShare)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ticker: TextView = itemView.findViewById(R.id.txtTicker)
        private val score: TextView = itemView.findViewById(R.id.txtScore)
        private val headline: TextView = itemView.findViewById(R.id.txtHeadline)
        private val chips: TextView = itemView.findViewById(R.id.txtChips)
        private val metrics: TextView = itemView.findViewById(R.id.txtMetrics)

        fun bind(item: WatchlistItem, onClick: (WatchlistItem) -> Unit, onShare: (WatchlistItem) -> Unit) {
            ticker.text = item.ticker
            score.text = String.format("%.0f", item.conviction_score ?: 0.0)
            headline.text = item.headline ?: "—"

            val daily = pct(item.daily_return)
            val idio = pct(item.idiosyncratic_return)
            val z = item.z_score?.let { String.format("z %.2f", it) } ?: "z —"
            val sev = item.severity_tag ?: "UNKNOWN"
            metrics.text = listOf(daily, idio, z, sev).joinToString("  ·  ")

            val beta = item.beta_1y?.let { String.format("β %.2f", it) } ?: "β —"
            val tags = item.blueprint_tags.joinToString(" · ")
            chips.text = listOf(beta, tags).filter { it.isNotBlank() }.joinToString("  ·  ")

            val color = when (item.severity_tag) {
                "CANDIDATE" -> R.color.severity_candidate
                "EXCLUDE" -> R.color.severity_exclude
                else -> R.color.severity_unknown
            }
            score.setTextColor(ContextCompat.getColor(itemView.context, color))
            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener {
                onShare(item)
                true
            }
        }

        private fun pct(v: Double?): String {
            if (v == null) return "—%"
            return String.format("%+.1f%%", v * 100.0)
        }
    }
}
