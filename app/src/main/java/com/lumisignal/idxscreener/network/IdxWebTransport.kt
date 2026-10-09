package com.lumisignal.idxscreener.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Reads idx.co.id JSON endpoints from inside a hidden WebView that has loaded IDX's own
 * "Ringkasan Saham" page. Cloudflare in front of idx.co.id rejects plain HTTP clients with
 * "Attention Required", but the page itself loads exactly this data from the browser, so a real
 * Chromium engine with the site's cookies can request it the same way the website does.
 */
class IdxWebTransport(context: Context) : IdxBrowserFetcher {
    private val appContext = context.applicationContext
    private var webView: WebView? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
    private val counter = AtomicInteger(0)
    @Volatile override var lastTitle: String = ""
        private set

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(id: String, status: Int, body: String) {
            pending.remove(id)?.complete(status to body)
        }
    }

    /** Opens the IDX page and waits until any Cloudflare interstitial has cleared. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override suspend fun open() {
        withContext(Dispatchers.Main) {
            if (webView != null) return@withContext
            CookieManager.getInstance().setAcceptCookie(true)
            val view = WebView(appContext)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.blockNetworkImage = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.addJavascriptInterface(Bridge(), "LumiBridge")
            view.webViewClient = WebViewClient()
            webView = view
            view.loadUrl(PAGE_URL)
        }
        val ready = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            var ok = false
            while (!ok) {
                delay(1_000L)
                val state = evaluate("(function(){return document.readyState+'|'+document.title;})()")
                    .trim('"')
                val readyState = state.substringBefore('|')
                lastTitle = state.substringAfter('|', "")
                val blocked = BLOCK_MARKERS.any { lastTitle.contains(it, ignoreCase = true) }
                ok = readyState == "complete" && lastTitle.isNotBlank() && !blocked
            }
            true
        }
        if (ready != true) {
            close()
            throw IOException("Halaman IDX tidak terbuka di WebView (judul: ${lastTitle.ifBlank { "kosong" }})")
        }
    }

    override suspend fun get(url: String): String {
        val id = "r${counter.incrementAndGet()}"
        val result = CompletableDeferred<Pair<Int, String>>()
        pending[id] = result
        val script = """
            (function(){
              fetch('$url',{credentials:'include',headers:{'Accept':'application/json, text/plain, */*','X-Requested-With':'XMLHttpRequest'}})
                .then(function(r){return r.text().then(function(t){LumiBridge.onResult('$id',r.status,t);});})
                .catch(function(e){LumiBridge.onResult('$id',-1,String(e));});
            })();
        """.trimIndent()
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript(script, null) ?: throw IOException("WebView IDX belum dibuka")
        }
        val (status, body) = withTimeoutOrNull(FETCH_TIMEOUT_MS) { result.await() }
            ?: run { pending.remove(id); throw IOException("IDX WebView timeout") }
        if (status == 429) throw RateLimitException()
        if (status < 0) throw IOException("IDX WebView: $body")
        if (status !in 200..299) throw HttpStatusException(status, body.take(200))
        if (HttpClient.looksLikeHtmlChallenge(body)) throw RateLimitException(challenge = true)
        return body
    }

    override suspend fun close() {
        withContext(Dispatchers.Main) {
            webView?.let { view ->
                runCatching { view.stopLoading() }
                runCatching { view.removeJavascriptInterface("LumiBridge") }
                runCatching { view.destroy() }
            }
            webView = null
        }
        pending.values.forEach { it.complete(-1 to "closed") }
        pending.clear()
    }

    private suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext ""
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value ?: "") }
        }
    }

    companion object {
        private const val PAGE_URL = "https://www.idx.co.id/id/data-pasar/ringkasan-perdagangan/ringkasan-saham/"
        private const val OPEN_TIMEOUT_MS = 45_000L
        private const val FETCH_TIMEOUT_MS = 45_000L
        private val BLOCK_MARKERS = listOf("Just a moment", "Attention Required", "Cloudflare", "Checking your browser")
    }
}
