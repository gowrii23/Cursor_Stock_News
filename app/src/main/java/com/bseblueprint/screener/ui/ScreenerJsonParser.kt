package com.bseblueprint.screener.ui

import com.bseblueprint.screener.data.Layer3Result
import com.bseblueprint.screener.data.ScreenerStock
import com.bseblueprint.screener.data.ScreenerTierCounts
import com.bseblueprint.screener.data.ScreenerTopReview
import com.bseblueprint.screener.data.ScreenerUiState
import com.bseblueprint.screener.util.JsonSafe
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

object ScreenerJsonParser {

    fun parseDashboard(dash: JsonObject, emptyMeta: String): ScreenerUiState {
        val scan = JsonSafe.obj(dash, "scan")
        val stocks = JsonSafe.arr(dash, "stocks")?.let { parseStocks(it) } ?: emptyList()
        val topReview = JsonSafe.arr(dash, "top_review")?.let { parseTopReview(it) } ?: emptyList()
        val countsElem = JsonSafe.obj(dash, "counts")
        val counts = if (countsElem != null) {
            ScreenerTierCounts(
                high = JsonSafe.int(countsElem, "high") ?: 0,
                watch = JsonSafe.int(countsElem, "watch") ?: 0,
                low = JsonSafe.int(countsElem, "low") ?: 0,
                all = JsonSafe.int(countsElem, "all") ?: stocks.size
            )
        } else {
            ScreenerTierCounts(
                high = stocks.count { it.tier == "high" },
                watch = stocks.count { it.tier == "watch" },
                low = stocks.count { it.tier == "low" },
                all = stocks.size
            )
        }
        val meta = if (scan != null) buildMetaLine(scan) else emptyMeta
        return ScreenerUiState(stocks, topReview, counts, meta)
    }

    private fun buildMetaLine(scan: JsonObject): String = buildString {
        val total = JsonSafe.int(scan, "total_raw") ?: 0
        val l1 = JsonSafe.int(scan, "passed_l1") ?: 0
        val high = JsonSafe.int(scan, "high_count") ?: 0
        val watch = JsonSafe.int(scan, "watch_count") ?: 0
        val low = JsonSafe.int(scan, "low_count") ?: 0
        append("$l1 passed L1 of $total")
        append(" · $high high · $watch watch · $low low")
    }

    private fun parseStocks(arr: JsonArray): List<ScreenerStock> {
        val out = mutableListOf<ScreenerStock>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val symbol = JsonSafe.string(o, "symbol")?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            out.add(
                ScreenerStock(
                    symbol = symbol,
                    name = JsonSafe.string(o, "name"),
                    cmp = JsonSafe.double(o, "cmp"),
                    score_total = JsonSafe.double(o, "score_total"),
                    tier = JsonSafe.string(o, "tier"),
                    l1_passed = JsonSafe.int(o, "l1_passed"),
                    layer3 = parseLayer3(o.get("layer3")),
                    user_verified = JsonSafe.int(o, "user_verified")
                )
            )
        }
        return out
    }

    private fun parseTopReview(arr: JsonArray): List<ScreenerTopReview> {
        val out = mutableListOf<ScreenerTopReview>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val symbol = JsonSafe.string(o, "symbol")?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            out.add(
                ScreenerTopReview(
                    symbol = symbol,
                    name = JsonSafe.string(o, "name"),
                    cmp = JsonSafe.double(o, "cmp"),
                    score_total = JsonSafe.double(o, "score_total"),
                    tier = JsonSafe.string(o, "tier"),
                    l1_passed = JsonSafe.bool(o, "l1_passed"),
                    review_badge = JsonSafe.string(o, "review_badge")
                )
            )
        }
        return out
    }

    private fun parseLayer3(el: JsonElement?): Layer3Result? {
        if (el == null || !el.isJsonObject) return null
        val o = el.asJsonObject
        val signals = JsonSafe.arr(o, "signals")?.mapNotNull { JsonSafe.string(it) }
        return Layer3Result(
            status = JsonSafe.string(o, "status"),
            signals = signals,
            score = JsonSafe.int(o, "score")
        )
    }
}
