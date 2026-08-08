package com.bseblueprint.screener.bridge

import android.content.Context
import com.bseblueprint.screener.util.SecureTokenStore
import com.chaquo.python.Python
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object PythonBridge {
    private val gson = Gson()
    const val SCREENER_DEFAULT_URL = "https://www.screener.in/screens/3835709/cursor/"
    const val HF_MODEL = "Qwen/Qwen2.5-7B-Instruct"
    @Volatile private var dbPath: String = ""
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val filesDir = appCtx.filesDir
        dbPath = File(filesDir, "bse_blueprint_screener.db").absolutePath
        // Mirror bundled assets into filesDir so Python can read overrides/defaults
        listOf(
            "universe_nifty100.json",
            "blueprint_tags.json",
            "settings_defaults.json",
            "pattas_universe.json"
        ).forEach { name ->
            val dest = File(filesDir, name)
            if (!dest.exists()) {
                appCtx.assets.open(name).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        migrateLegacyHfToken(appCtx)
    }

    private fun migrateLegacyHfToken(context: Context) {
        if (SecureTokenStore.hasHfToken(context)) return
        try {
            val raw = module().callAttr("pop_legacy_hf_token_json", dbPath).toString()
            val token = JsonParser.parseString(raw).asJsonObject
                .get("token")?.asString.orEmpty().trim()
            if (token.isNotEmpty()) {
                SecureTokenStore.save(
                    context,
                    hf = token,
                    gemini = SecureTokenStore.getGeminiKey(context)
                )
            }
        } catch (_: Throwable) {
            // Non-fatal — user can re-enter token in Settings
        }
    }

    private fun requireContext(): Context =
        appContext ?: error("PythonBridge.init() must be called before use")

    private fun module() = Python.getInstance().getModule("pipeline")
    private fun screenerModule() = Python.getInstance().getModule("screener_pipeline")
    private fun swingModule() = Python.getInstance().getModule("swing_pipeline")
    private fun pattasModule() = Python.getInstance().getModule("pattas_pipeline")

    fun runDailyScreen(
        useLive: Boolean = true,
        forceDemo: Boolean = false,
        reporter: RunProgressReporter? = null
    ): JsonObject {
        val raw = module().callAttr(
            "run_daily_screen",
            dbPath,
            useLive,
            forceDemo,
            reporter
        ).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getDashboard(): JsonObject {
        val raw = module().callAttr("get_dashboard_json", dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getStockDetail(ticker: String): JsonObject {
        val raw = module().callAttr("get_stock_detail_json", ticker, dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getNews(ticker: String? = null): JsonObject {
        val raw = module().callAttr("get_news_json", ticker, dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getSettings(): JsonObject {
        val raw = module().callAttr("get_settings_json", dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun saveSettings(payload: Map<String, Any?>): JsonObject {
        val json = gson.toJson(payload)
        val raw = module().callAttr("save_settings_json", json, dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun processScreenerCapture(
        rowsJson: String,
        reporter: RunProgressReporter? = null
    ): JsonObject {
        val raw = screenerModule().callAttr(
            "process_screener_capture",
            rowsJson,
            dbPath,
            SCREENER_DEFAULT_URL,
            reporter
        ).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getScreenerDashboard(): JsonObject {
        val raw = screenerModule().callAttr("get_screener_dashboard_json", dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getScreenerDetail(symbol: String): JsonObject {
        val raw = screenerModule().callAttr("get_screener_detail_json", symbol, dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun setScreenerVerified(symbol: String, verified: Boolean): JsonObject {
        val raw = screenerModule().callAttr(
            "set_screener_verified_json",
            symbol,
            verified,
            dbPath
        ).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun runSwingScreen(reporter: RunProgressReporter? = null): JsonObject {
        val raw = swingModule().callAttr("run_swing_screen", dbPath, reporter).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getSwingDashboard(): JsonObject {
        val raw = swingModule().callAttr("get_swing_dashboard_json", dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getSwingDetail(symbol: String): JsonObject {
        val raw = swingModule().callAttr("get_swing_detail_json", symbol, dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun startPattasScan(reporter: RunProgressReporter? = null): JsonObject {
        val raw = pattasModule().callAttr("start_pattas_scan", dbPath, reporter).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun finishPattasScanWithWebviewRows(
        capturedRowsJson: String,
        webviewRowsJson: String,
        reporter: RunProgressReporter? = null
    ): JsonObject {
        val raw = pattasModule().callAttr(
            "finish_pattas_scan_with_webview_rows",
            capturedRowsJson,
            webviewRowsJson,
            dbPath,
            reporter
        ).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getPattasDashboard(): JsonObject {
        val raw = pattasModule().callAttr("get_pattas_dashboard_json", dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getPattasDetail(symbol: String): JsonObject {
        val raw = pattasModule().callAttr("get_pattas_detail_json", symbol, dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun setPattasMoatVerified(symbol: String, verified: Boolean): JsonObject {
        val raw = pattasModule().callAttr(
            "set_pattas_moat_verified_json",
            symbol,
            verified,
            dbPath
        ).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getPattasSymbolsJson(): JsonObject {
        val raw = pattasModule().callAttr("get_pattas_symbols_json", dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun addPattasSymbol(symbol: String, name: String? = null): JsonObject {
        val raw = pattasModule().callAttr(
            "add_pattas_symbol_json",
            symbol,
            name,
            null,
            dbPath
        ).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun removePattasSymbol(symbol: String): JsonObject {
        val raw = pattasModule().callAttr("remove_pattas_symbol_json", symbol, dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun getPattasCandidatesJson(): JsonObject {
        val raw = pattasModule().callAttr("get_pattas_candidates_json", dbPath).toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun askAiVerdict(
        symbol: String,
        forceRefresh: Boolean = false,
        reporter: RunProgressReporter? = null,
        webviewTranscriptText: String = "",
        webviewPdfBase64: String = "",
        useHf: Boolean = true,
        useGemini: Boolean = true
    ): JsonObject {
        val ctx = requireContext()
        val raw = Python.getInstance().getModule("llm_advisor")
            .callAttr(
                "ask_ai_verdict_json",
                symbol,
                SecureTokenStore.getHfToken(ctx),
                SecureTokenStore.getGeminiKey(ctx),
                dbPath,
                forceRefresh,
                reporter,
                webviewTranscriptText,
                webviewPdfBase64,
                useHf,
                useGemini
            )
            .toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun clearAiCache(symbol: String): JsonObject {
        val raw = Python.getInstance().getModule("llm_advisor")
            .callAttr("clear_ai_cache_json", symbol, dbPath)
            .toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun clearAllAiCaches(): JsonObject {
        val raw = Python.getInstance().getModule("llm_advisor")
            .callAttr("clear_all_ai_caches_json", dbPath)
            .toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun testGeminiKey(): JsonObject {
        val ctx = requireContext()
        val raw = Python.getInstance().getModule("llm_advisor")
            .callAttr("test_gemini_key_json", SecureTokenStore.getGeminiKey(ctx))
            .toString()
        return JsonParser.parseString(raw).asJsonObject
    }

    fun hfTokenStatus(): JsonObject {
        val ctx = appContext
        return JsonObject().apply {
            addProperty("has_token", ctx != null && SecureTokenStore.hasHfToken(ctx))
            addProperty("has_gemini_key", ctx != null && SecureTokenStore.hasGeminiKey(ctx))
            addProperty("model", HF_MODEL)
        }
    }
}
