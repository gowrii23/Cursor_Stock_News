package com.bseblueprint.screener.ui

import com.bseblueprint.screener.data.SwingCounts
import com.bseblueprint.screener.data.SwingCoverage
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
        val state = JsonSafe.string(regimeObj, "state") ?: "insufficient"
        val regime = SwingRegime(
            state = state,
            bullish = state == "bullish",
            label = JsonSafe.string(regimeObj, "label") ?: "No run yet",
            asOf = JsonSafe.string(regimeObj, "as_of")
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
        val covObj = JsonSafe.obj(dash, "coverage")
        val coverage = SwingCoverage(
            pricedCount = JsonSafe.int(covObj, "priced_count") ?: 0,
            universeSize = JsonSafe.int(covObj, "universe_size") ?: 0,
            asOf = JsonSafe.string(covObj, "as_of"),
            topN = JsonSafe.int(covObj, "top_n") ?: 8,
            totalHits = JsonSafe.int(covObj, "total_hits") ?: hits.size
        )
        val run = JsonSafe.obj(dash, "run")
        val meta = if (run != null) {
            buildMeta(regime, coverage, counts)
        } else {
            emptyMeta
        }
        return SwingUiState(hits, regime, counts, coverage, meta)
    }

    private fun buildMeta(
        regime: SwingRegime,
        coverage: SwingCoverage,
        counts: SwingCounts
    ): String = buildString {
        val asOf = coverage.asOf ?: regime.asOf
        if (!asOf.isNullOrBlank()) append("As of $asOf · ")
        if (coverage.universeSize > 0) {
            append("${coverage.pricedCount}/${coverage.universeSize} priced · ")
        }
        append("${counts.momentum} mom · ${counts.sleeping} sleep")
        if (coverage.totalHits > coverage.topN) {
            append(" · showing top ${coverage.topN}")
        }
    }

    private fun parseHits(arr: JsonArray): List<SwingHit> {
        val out = mutableListOf<SwingHit>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val symbol = JsonSafe.string(o, "symbol")?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            val signals = JsonSafe.arr(o, "signals")?.mapNotNull { JsonSafe.string(it) }
            val also = JsonSafe.arr(o, "also_screens")?.mapNotNull { JsonSafe.string(it) }
            val metricsObj = JsonSafe.obj(o, "metrics")
            val metrics = metricsObj?.entrySet()?.associate { (k, v) ->
                k to JsonSafe.double(v)
            }
            out.add(
                SwingHit(
                    symbol = symbol,
                    name = JsonSafe.string(o, "name"),
                    screen = JsonSafe.string(o, "screen"),
                    close = JsonSafe.double(o, "close"),
                    score = JsonSafe.double(o, "score"),
                    signals = signals,
                    metrics = metrics,
                    also_screens = also,
                    as_of = JsonSafe.string(o, "as_of")
                )
            )
        }
        return out
    }
}
