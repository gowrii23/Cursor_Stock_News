package com.bseblueprint.screener.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bseblueprint.screener.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject

class PattasScanActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var captureButton: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val capturedRows = mutableListOf<Map<String, Any?>>()
    private var symbols: List<String> = emptyList()
    private var currentIndex = 0
    private var capturing = false
    private var extractAttempts = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattas_scan)

        symbols = intent.getStringArrayListExtra(EXTRA_SYMBOLS)?.map { it.uppercase() }.orEmpty()
        if (symbols.isEmpty()) {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        webView = findViewById(R.id.pattasWebView)
        statusText = findViewById(R.id.scanStatus)
        progress = findViewById(R.id.scanProgress)
        captureButton = findViewById(R.id.btnStartCapture)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (capturing) {
                    extractAttempts = 0
                    handler.postDelayed({ injectExtract() }, PAGE_LOAD_DELAY_MS)
                } else {
                    statusText.text = getString(R.string.screener_scan_login_hint)
                }
            }
        }

        captureButton.setOnClickListener { startCapture() }
        statusText.text = getString(
            R.string.pattas_scan_symbol,
            symbols.first(),
            1,
            symbols.size
        )
        loadSymbol(0)
    }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        capturedRows.clear()
        currentIndex = 0
        extractAttempts = 0
        captureButton.isEnabled = false
        progress.visibility = View.VISIBLE
        loadSymbol(0)
    }

    private fun loadSymbol(index: Int) {
        currentIndex = index
        val sym = symbols[index]
        statusText.text = getString(R.string.pattas_scan_symbol, sym, index + 1, symbols.size)
        webView.loadUrl("https://www.screener.in/company/$sym/consolidated/")
    }

    private fun injectExtract() {
        extractAttempts++
        webView.evaluateJavascript(EXTRACT_JS) { raw ->
            handler.post { handleExtractResult(raw) }
        }
    }

    private fun handleExtractResult(raw: String?) {
        if (!capturing) return

        if (raw.isNullOrBlank() || raw == "null") {
            retryExtract("Empty response — retrying…")
            return
        }

        try {
            val jsonText = gson.fromJson(raw, String::class.java)
            val obj = JSONObject(jsonText)
            if (obj.has("error")) {
                val err = obj.getString("error")
                if (obj.optBoolean("retry", false) && extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                    retryExtract(err)
                    return
                }
                failCapture(err)
                return
            }

            val rowObj = obj.optJSONObject("row")
            if (rowObj == null || rowObj.length() < 2) {
                if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                    retryExtract("Ratios not ready — retrying…")
                    return
                }
                failCapture("Could not parse ratios for ${symbols[currentIndex]}")
                return
            }

            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val row: Map<String, Any?> = gson.fromJson(rowObj.toString(), type)
            capturedRows.add(row)

            if (currentIndex + 1 < symbols.size) {
                handler.postDelayed({ loadSymbol(currentIndex + 1) }, PAGE_DELAY_MS)
            } else {
                finishCapture()
            }
        } catch (t: Throwable) {
            if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                retryExtract("Parse error — retrying…")
            } else {
                failCapture("Parse error: ${t.message}")
            }
        }
    }

    private fun retryExtract(message: String) {
        statusText.text = message
        handler.postDelayed({ injectExtract() }, RETRY_DELAY_MS)
    }

    private fun failCapture(message: String) {
        capturing = false
        progress.visibility = View.GONE
        captureButton.isEnabled = true
        statusText.text = message
    }

    private fun finishCapture() {
        capturing = false
        progress.visibility = View.GONE
        statusText.text = getString(R.string.pattas_scan_complete, capturedRows.size)
        val result = Intent().apply {
            putExtra(EXTRA_ROWS_JSON, gson.toJson(capturedRows))
        }
        setResult(RESULT_OK, result)
        handler.postDelayed({ finish() }, 600)
    }

    companion object {
        const val EXTRA_SYMBOLS = "symbols"
        const val EXTRA_ROWS_JSON = "rows_json"
        const val PAGE_DELAY_MS = 2000L
        private const val PAGE_LOAD_DELAY_MS = 2000L
        private const val RETRY_DELAY_MS = 1500L
        private const val MAX_EXTRACT_ATTEMPTS = 10

        private const val EXTRACT_JS = """
            (function() {
              try {
                var row = {};
                var symMatch = window.location.pathname.match(/\/company\/([^/]+)\//);
                if (symMatch) row.symbol = symMatch[1].toUpperCase();
                var items = document.querySelectorAll('li.flex.flex-space-between');
                items.forEach(function(li) {
                  var nameEl = li.querySelector('.name');
                  var numEl = li.querySelector('.number');
                  if (!nameEl || !numEl) return;
                  var name = nameEl.innerText.trim();
                  var val = numEl.innerText.trim();
                  if (name === 'Stock P/E') row['P/E'] = val;
                  else if (name === 'Dividend Yield') row['Div Yld %'] = val;
                  else if (name === 'Current Price') row['CMP Rs.'] = val;
                  else if (name === 'ROCE') row['ROCE %'] = val;
                  else if (name === 'ROE') row['ROE %'] = val;
                  else if (name === 'Market Cap') row['Mar Cap Rs.Cr.'] = val;
                });
                var tables = document.querySelectorAll('table.ranges-table tr');
                tables.forEach(function(tr) {
                  var cells = tr.querySelectorAll('td');
                  if (cells.length >= 2 && cells[0].innerText.indexOf('3 Years') >= 0) {
                    row['ROE 3Yr %'] = cells[1].innerText.trim();
                  }
                });
                if (!row['P/E']) {
                  return JSON.stringify({error: 'Ratio panel not visible yet', retry: true});
                }
                return JSON.stringify({row: row});
              } catch(e) {
                return JSON.stringify({error: e.message, retry: false});
              }
            })();
        """
    }
}
