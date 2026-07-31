package com.bseblueprint.screener.data

data class WatchlistItem(
    val ticker: String = "",
    val date: String? = null,
    val z_score: Double? = null,
    val idiosyncratic_return: Double? = null,
    val headline: String? = null,
    val source: String? = null,
    val severity_tag: String? = null,
    val blueprint_tags: List<String> = emptyList(),
    val conviction_score: Double? = null,
    val beta_1y: Double? = null,
    val alpha_1y: Double? = null,
    val alpha_percentile: Double? = null
)

data class NewsItem(
    val ticker: String? = null,
    val date: String? = null,
    val headline: String? = null,
    val source: String? = null,
    val url: String? = null,
    val severity_tag: String? = null
)

data class MetricPoint(
    val ticker: String? = null,
    val date: String? = null,
    val close: Double? = null,
    val daily_return: Double? = null,
    val beta_1y: Double? = null,
    val alpha_1y: Double? = null,
    val alpha_3y: Double? = null
)
