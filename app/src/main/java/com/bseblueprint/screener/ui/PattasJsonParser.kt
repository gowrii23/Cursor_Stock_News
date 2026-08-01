package com.bseblueprint.screener.ui

import com.bseblueprint.screener.data.PattasStock
import com.bseblueprint.screener.data.PattasSymbol
import com.bseblueprint.screener.data.PattasUiState
import com.bseblueprint.screener.util.JsonSafe
import com.google.gson.JsonArray
import com.google.gson.JsonObject

object PattasJsonParser {

    fun parseDashboard(dash: JsonObject, emptyMeta: String): PattasUiState {
        val scan = JsonSafe.obj(dash, "scan")
        val stocks = JsonSafe.arr(dash, "stocks")?.let { parseStocks(it) } ?: emptyList()
        val candidates = JsonSafe.arr(dash, "candidates")?.let { parseCandidates(it) } ?: emptyList()
        val symbolCount = JsonSafe.int(dash, "symbol_count") ?: stocks.size
        val meta = if (scan != null) buildMetaLine(scan, stocks.size) else emptyMeta
        return PattasUiState(stocks, candidates, symbolCount, meta)
    }

    private fun buildMetaLine(scan: JsonObject, scored: Int): String = buildString {
        val count = JsonSafe.int(scan, "symbol_count") ?: scored
        append("$count stocks scored")
        val at = JsonSafe.string(scan, "scanned_at")
        if (!at.isNullOrBlank()) {
            append(" · last scan")
        }
    }

    fun parseStocks(arr: JsonArray): List<PattasStock> = parseStockList(arr, isCandidate = false)

    fun parseCandidates(arr: JsonArray): List<PattasStock> = parseStockList(arr, isCandidate = true)

    private fun parseStockList(arr: JsonArray, isCandidate: Boolean): List<PattasStock> {
        val out = mutableListOf<PattasStock>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val symbol = JsonSafe.string(o, "symbol")?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            val pillars = parseBoolMap(JsonSafe.obj(o, "pillars"))
            val medians = parseDoubleMap(JsonSafe.obj(o, "peer_medians"))
            val pattasObj = JsonSafe.obj(o, "pattas")
            val score = JsonSafe.int(o, "pattas_score")
                ?: JsonSafe.int(pattasObj, "pattas_score")
                ?: 0
            out.add(
                PattasStock(
                    symbol = symbol,
                    name = JsonSafe.string(o, "name"),
                    cmp = JsonSafe.double(o, "cmp"),
                    pe = JsonSafe.double(o, "pe"),
                    div_yield = JsonSafe.double(o, "div_yield"),
                    debt_eq = JsonSafe.double(o, "debt_eq"),
                    roe_3y = JsonSafe.double(o, "roe_3y"),
                    ind_pe = JsonSafe.double(o, "ind_pe"),
                    pattas_score = score,
                    pillars = pillars,
                    peer_medians = medians,
                    peer_group_size = JsonSafe.int(o, "peer_group_size")
                        ?: JsonSafe.int(pattasObj, "peer_group_size")
                        ?: 0,
                    used_basket_fallback = JsonSafe.bool(o, "used_basket_fallback")
                        ?: JsonSafe.bool(pattasObj, "used_basket_fallback")
                        ?: false,
                    user_moat_verified = JsonSafe.int(o, "user_moat_verified"),
                    isCandidate = isCandidate
                )
            )
        }
        return out
    }

    fun parseSymbols(arr: JsonArray?): List<PattasSymbol> {
        if (arr == null) return emptyList()
        val out = mutableListOf<PattasSymbol>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val o = el.asJsonObject
            val symbol = JsonSafe.string(o, "symbol")?.trim().orEmpty()
            if (symbol.isEmpty()) continue
            out.add(
                PattasSymbol(
                    symbol = symbol,
                    name = JsonSafe.string(o, "name"),
                    added_date = JsonSafe.string(o, "added_date"),
                    note = JsonSafe.string(o, "note")
                )
            )
        }
        return out
    }

    private fun parseBoolMap(obj: JsonObject?): Map<String, Boolean?> {
        if (obj == null) return emptyMap()
        return obj.entrySet().associate { (k, v) ->
            k to when {
                v.isJsonNull -> null
                v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                else -> null
            }
        }
    }

    private fun parseDoubleMap(obj: JsonObject?): Map<String, Double?> {
        if (obj == null) return emptyMap()
        return obj.entrySet().associate { (k, v) -> k to JsonSafe.double(v) }
    }
}
