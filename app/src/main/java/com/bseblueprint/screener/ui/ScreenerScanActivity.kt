package com.bseblueprint.screener.ui

import android.annotation.SuppressLint
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

class ScreenerScanActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var captureButton: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val allRows = mutableListOf<Map<String, Any?>>()
    private var currentPage = 1
    private var totalPages = 25
    private var capturing = false
    private var extractAttempts = 0
    private val baseUrl = DEFAULT_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screener_scan)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        webView = findViewById(R.id.screenerWebView)
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
        webView.loadUrl(baseUrl)
    }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        allRows.clear()
        currentPage = 1
        extractAttempts = 0
        captureButton.isEnabled = false
        progress.visibility = View.VISIBLE
        statusText.text = getString(R.string.screener_scan_starting)
        loadPage(1)
    }

    private fun loadPage(page: Int) {
        currentPage = page
        val url = if (page <= 1) baseUrl else "$baseUrl?page=$page"
        statusText.text = getString(R.string.screener_scan_page, page, totalPages)
        webView.loadUrl(url)
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
            retryExtract("Empty response from page — retrying…")
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

            if (obj.has("totalPages")) {
                totalPages = obj.optInt("totalPages", totalPages).coerceAtLeast(1)
            }

            val rowsArr = obj.optJSONArray("rows")
            val rowCount = rowsArr?.length() ?: 0
            if (rowCount == 0) {
                if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                    retryExtract("Table not ready — retrying…")
                    return
                }
                if (currentPage == 1) {
                    failCapture("No rows found. Log in to screener.in, then try again.")
                    return
                }
                finishCapture()
                return
            }

            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val pageRows: List<Map<String, Any?>> = gson.fromJson(rowsArr.toString(), type)
            allRows.addAll(pageRows)

            val page = obj.optInt("page", currentPage)
            statusText.text = getString(R.string.screener_scan_rows, page, totalPages, allRows.size)

            if (page < totalPages && rowCount >= 5) {
                handler.postDelayed({ loadPage(page + 1) }, PAGE_DELAY_MS)
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
        statusText.text = getString(R.string.screener_scan_complete, allRows.size)
        intent.putExtra(EXTRA_ROWS_JSON, gson.toJson(allRows))
        setResult(RESULT_OK, intent)
        handler.postDelayed({ finish() }, 600)
    }

    companion object {
        const val EXTRA_ROWS_JSON = "rows_json"
        const val DEFAULT_URL = "https://www.screener.in/screens/3835709/cursor/"
        const val PAGE_DELAY_MS = 2500L
        private const val PAGE_LOAD_DELAY_MS = 2000L
        private const val RETRY_DELAY_MS = 1500L
        private const val MAX_EXTRACT_ATTEMPTS = 12

        private const val EXTRACT_JS = """
            (function() {
              try {
                var table = document.querySelector('table.data-table')
                  || document.querySelector('.responsive-holder table')
                  || document.querySelector('table.data-table')
                  || document.querySelector('table');
                if (!table) {
                  return JSON.stringify({error: 'Results table not visible yet', retry: true});
                }
                var headers = [];
                var ths = table.querySelectorAll('thead th');
                if (ths.length === 0) {
                  var headerRow = table.querySelector('tr');
                  if (headerRow) ths = headerRow.querySelectorAll('th, td');
                }
                ths.forEach(function(th) { headers.push(th.innerText.trim()); });
                var rows = [];
                table.querySelectorAll('tbody tr').forEach(function(tr) {
                  var cells = tr.querySelectorAll('td');
                  if (cells.length < 2) return;
                  var row = {};
                  var link = tr.querySelector('a[href*="/company/"]');
                  if (link) {
                    var href = link.getAttribute('href') || '';
                    var parts = href.split('/').filter(Boolean);
                    for (var i = 0; i < parts.length; i++) {
                      if (parts[i] === 'company' && i + 1 < parts.length) {
                        row.symbol = parts[i + 1].toUpperCase();
                        break;
                      }
                    }
                    row.name = link.innerText.trim();
                  }
                  cells.forEach(function(td, i) {
                    var key = i < headers.length ? headers[i] : ('col' + i);
                    row[key] = td.innerText.trim();
                  });
                  if (row.name || row.symbol) rows.push(row);
                });
                var page = 1;
                var m = window.location.search.match(/page=(\d+)/);
                if (m) page = parseInt(m[1]);
                var totalPages = 25;
                var bodyText = document.body ? document.body.innerText : '';
                var pm = bodyText.match(/page\s+\d+\s+of\s+(\d+)/i);
                if (pm) totalPages = parseInt(pm[1]);
                return JSON.stringify({rows: rows, page: page, count: rows.length, totalPages: totalPages});
              } catch(e) {
                return JSON.stringify({error: e.message, retry: false});
              }
            })();
        """
    }
}
