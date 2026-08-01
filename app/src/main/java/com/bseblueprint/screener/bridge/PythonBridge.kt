package com.bseblueprint.screener.bridge

import android.content.Context
import com.chaquo.python.Python
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object PythonBridge {
    private val gson = Gson()
    const val SCREENER_DEFAULT_URL = "https://www.screener.in/screens/3835709/cursor/"
    @Volatile private var dbPath: String = ""

    fun init(context: Context) {
        val filesDir = context.filesDir
        dbPath = File(filesDir, "bse_blueprint_screener.db").absolutePath
        // Mirror bundled assets into filesDir so Python can read overrides/defaults
        listOf(
            "universe_nifty100.json",
            "blueprint_tags.json",
            "settings_defaults.json"
        ).forEach { name ->
            val dest = File(filesDir, name)
            if (!dest.exists()) {
                context.assets.open(name).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun module() = Python.getInstance().getModule("pipeline")
    private fun screenerModule() = Python.getInstance().getModule("screener_pipeline")
    private fun swingModule() = Python.getInstance().getModule("swing_pipeline")

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
}
