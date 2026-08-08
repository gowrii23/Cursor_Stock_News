package com.bseblueprint.screener.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bseblueprint.screener.R
import com.google.android.material.appbar.MaterialToolbar

/** WebView fallback when headless BSE transcript PDF download is blocked. */
class ConcallScanActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_concall_scan)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finishWithCancel() }

        webView = findViewById(R.id.concallWebView)
        statusText = findViewById(R.id.concallStatus)
        progress = findViewById(R.id.concallProgress)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            finishWithCancel()
            return
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        webView.addJavascriptInterface(Bridge(), "AndroidTranscript")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                handler.postDelayed({ injectExtract() }, 1500L)
            }
        }

        statusText.text = getString(R.string.concall_scan_loading)
        webView.loadUrl(url)
    }

    private fun injectExtract() {
        if (finished) return
        webView.evaluateJavascript(EXTRACT_JS, null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onPdfBase64(data: String?) {
            handler.post {
                if (finished) return@post
                val b64 = data?.trim().orEmpty()
                if (b64.length > 100) {
                    finishWithResult(pdfBase64 = b64)
                } else {
                    finishWithCancel()
                }
            }
        }

        @JavascriptInterface
        fun onText(text: String?) {
            handler.post {
                if (finished) return@post
                val t = text?.trim().orEmpty()
                if (t.length > 500) {
                    finishWithResult(transcriptText = t)
                } else {
                    finishWithCancel()
                }
            }
        }
    }

    private fun finishWithResult(transcriptText: String = "", pdfBase64: String = "") {
        if (finished) return
        finished = true
        progress.visibility = View.GONE
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_TRANSCRIPT_TEXT, transcriptText)
                putExtra(EXTRA_PDF_BASE64, pdfBase64)
            }
        )
        finish()
    }

    private fun finishWithCancel() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_URL = "transcript_url"
        const val EXTRA_TRANSCRIPT_TEXT = "transcript_text"
        const val EXTRA_PDF_BASE64 = "pdf_base64"

        private const val EXTRACT_JS = """
            (function() {
              try {
                var text = (document.body && document.body.innerText) ? document.body.innerText.trim() : '';
                if (text.length > 500) {
                  AndroidTranscript.onText(text.substring(0, 12000));
                  return;
                }
                fetch(window.location.href, {credentials: 'include'})
                  .then(function(r) { return r.arrayBuffer(); })
                  .then(function(buf) {
                    var bytes = new Uint8Array(buf);
                    if (bytes.length < 500) { AndroidTranscript.onText(''); return; }
                    var binary = '';
                    var chunk = 0x8000;
                    for (var i = 0; i < bytes.length; i += chunk) {
                      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                    }
                    AndroidTranscript.onPdfBase64(btoa(binary));
                  })
                  .catch(function() { AndroidTranscript.onText(text); });
              } catch (e) { AndroidTranscript.onText(''); }
            })();
        """
    }
}
