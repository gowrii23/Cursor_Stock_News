package com.bseblueprint.screener.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bseblueprint.screener.R
import com.bseblueprint.screener.data.SwingHit
import com.google.android.material.chip.Chip

class SwingAdapter(
    private val onClick: (SwingHit) -> Unit
) : RecyclerView.Adapter<SwingAdapter.VH>() {

    private var items: List<SwingHit> = emptyList()

    fun submit(list: List<SwingHit>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_swing_hit, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val symbol = itemView.findViewById<TextView>(R.id.swingSymbol)
        private val name = itemView.findViewById<TextView>(R.id.swingName)
        private val score = itemView.findViewById<TextView>(R.id.swingScore)
        private val close = itemView.findViewById<TextView>(R.id.swingClose)
        private val screenChip = itemView.findViewById<Chip>(R.id.swingScreenChip)
        private val hint = itemView.findViewById<TextView>(R.id.swingHint)

        fun bind(item: SwingHit, onClick: (SwingHit) -> Unit) {
            symbol.text = item.symbol
            name.text = item.name ?: item.symbol
            score.text = String.format("%.0f", item.score ?: 0.0)
            close.text = item.close?.let { "₹%.2f".format(it) } ?: "—"
            val primary = when (item.screen) {
                "momentum" -> "Momentum"
                "sleeping" -> "Sleeping Giant"
                else -> item.screen ?: "—"
            }
            val also = item.also_screens.orEmpty()
                .map {
                    when (it) {
                        "momentum" -> "Mom"
                        "sleeping" -> "Sleep"
                        else -> it
                    }
                }
            screenChip.text = if (also.isEmpty()) primary else "$primary + ${also.joinToString()}"
            val vol = item.metrics?.get("vol_ratio")
            val stop = item.metrics?.get("stop_hint")
            hint.text = buildString {
                append(item.signals?.firstOrNull() ?: "—")
                if (vol != null) append(" · vol ${"%.1f".format(vol)}×")
                if (stop != null) append(" · stop ~₹${"%.0f".format(stop)}")
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
