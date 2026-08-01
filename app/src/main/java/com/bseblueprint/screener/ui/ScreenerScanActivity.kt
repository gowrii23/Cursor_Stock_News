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
import com.bseblueprint.screener.bridge.ScreenerJsBridge
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import org.json.JSONArray
import org.json.JSONObject

class ScreenerScanActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var captureButton: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val allRows = mutableListOf<Map<String, Any?>>()
    private var currentPage = 0
    private var totalPages = 25
    private var capturing = false
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
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(
            ScreenerJsBridge { json -> handler.post { onPageJson(json) } },
            "ScreenerBridge"
        )
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (capturing) {
                    handler.postDelayed({ injectExtract() }, 800)
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
        currentPage = 0
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
        webView.evaluateJavascript(EXTRACT_JS, null)
    }

    private fun onPageJson(json: String) {
        try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                statusText.text = obj.getString("error")
                return
            }
            val rowsArr = obj.optJSONArray("rows") ?: JSONArray()
            if (rowsArr.length() == 0) {
                finishCapture()
                return
            }
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            val pageRows: List<Map<String, Any?>> = gson.fromJson(rowsArr.toString(), type)
            allRows.addAll(pageRows)
            val page = obj.optInt("page", currentPage)
            statusText.text = getString(
                R.string.screener_scan_rows,
                page,
                totalPages,
                allRows.size
            )
            if (page < totalPages && rowsArr.length() >= 10) {
                handler.postDelayed({ loadPage(page + 1) }, PAGE_DELAY_MS)
            } else {
                finishCapture()
            }
        } catch (t: Throwable) {
            statusText.text = "Parse error: ${t.message}"
            captureButton.isEnabled = true
            capturing = false
        }
    }

    private fun finishCapture() {
        capturing = false
        progress.visibility = View.GONE
        statusText.text = getString(R.string.screener_scan_complete, allRows.size)
        val result = intent
        result.putExtra(EXTRA_ROWS_JSON, gson.toJson(allRows))
        setResult(RESULT_OK, result)
        handler.postDelayed({ finish() }, 600)
    }

    companion object {
        const val EXTRA_ROWS_JSON = "rows_json"
        const val DEFAULT_URL = "https://www.screener.in/screens/3835709/cursor/"
        const val PAGE_DELAY_MS = 2500L
        private const val EXTRACT_JS = """
            (function() {
              try {
                var table = document.querySelector('table.data-table') || document.querySelector('table');
                if (!table) return JSON.stringify({error: 'No results table — log in to screener.in first'});
                var headers = [];
                var ths = table.querySelectorAll('thead th');
                if (ths.length === 0) ths = table.querySelectorAll('tr:first-child th, tr:first-child td');
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
                    row.symbol = parts.length >= 2 ? parts[1].toUpperCase() : '';
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
                ScreenerBridge.onPageData(JSON.stringify({rows: rows, page: page, count: rows.length}));
              } catch(e) {
                ScreenerBridge.onPageData(JSON.stringify({error: e.message}));
              }
            })();
        """
    }
}
