package com.bseblueprint.screener.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Null-safe Gson accessors. Gson returns JsonNull (not Kotlin null) for JSON null,
 * so `get("x")?.asString` still throws UnsupportedOperationException("JsonNull").
 */
object JsonSafe {

    fun obj(parent: JsonObject?, key: String): JsonObject? {
        val el = parent?.get(key) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
    }

    fun arr(parent: JsonObject?, key: String): JsonArray? {
        val el = parent?.get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray else null
    }

    fun string(el: JsonElement?): String? {
        if (el == null || el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive -> el.asString
                else -> el.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun string(parent: JsonObject?, key: String): String? = string(parent?.get(key))

    fun int(el: JsonElement?): Int? {
        if (el == null || el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt
                el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> if (el.asBoolean) 1 else 0
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.toIntOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun int(parent: JsonObject?, key: String): Int? = int(parent?.get(key))

    fun double(el: JsonElement?): Double? {
        if (el == null || el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asDouble
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.toDoubleOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun double(parent: JsonObject?, key: String): Double? = double(parent?.get(key))

    fun bool(el: JsonElement?): Boolean? {
        if (el == null || el.isJsonNull) return null
        return try {
            when {
                el.isJsonPrimitive && el.asJsonPrimitive.isBoolean -> el.asBoolean
                el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asInt != 0
                el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString.toBooleanStrictOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun bool(parent: JsonObject?, key: String): Boolean? = bool(parent?.get(key))
}
