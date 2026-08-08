package com.bseblueprint.screener.util

import com.google.gson.JsonObject

/** Build provenance footer line from Ask AI sources_used JSON. */
object AskAiSourceFooter {

    fun format(sources: JsonObject?): String {
        if (sources == null) return ""
        val cached = JsonSafe.bool(sources, "cached") == true
        if (cached) return "Cached today · tap Re-ask to refresh"

        val qual = JsonSafe.string(sources, "qual_status").orEmpty()
        val qualDetail = JsonSafe.string(sources, "qual_detail").orEmpty()
        val period = JsonSafe.string(sources, "concall_date").orEmpty()
        val chars = JsonSafe.int(sources, "transcript_chars") ?: 0
        val method = JsonSafe.string(sources, "transcript_method").orEmpty()
        val kChars = if (chars > 0) "${chars / 1000}k" else "0"

        return when (qual) {
            "used" -> buildString {
                if (period.isNotBlank()) append("Concall: $period · ")
                append("transcript $kChars chars")
                if (method.isNotBlank()) append(" ($method)")
                append(" · Gemini used")
            }
            "skipped_no_key" -> withDetail("Qual: skipped (no Gemini key)", qualDetail)
            "skipped_rate_limit" -> withDetail("Qual: skipped (Gemini rate limit)", qualDetail)
            "skipped_disabled" -> withDetail("Qual: skipped (Gemini off)", qualDetail)
            "skipped_no_transcript" -> "Transcript: unavailable · quant + announcements only"
            "skipped_gemini_error" -> withDetail(
                "Qual: skipped (Gemini error) · transcript $kChars chars",
                qualDetail
            )
            else -> {
                if (chars > 0) "Transcript $kChars chars ($method) · qual not run"
                else "Quant-only · no transcript extracted"
            }
        }
    }

    private fun withDetail(prefix: String, detail: String): String {
        val trimmed = detail.trim()
        return if (trimmed.isBlank()) prefix else "$prefix — $trimmed"
    }

    fun formatQualContext(qual: JsonObject?): String {
        if (qual == null) return ""
        val parts = listOf(
            "management_tone" to "Tone",
            "key_guidance" to "Guidance",
            "risks_mentioned" to "Risks",
            "qualitative_flags" to "Flags",
            "summary_text" to "Summary"
        )
        val lines = parts.mapNotNull { (key, label) ->
            val v = JsonSafe.string(qual, key)?.trim().orEmpty()
            if (v.isBlank()) null else "$label: $v"
        }
        return lines.joinToString("\n")
    }
}
