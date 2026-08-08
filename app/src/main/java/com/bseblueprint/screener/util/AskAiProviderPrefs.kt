package com.bseblueprint.screener.util

import android.content.Context

/** Per-session Ask AI provider toggles (near the Ask AI buttons). */
object AskAiProviderPrefs {
    private const val FILE = "ask_ai_provider_prefs"
    private const val KEY_HF = "hf_enabled"
    private const val KEY_GEMINI = "gemini_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isHfEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HF, true)

    fun isGeminiEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GEMINI, true)

    fun setHfEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HF, enabled).apply()
    }

    fun setGeminiEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GEMINI, enabled).apply()
    }
}
