package com.bseblueprint.screener.data

data class PattasStock(
    val symbol: String = "",
    val name: String? = null,
    val cmp: Double? = null,
    val pe: Double? = null,
    val div_yield: Double? = null,
    val debt_eq: Double? = null,
    val roe_3y: Double? = null,
    val ind_pe: Double? = null,
    val pattas_score: Int = 0,
    val pillars: Map<String, Boolean?> = emptyMap(),
    val peer_medians: Map<String, Double?> = emptyMap(),
    val peer_group_size: Int = 0,
    val used_basket_fallback: Boolean = false,
    val user_moat_verified: Int? = null,
    val isCandidate: Boolean = false
)

data class PattasSymbol(
    val symbol: String = "",
    val name: String? = null,
    val added_date: String? = null,
    val note: String? = null
)

data class PattasUiState(
    val stocks: List<PattasStock> = emptyList(),
    val candidates: List<PattasStock> = emptyList(),
    val symbolCount: Int = 0,
    val metaLine: String = ""
)
