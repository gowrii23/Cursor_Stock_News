package com.bseblueprint.screener.data

data class SwingHit(
    val symbol: String = "",
    val name: String? = null,
    val screen: String? = null,
    val close: Double? = null,
    val score: Double? = null,
    val signals: List<String>? = null,
    val metrics: Map<String, Double?>? = null,
    val also_screens: List<String>? = null,
    val as_of: String? = null
)

data class SwingRegime(
    val state: String = "insufficient",
    val bullish: Boolean = false,
    val label: String = "",
    val asOf: String? = null
)

data class SwingCounts(
    val momentum: Int = 0,
    val sleeping: Int = 0,
    val all: Int = 0
)

data class SwingCoverage(
    val pricedCount: Int = 0,
    val universeSize: Int = 0,
    val asOf: String? = null,
    val topN: Int = 8,
    val totalHits: Int = 0
)

data class SwingUiState(
    val hits: List<SwingHit> = emptyList(),
    val regime: SwingRegime = SwingRegime(),
    val counts: SwingCounts = SwingCounts(),
    val coverage: SwingCoverage = SwingCoverage(),
    val metaLine: String = ""
)

enum class SwingScreenFilter {
    ALL, MOMENTUM, SLEEPING
}
