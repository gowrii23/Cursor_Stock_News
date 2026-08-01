package com.bseblueprint.screener.data

data class SwingHit(
    val symbol: String = "",
    val name: String? = null,
    val screen: String? = null,
    val close: Double? = null,
    val score: Double? = null,
    val signals: List<String>? = null,
    val metrics: Map<String, Double>? = null
)

data class SwingRegime(
    val bullish: Boolean = false,
    val label: String = ""
)

data class SwingCounts(
    val momentum: Int = 0,
    val sleeping: Int = 0,
    val all: Int = 0
)

data class SwingUiState(
    val hits: List<SwingHit> = emptyList(),
    val regime: SwingRegime = SwingRegime(),
    val counts: SwingCounts = SwingCounts(),
    val metaLine: String = ""
)

enum class SwingScreenFilter {
    ALL, MOMENTUM, SLEEPING
}
