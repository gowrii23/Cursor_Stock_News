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
    private lateinit var loginButton: MaterialButton
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
        loginButton = findViewById(R.id.btnOpenLogin)

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
                } else if (url?.contains("/login") == true) {
                    statusText.text = getString(R.string.pattas_scan_login_step)
                }
            }
        }

        loginButton.setOnClickListener { loadLoginPage() }
        captureButton.setOnClickListener { startCapture() }
        statusText.text = getString(R.string.pattas_scan_login_step)
        loadLoginPage()
    }

    private fun loadLoginPage() {
        capturing = false
        progress.visibility = View.GONE
        captureButton.isEnabled = true
        webView.loadUrl(LOGIN_URL)
    }

    private fun startCapture() {
        if (capturing) return
        capturing = true
        capturedRows.clear()
        currentIndex = 0
        extractAttempts = 0
        captureButton.isEnabled = false
        loginButton.isEnabled = false
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
                if (obj.optBoolean("needsLogin", false)) {
                    pauseForLogin()
                    return
                }
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

    private fun pauseForLogin() {
        capturing = false
        progress.visibility = View.GONE
        captureButton.isEnabled = true
        loginButton.isEnabled = true
        statusText.text = getString(R.string.pattas_scan_login_needed)
        loadLoginPage()
    }

    private fun retryExtract(message: String) {
        statusText.text = message
        handler.postDelayed({ injectExtract() }, RETRY_DELAY_MS)
    }

    private fun failCapture(message: String) {
        capturing = false
        progress.visibility = View.GONE
        captureButton.isEnabled = true
        loginButton.isEnabled = true
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
        private const val LOGIN_URL = "https://www.screener.in/login/"
        const val PAGE_DELAY_MS = 2500L
        private const val PAGE_LOAD_DELAY_MS = 2500L
        private const val RETRY_DELAY_MS = 1500L
        private const val MAX_EXTRACT_ATTEMPTS = 12

        private const val EXTRACT_JS = """
            (function() {
              try {
                var path = window.location.pathname || '';
                var onLogin = path.indexOf('/login') >= 0;
                var hasLoginForm = !!(
                  document.querySelector('#id_username, input[name="username"], form[action*="login"]')
                );
                if (onLogin || (hasLoginForm && path.indexOf('/company/') < 0)) {
                  return JSON.stringify({error: 'Login page — sign in first', needsLogin: true, retry: false});
                }

                var row = {};
                var symMatch = path.match(/\/company\/([^/]+)\//);
                if (symMatch) row.symbol = symMatch[1].toUpperCase();

                function norm(s) {
                  return (s || '').trim().toLowerCase().replace(/\s+/g, ' ');
                }

                function setField(label, val) {
                  if (!val || val === '-' || val === '—') return;
                  var n = norm(label);
                  if (n.indexOf('stock p/e') >= 0 || n === 'p/e' || n === 'pe') row['P/E'] = val;
                  else if (n.indexOf('dividend yield') >= 0 || n.indexOf('div yld') >= 0) row['Div Yld %'] = val;
                  else if (n.indexOf('current price') >= 0 || n === 'cmp') row['CMP Rs.'] = val;
                  else if (n.indexOf('debt') >= 0 && n.indexOf('eq') >= 0) row['Debt / Eq'] = val;
                  else if (n.indexOf('ind pe') >= 0 || n.indexOf('industry pe') >= 0) row['Ind PE'] = val;
                  else if (n.indexOf('roe 3') >= 0 || n.indexOf('roe 3yr') >= 0) row['ROE 3Yr %'] = val;
                  else if (n === 'roce' || n.indexOf('roce %') >= 0) row['ROCE %'] = val;
                  else if (n === 'roe' || (n.indexOf('roe %') >= 0 && n.indexOf('3') < 0)) row['ROE %'] = val;
                  else if (n.indexOf('market cap') >= 0) row['Mar Cap Rs.Cr.'] = val;
                }

                // Top ratio panel (logged-in and guest layouts)
                document.querySelectorAll('li.flex.flex-space-between, .company-ratios li, #top-ratios li').forEach(function(li) {
                  var nameEl = li.querySelector('.name, .name span, span.name');
                  var numEl = li.querySelector('.number, .value .number, span.number');
                  if (nameEl && numEl) setField(nameEl.innerText, numEl.innerText);
                });

                // Alternate ratio rows (some logged-in pages)
                document.querySelectorAll('[data-source="default"]').forEach(function(li) {
                  var nameEl = li.querySelector('.name');
                  var numEl = li.querySelector('.number');
                  if (nameEl && numEl) setField(nameEl.innerText, numEl.innerText);
                });

                // Compounded / ranges table — ROE 3yr
                document.querySelectorAll('table.ranges-table tr, .ranges-table tr').forEach(function(tr) {
                  var cells = tr.querySelectorAll('td');
                  if (cells.length >= 2) {
                    var label = cells[0].innerText.trim();
                    if (label.indexOf('3 Years') >= 0 || label.indexOf('3 Year') >= 0) {
                      row['ROE 3Yr %'] = cells[1].innerText.trim();
                    }
                  }
                });

                // Ratios section table — Debt/Eq row if present
                var ratios = document.querySelector('#ratios') || document.querySelector('[id*="ratios"]');
                if (ratios) {
                  ratios.querySelectorAll('tr').forEach(function(tr) {
                    var labelCell = tr.querySelector('td.text, td:first-child');
                    if (!labelCell) return;
                    var label = labelCell.innerText.trim();
                    var cells = tr.querySelectorAll('td');
                    if (cells.length < 2) return;
                    var val = cells[cells.length - 1].innerText.trim();
                    setField(label, val);
                  });
                }

                if (!row['P/E']) {
                  var bodyText = document.body ? document.body.innerText : '';
                  if (bodyText.indexOf('Sign in') >= 0 || bodyText.indexOf('Log in') >= 0) {
                    return JSON.stringify({error: 'Login required for ratios', needsLogin: true, retry: false});
                  }
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
