package com.bseblueprint.screener.bridge

import android.webkit.JavascriptInterface

class ScreenerJsBridge(
    private val onPageData: (String) -> Unit
) {
    @JavascriptInterface
    fun onPageData(json: String) {
        onPageData(json)
    }
}
