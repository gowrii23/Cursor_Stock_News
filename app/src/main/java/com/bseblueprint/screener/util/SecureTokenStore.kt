package com.bseblueprint.screener.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API keys stored encrypted on-device — never in the Python/SQLite settings blob. */
object SecureTokenStore {
    private const val FILE = "secure_tokens"
    private const val KEY_HF = "hf_token"
    private const val KEY_GEMINI = "gemini_key"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getHfToken(context: Context): String =
        prefs(context).getString(KEY_HF, "").orEmpty()

    fun getGeminiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI, "").orEmpty()

    fun save(context: Context, hf: String, gemini: String) {
        prefs(context).edit()
            .putString(KEY_HF, hf)
            .putString(KEY_GEMINI, gemini)
            .apply()
    }

    fun hasHfToken(context: Context): Boolean = getHfToken(context).isNotBlank()

    fun hasGeminiKey(context: Context): Boolean = getGeminiKey(context).isNotBlank()
}
