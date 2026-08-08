package com.bseblueprint.screener.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bseblueprint.screener.R
import com.bseblueprint.screener.bridge.PythonBridge
import com.bseblueprint.screener.util.JsonSafe
import com.bseblueprint.screener.util.SecureTokenStore
import com.bseblueprint.screener.work.DailyScreenScheduler
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val edtZ = findViewById<TextInputEditText>(R.id.edtZThreshold)
        val edtMinIdio = findViewById<TextInputEditText>(R.id.edtMinIdio)
        val edtBeta = findViewById<TextInputEditText>(R.id.edtBetaThreshold)
        val edtExclude = findViewById<TextInputEditText>(R.id.edtExcludeKeywords)
        val edtCandidate = findViewById<TextInputEditText>(R.id.edtCandidateKeywords)
        val edtBlueprint = findViewById<TextInputEditText>(R.id.edtBlueprintJson)
        val edtHfToken = findViewById<TextInputEditText>(R.id.edtHfToken)
        val edtGeminiKey = findViewById<TextInputEditText>(R.id.edtGeminiKey)
        val swWifi = findViewById<MaterialSwitch>(R.id.swRequireWifi)
        val swCharge = findViewById<MaterialSwitch>(R.id.swRequireCharging)
        val btnSave = findViewById<MaterialButton>(R.id.btnSave)
        val btnRun = findViewById<MaterialButton>(R.id.btnRunNow)

        lifecycleScope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { PythonBridge.getSettings() }
                edtZ.setText(jsonNumber(settings, "z_threshold", "-1.5"))
                edtMinIdio.setText(jsonNumber(settings, "min_idio_return", "-0.015"))
                edtBeta.setText(jsonNumber(settings, "beta_low_threshold", "0.8"))
                val excl = JsonSafe.arr(settings, "exclude_keywords")
                    ?.mapNotNull { JsonSafe.string(it) }
                    ?.joinToString("\n")
                    ?: ""
                val cand = JsonSafe.arr(settings, "candidate_keywords")
                    ?.mapNotNull { JsonSafe.string(it) }
                    ?.joinToString("\n")
                    ?: ""
                edtExclude.setText(excl)
                edtCandidate.setText(cand)
                val bp = settings.get("blueprint_tags")
                edtBlueprint.setText(
                    if (bp != null && !bp.isJsonNull) bp.toString() else "{}"
                )
                edtHfToken.setText(SecureTokenStore.getHfToken(this@SettingsActivity))
                edtGeminiKey.setText(SecureTokenStore.getGeminiKey(this@SettingsActivity))
                swWifi.isChecked = jsonBool(settings, "require_wifi", true)
                swCharge.isChecked = jsonBool(settings, "require_charging", false)
            } catch (t: Throwable) {
                Toast.makeText(this@SettingsActivity, t.message, Toast.LENGTH_LONG).show()
            }
        }

        btnSave.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val payload = mapOf(
                        "z_threshold" to (edtZ.text?.toString()?.toDoubleOrNull() ?: -1.5),
                        "min_idio_return" to (edtMinIdio.text?.toString()?.toDoubleOrNull() ?: -0.015),
                        "beta_low_threshold" to (edtBeta.text?.toString()?.toDoubleOrNull() ?: 0.8),
                        "exclude_keywords" to edtExclude.text?.toString()
                            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                        "candidate_keywords" to edtCandidate.text?.toString()
                            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                        "require_wifi" to swWifi.isChecked,
                        "require_charging" to swCharge.isChecked,
                        "blueprint_tags" to com.google.gson.JsonParser
                            .parseString(edtBlueprint.text?.toString() ?: "{}")
                            .asJsonObject
                            .entrySet()
                            .associate { (k, v) ->
                                k to (if (v.isJsonArray) {
                                    v.asJsonArray.mapNotNull { JsonSafe.string(it) }
                                } else {
                                    emptyList()
                                })
                            }
                    )
                    withContext(Dispatchers.IO) { PythonBridge.saveSettings(payload) }
                    SecureTokenStore.save(
                        this@SettingsActivity,
                        hf = edtHfToken.text?.toString()?.trim().orEmpty(),
                        gemini = edtGeminiKey.text?.toString()?.trim().orEmpty()
                    )
                    getSharedPreferences("screener_prefs", MODE_PRIVATE).edit()
                        .putBoolean("require_wifi", swWifi.isChecked)
                        .putBoolean("require_charging", swCharge.isChecked)
                        .apply()
                    DailyScreenScheduler.ensureScheduled(this@SettingsActivity)
                    Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Toast.makeText(this@SettingsActivity, "Save failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnRun.setOnClickListener {
            DailyScreenScheduler.runNow(this)
            Toast.makeText(this, "Daily screen queued", Toast.LENGTH_SHORT).show()
        }
    }

    private fun jsonNumber(
        obj: com.google.gson.JsonObject,
        key: String,
        fallback: String
    ): String {
        val el = obj.get(key) ?: return fallback
        return when {
            el.isJsonNull -> fallback
            el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asNumber.toString()
            el.isJsonPrimitive -> JsonSafe.string(el) ?: fallback
            else -> fallback
        }
    }

    private fun jsonBool(
        obj: com.google.gson.JsonObject,
        key: String,
        fallback: Boolean
    ): Boolean = JsonSafe.bool(obj, key) ?: fallback
}
