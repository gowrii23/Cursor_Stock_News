package com.bseblueprint.screener.bridge

import android.content.Context
import com.chaquo.python.Python
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

object PythonBridge {
    private val gson = Gson()
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

    fun runDailyScreen(useLive: Boolean = true, forceDemo: Boolean = false): JsonObject {
        val raw = module().callAttr(
            "run_daily_screen",
            dbPath,
            useLive,
            forceDemo
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
}
