package com.bseblueprint.screener.ui

import com.bseblueprint.screener.data.Layer3Result
import com.bseblueprint.screener.data.ScreenerStock
import com.bseblueprint.screener.data.ScreenerTierCounts
import com.bseblueprint.screener.data.ScreenerTopReview
import com.bseblueprint.screener.data.ScreenerUiState
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

object ScreenerJsonParser {

    fun parseDashboard(dash: JsonObject, emptyMeta: String): ScreenerUiState {
        val scan = dash.get("scan")?.takeIf { it.isJsonObject }?.asJsonObject
        val stocks = dash.get("stocks")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.let { parseStocks(it) } ?: emptyList()
        val topReview = dash.get("top_review")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.let { parseTopReview(it) } ?: emptyList()
        val countsElem = dash.get("counts")?.takeIf { it.isJsonObject }?.asJsonObject
        val counts = if (countsElem != null) {
            ScreenerTierCounts(
                high = countsElem.get("high")?.asInt ?: 0,
                watch = countsElem.get("watch")?.asInt ?: 0,
                low = countsElem.get("low")?.asInt ?: 0,
                all = countsElem.get("all")?.asInt ?: stocks.size
            )
        } else {
            ScreenerTierCounts(
                high = stocks.count { it.tier == "high" },
                watch = stocks.count { it.tier == "watch" },
                low = stocks.count { it.tier == "low" },
                all = stocks.size
            )
        }
        val meta = if (scan != null && !scan.isJsonNull) {
            buildMetaLine(scan)
        } else {
            emptyMeta
        }
        return ScreenerUiState(stocks, topReview, counts, meta)
    }

    private fun buildMetaLine(scan: JsonObject): String = buildString {
        val total = scan.get("total_raw")?.asInt ?: 0
        val l1 = scan.get("passed_l1")?.asInt ?: 0
        val high = scan.get("high_count")?.asInt ?: 0
        val watch = scan.get("watch_count")?.asInt ?: 0
        val low = scan.get("low_count")?.asInt ?: 0
        append("$l1 passed L1 of $total")
        append(" · $high high · $watch watch · $low low")
    }

    private fun parseStocks(arr: JsonArray): List<ScreenerStock> {
        val out = mutableListOf<ScreenerStock>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val symbol = o.get("symbol")?.asString?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            out.add(
                ScreenerStock(
                    symbol = symbol,
                    name = o.get("name")?.asString,
                    cmp = o.get("cmp")?.asDoubleOrNull(),
                    score_total = o.get("score_total")?.asDoubleOrNull(),
                    tier = o.get("tier")?.asString,
                    l1_passed = o.get("l1_passed")?.asBoolInt(),
                    layer3 = parseLayer3(o.get("layer3")),
                    user_verified = o.get("user_verified")?.asInt
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
            val symbol = o.get("symbol")?.asString?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            out.add(
                ScreenerTopReview(
                    symbol = symbol,
                    name = o.get("name")?.asString,
                    cmp = o.get("cmp")?.asDoubleOrNull(),
                    score_total = o.get("score_total")?.asDoubleOrNull(),
                    tier = o.get("tier")?.asString,
                    l1_passed = o.get("l1_passed")?.asBool(),
                    review_badge = o.get("review_badge")?.asString
                )
            )
        }
        return out
    }

    private fun parseLayer3(el: JsonElement?): Layer3Result? {
        if (el == null || !el.isJsonObject) return null
        val o = el.asJsonObject
        val signals = o.get("signals")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.asString }
        return Layer3Result(
            status = o.get("status")?.asString,
            signals = signals,
            score = o.get("score")?.asInt
        )
    }

    private fun JsonElement.asDoubleOrNull(): Double? {
        if (isJsonNull) return null
        return try {
            asDouble
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonElement.asBoolInt(): Int? {
        if (isJsonNull) return null
        return when {
            isJsonPrimitive && asJsonPrimitive.isBoolean -> if (asBoolean) 1 else 0
            isJsonPrimitive && asJsonPrimitive.isNumber -> asInt
            else -> null
        }
    }

    private fun JsonElement.asBool(): Boolean? {
        if (isJsonNull) return null
        return when {
            isJsonPrimitive && asJsonPrimitive.isBoolean -> asBoolean
            isJsonPrimitive && asJsonPrimitive.isNumber -> asInt != 0
            else -> null
        }
    }
}
