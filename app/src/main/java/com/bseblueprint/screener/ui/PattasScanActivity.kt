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
    private val skippedSymbols = mutableListOf<String>()
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
        skippedSymbols.clear()
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
                if (obj.optBoolean("skip", false)) {
                    skipCurrentSymbol(obj.optString("reason", "unavailable"))
                    return
                }
                if (obj.optBoolean("retry", false) && extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                    retryExtract(err)
                    return
                }
                // Unrecoverable for this symbol — skip instead of aborting whole scan
                skipCurrentSymbol(err)
                return
            }

            if (obj.optBoolean("skip", false)) {
                skipCurrentSymbol(obj.optString("reason", "unavailable"))
                return
            }

            val rowObj = obj.optJSONObject("row")
            if (rowObj == null || rowObj.length() < 1) {
                if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                    retryExtract("Ratios not ready — retrying…")
                    return
                }
                skipCurrentSymbol("no ratios parsed")
                return
            }

            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val row: Map<String, Any?> = gson.fromJson(rowObj.toString(), type)
            if (!hasMeaningfulData(row)) {
                if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                    retryExtract("Waiting for ratio panel…")
                    return
                }
                skipCurrentSymbol("no usable ratios")
                return
            }
            capturedRows.add(row)
            advanceToNext()
        } catch (t: Throwable) {
            if (extractAttempts < MAX_EXTRACT_ATTEMPTS) {
                retryExtract("Parse error — retrying…")
            } else {
                skipCurrentSymbol("parse error")
            }
        }
    }

    private fun hasMeaningfulData(row: Map<String, Any?>): Boolean {
        val keys = listOf(
            "P/E", "CMP Rs.", "Div Yld %", "Debt / Eq", "ROCE %", "ROE %",
            "ROE 3Yr %", "Ind PE", "Gross NPA %", "Net NPA %", "Price to Book"
        )
        return keys.any { row[it] != null && row[it].toString().isNotBlank() }
    }

    private fun skipCurrentSymbol(reason: String) {
        val sym = symbols.getOrNull(currentIndex) ?: return
        skippedSymbols.add("$sym:$reason")
        statusText.text = getString(R.string.pattas_scan_skipped, sym, reason)
        advanceToNext()
    }

    private fun advanceToNext() {
        extractAttempts = 0
        if (currentIndex + 1 < symbols.size) {
            handler.postDelayed({ loadSymbol(currentIndex + 1) }, PAGE_DELAY_MS)
        } else {
            finishCapture()
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
        captureButton.isEnabled = true
        loginButton.isEnabled = true
        statusText.text = if (skippedSymbols.isEmpty()) {
            getString(R.string.pattas_scan_complete, capturedRows.size)
        } else {
            getString(
                R.string.pattas_scan_complete_with_skips,
                capturedRows.size,
                skippedSymbols.size
            )
        }
        val result = Intent().apply {
            putExtra(EXTRA_ROWS_JSON, gson.toJson(capturedRows))
            putExtra(EXTRA_SKIPPED_JSON, gson.toJson(skippedSymbols))
        }
        setResult(RESULT_OK, result)
        handler.postDelayed({ finish() }, 600)
    }

    companion object {
        const val EXTRA_SYMBOLS = "symbols"
        const val EXTRA_ROWS_JSON = "rows_json"
        const val EXTRA_SKIPPED_JSON = "skipped_json"
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

                var title = (document.title || '').toLowerCase();
                var bodyText = document.body ? document.body.innerText : '';
                var heading = document.querySelector('h1, h2');
                var headingText = heading ? heading.innerText.toLowerCase() : '';
                var is404 = title.indexOf('404') >= 0 ||
                  bodyText.indexOf('Page Not Found') >= 0 ||
                  bodyText.indexOf('Error 404') >= 0 ||
                  headingText.indexOf('404') >= 0 ||
                  headingText.indexOf('not found') >= 0;
                if (is404) {
                  return JSON.stringify({skip: true, reason: '404 not found', symbol: row.symbol || ''});
                }

                function norm(s) {
                  return (s || '').trim().toLowerCase().replace(/\s+/g, ' ');
                }

                function cleanVal(val) {
                  if (!val) return null;
                  val = val.replace(/\u00a0/g, ' ').trim();
                  if (!val || val === '-' || val === '—' || val === 'N/A') return null;
                  return val;
                }

                function extractValue(li) {
                  var numEl = li.querySelector('.number');
                  if (numEl) return cleanVal(numEl.innerText);
                  var valEl = li.querySelector('.value, .nowrap.value, span.value');
                  if (valEl) {
                    var t = valEl.innerText.replace(/\u00a0/g, ' ').trim();
                    var m = t.match(/-?\d[\d,]*\.?\d*/);
                    return cleanVal(m ? m[0] : t);
                  }
                  return null;
                }

                function setField(label, val) {
                  val = cleanVal(val);
                  if (!val) return;
                  var n = norm(label);
                  if (n.indexOf('stock p/e') >= 0 || n === 'p/e' || n === 'pe') row['P/E'] = val;
                  else if (n.indexOf('dividend yield') >= 0 || n.indexOf('div yld') >= 0) row['Div Yld %'] = val;
                  else if (n.indexOf('current price') >= 0 || n === 'cmp') row['CMP Rs.'] = val;
                  else if (n.indexOf('debt') >= 0 && (n.indexOf('eq') >= 0 || n.indexOf('equity') >= 0)) row['Debt / Eq'] = val;
                  else if (n.indexOf('ind pe') >= 0 || n.indexOf('industry pe') >= 0) row['Ind PE'] = val;
                  else if (n.indexOf('roe 3') >= 0 || n.indexOf('roe 3yr') >= 0) row['ROE 3Yr %'] = val;
                  else if ((n.indexOf('roe 10') >= 0) && !row['ROE 3Yr %']) row['ROE 3Yr %'] = val;
                  else if (n.indexOf('roce 10') >= 0) row['ROCE 10Yr %'] = val;
                  else if (n === 'roce' || (n.indexOf('roce') >= 0 && n.indexOf('10') < 0)) row['ROCE %'] = val;
                  else if (n === 'roe' || (n.indexOf('roe') >= 0 && n.indexOf('3') < 0 && n.indexOf('10') < 0)) row['ROE %'] = val;
                  else if (n.indexOf('market cap') >= 0) row['Mar Cap Rs.Cr.'] = val;
                  else if (n.indexOf('book value') >= 0) row['Book Value'] = val;
                  else if (n.indexOf('int coverage') >= 0 || n.indexOf('interest coverage') >= 0) row['Int Coverage'] = val;
                  else if (n === 'opm' || n.indexOf('opm %') === 0) row['OPM %'] = val;
                  else if (n.indexOf('opm 5') >= 0) row['OPM 5Year %'] = val;
                  else if (n.indexOf('cmp') >= 0 && n.indexOf('fcf') >= 0) row['CMP / FCF'] = val;
                  else if (n.indexOf('price to book') >= 0) row['Price to Book'] = val;
                  else if (n.indexOf('gross npa') >= 0) row['Gross NPA %'] = val;
                  else if (n.indexOf('net npa') >= 0) row['Net NPA %'] = val;
                  else if (n.indexOf('capital adequacy') >= 0) row['Capital Adequacy Ratio'] = val;
                  else if (n.indexOf('net interest margin') >= 0) row['Net Interest Margin'] = val;
                  else if (n.indexOf('face value') >= 0) row['Face Value'] = val;
                }

                function scanLi(li) {
                  var nameEl = li.querySelector('.name, .name span, span.name, th, dt');
                  if (!nameEl) return;
                  var val = extractValue(li);
                  if (!val && li.querySelectorAll('td').length >= 2) {
                    var tds = li.querySelectorAll('td');
                    val = cleanVal(tds[tds.length - 1].innerText);
                  }
                  if (val) setField(nameEl.innerText, val);
                }

                // Top ratio panel + logged-in custom ratio list
                document.querySelectorAll(
                  'li.flex.flex-space-between, li.flex-space-between, .company-ratios li, #top-ratios li, [data-source] li, ul.ratios li'
                ).forEach(scanLi);

                document.querySelectorAll('[data-source="default"]').forEach(scanLi);

                // Any list item with a .name child (logged-in front-page ratios)
                document.querySelectorAll('li').forEach(function(li) {
                  if (li.querySelector('.name')) scanLi(li);
                });

                // Compounded / ranges table — ROE 3yr
                document.querySelectorAll('table.ranges-table tr, .ranges-table tr').forEach(function(tr) {
                  var cells = tr.querySelectorAll('td');
                  if (cells.length >= 2) {
                    var label = cells[0].innerText.trim();
                    if (label.indexOf('3 Years') >= 0 || label.indexOf('3 Year') >= 0) {
                      row['ROE 3Yr %'] = cleanVal(cells[1].innerText);
                    }
                  }
                });

                // Ratios section table rows
                var ratios = document.querySelector('#ratios') || document.querySelector('[id*="ratios"]');
                if (ratios) {
                  ratios.querySelectorAll('tr').forEach(function(tr) {
                    var labelCell = tr.querySelector('td.text, td:first-child, th.text');
                    if (!labelCell) return;
                    var cells = tr.querySelectorAll('td');
                    if (cells.length < 2) return;
                    setField(labelCell.innerText, cells[cells.length - 1].innerText);
                  });
                }

                function insightMetric(name) {
                  var re = new RegExp(name + '\\\\s*-\\\\s*([\\\\d.]+%?)', 'i');
                  var m = bodyText.match(re);
                  return m ? cleanVal(m[1]) : null;
                }

                row['Gross NPA %'] = row['Gross NPA %'] || insightMetric('Gross NPA');
                row['Net NPA %'] = row['Net NPA %'] || insightMetric('Net NPA');
                row['Capital Adequacy Ratio'] = row['Capital Adequacy Ratio'] || insightMetric('Capital Adequacy Ratio');
                row['Net Interest Margin'] = row['Net Interest Margin'] || insightMetric('Net Interest Margin');

                function latestAnalysisPct(label) {
                  var analysis = document.querySelector('#analysis');
                  if (!analysis) return null;
                  var rows = analysis.querySelectorAll('tr');
                  for (var i = 0; i < rows.length; i++) {
                    var labelCell = rows[i].querySelector('td.text');
                    if (!labelCell) continue;
                    if (norm(labelCell.innerText) !== norm(label)) continue;
                    var cells = rows[i].querySelectorAll('td');
                    for (var j = cells.length - 1; j >= 0; j--) {
                      var v = cleanVal(cells[j].innerText);
                      if (v && v.indexOf('%') >= 0) return v;
                    }
                  }
                  return null;
                }

                row['Gross NPA %'] = row['Gross NPA %'] || latestAnalysisPct('Gross NPA %');
                row['Net NPA %'] = row['Net NPA %'] || latestAnalysisPct('Net NPA %');

                function compounded3y(blockName, outKey) {
                  var tables = document.querySelectorAll('table');
                  for (var t = 0; t < tables.length; t++) {
                    var th = tables[t].querySelector('th');
                    if (!th || th.innerText.indexOf(blockName) < 0) continue;
                    var trs = tables[t].querySelectorAll('tr');
                    for (var r = 0; r < trs.length; r++) {
                      var label = trs[r].querySelector('td');
                      if (!label) continue;
                      if (label.innerText.indexOf('3 Year') >= 0) {
                        var cells = trs[r].querySelectorAll('td');
                        if (cells.length >= 2) row[outKey] = cleanVal(cells[1].innerText);
                      }
                    }
                  }
                }

                compounded3y('Compounded Sales Growth', 'Sales Growth 3Y');
                compounded3y('Compounded Profit Growth', 'Profit Growth 3Y');

                if (!row['Price to Book'] && row['CMP Rs.'] && row['Book Value']) {
                  var cmpN = parseFloat(String(row['CMP Rs.']).replace(/,/g, ''));
                  var bvN = parseFloat(String(row['Book Value']).replace(/,/g, ''));
                  if (!isNaN(cmpN) && !isNaN(bvN) && bvN > 0) {
                    row['Price to Book'] = (cmpN / bvN).toFixed(2);
                  }
                }

                function hasData(r) {
                  return r['P/E'] || r['CMP Rs.'] || r['Div Yld %'] || r['Debt / Eq'] ||
                    r['ROCE %'] || r['ROE %'] || r['ROE 3Yr %'] || r['Ind PE'] ||
                    r['Gross NPA %'] || r['Net NPA %'] || r['Price to Book'];
                }

                if (!hasData(row)) {
                  if (bodyText.indexOf('Sign in') >= 0 || bodyText.indexOf('Log in') >= 0) {
                    return JSON.stringify({error: 'Login required for ratios', needsLogin: true, retry: false});
                  }
                  return JSON.stringify({error: 'Ratio panel not visible yet', retry: true});
                }
                return JSON.stringify({row: row});
              } catch(e) {
                return JSON.stringify({error: e.message, retry: false, skip: true, reason: 'js error'});
              }
            })();
        """
    }
}
