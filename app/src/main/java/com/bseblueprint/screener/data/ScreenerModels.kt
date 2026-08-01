package com.bseblueprint.screener.data

data class ScreenerStock(
    val symbol: String = "",
    val name: String? = null,
    val cmp: Double? = null,
    val score_total: Double? = null,
    val tier: String? = null,
    val l1_passed: Int? = null,
    val l1_fails: List<String>? = null,
    val score_breakdown: Map<String, Double>? = null,
    val layer3: Layer3Result? = null,
    val manual_notes: List<String>? = null,
    val raw_columns: Map<String, Any>? = null,
    val user_verified: Int? = null,
    val blueprint_tags: List<String> = emptyList(),
    val blueprint_match: Boolean = false,
    val blueprint_bonus: Double? = null
)

data class ScreenerTopReview(
    val symbol: String = "",
    val name: String? = null,
    val cmp: Double? = null,
    val score_total: Double? = null,
    val tier: String? = null,
    val l1_passed: Boolean? = null,
    val review_badge: String? = null
)

data class ScreenerTierCounts(
    val high: Int = 0,
    val watch: Int = 0,
    val low: Int = 0,
    val all: Int = 0,
    val blueprint: Int = 0
)

data class ScreenerUiState(
    val stocks: List<ScreenerStock> = emptyList(),
    val topReview: List<ScreenerTopReview> = emptyList(),
    val counts: ScreenerTierCounts = ScreenerTierCounts(),
    val metaLine: String = ""
)

data class Layer3Result(
    val status: String? = null,
    val signals: List<String>? = null,
    val score: Int? = null
)

data class ScreenerScanMeta(
    val id: Int? = null,
    val scanned_at: String? = null,
    val source_url: String? = null,
    val total_raw: Int? = null,
    val passed_l1: Int? = null,
    val high_count: Int? = null,
    val watch_count: Int? = null,
    val message: String? = null
)

enum class ScreenerTierFilter {
    HIGH, WATCH, LOW, ALL
}

/** Independent of tier — AND with tier filter; does not change High/Watch/Low. */
enum class ScreenerThemeFilter {
    ALL, BLUEPRINT_ONLY
}
