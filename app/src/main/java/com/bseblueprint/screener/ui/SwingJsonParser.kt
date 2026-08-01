package com.bseblueprint.screener.ui

import com.bseblueprint.screener.data.SwingCounts
import com.bseblueprint.screener.data.SwingHit
import com.bseblueprint.screener.data.SwingRegime
import com.bseblueprint.screener.data.SwingUiState
import com.bseblueprint.screener.util.JsonSafe
import com.google.gson.JsonArray
import com.google.gson.JsonObject

object SwingJsonParser {

    fun parseDashboard(dash: JsonObject, emptyMeta: String): SwingUiState {
        val hits = JsonSafe.arr(dash, "hits")?.let { parseHits(it) } ?: emptyList()
        val regimeObj = JsonSafe.obj(dash, "regime")
        val regime = SwingRegime(
            bullish = JsonSafe.bool(regimeObj, "bullish") ?: false,
            label = JsonSafe.string(regimeObj, "label") ?: "No run yet"
        )
        val countsObj = JsonSafe.obj(dash, "counts")
        val counts = if (countsObj != null) {
            SwingCounts(
                momentum = JsonSafe.int(countsObj, "momentum") ?: 0,
                sleeping = JsonSafe.int(countsObj, "sleeping") ?: 0,
                all = JsonSafe.int(countsObj, "all") ?: hits.size
            )
        } else {
            SwingCounts(
                momentum = hits.count { it.screen == "momentum" },
                sleeping = hits.count { it.screen == "sleeping" },
                all = hits.size
            )
        }
        val run = JsonSafe.obj(dash, "run")
        val meta = if (run != null) {
            val m = JsonSafe.int(run, "momentum_count") ?: 0
            val s = JsonSafe.int(run, "sleeping_count") ?: 0
            "${regime.label} · $m momentum · $s sleeping giant"
        } else {
            emptyMeta
        }
        return SwingUiState(hits, regime, counts, meta)
    }

    private fun parseHits(arr: JsonArray): List<SwingHit> {
        val out = mutableListOf<SwingHit>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val symbol = JsonSafe.string(o, "symbol")?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            val signals = JsonSafe.arr(o, "signals")?.mapNotNull { JsonSafe.string(it) }
            val metricsObj = JsonSafe.obj(o, "metrics")
            val metrics = metricsObj?.entrySet()?.mapNotNull { (k, v) ->
                JsonSafe.double(v)?.let { k to it }
            }?.toMap()
            out.add(
                SwingHit(
                    symbol = symbol,
                    name = JsonSafe.string(o, "name"),
                    screen = JsonSafe.string(o, "screen"),
                    close = JsonSafe.double(o, "close"),
                    score = JsonSafe.double(o, "score"),
                    signals = signals,
                    metrics = metrics
                )
            )
        }
        return out
    }
}
