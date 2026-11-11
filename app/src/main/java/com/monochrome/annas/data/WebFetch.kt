package com.monochrome.annas.data

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Headless WebView fetcher.
 *
 * annas-archive.gl sits behind DDoS-Guard, which fingerprint-checks plain HTTP
 * clients (Rust/ureq, curl) on the hot paths (/search, /md5, /db). A real
 * browser engine passes it, so we keep ONE persistent headless WebView with a
 * solved session and serve every page through an in-page JavaScript fetch()
 * — same origin, browser cookies included, no page navigation. The Rust core
 * only parses the returned HTML/JSON. WebView cookies are synced into the
 * native core after every load so downloads and covers (ureq) inherit the
 * browser session.
 */
object WebFetch {

    /** Set from MainActivity so we always create the WebView with a UI context. */
    @Volatile
    var activityContext: android.content.Context? = null

    private val gate = Mutex() // one request at a time

    private var web: WebView? = null

    private fun isChallenge(html: String): Boolean {
        // Interstitials are tiny; real pages (hundreds of KB) also mention
        // ddos-guard in script tags — the size cap avoids false positives.
        if (html.length > 20_000) return false
        val lower = html.lowercase()
        return lower.contains("ddos-guard") ||
            lower.contains("checking your browser") ||
            lower.contains("just a moment")
    }

    private fun syncCookies(url: String) {
        try {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    runCatching { Repo.setCookies(cookie) }
                }
            }
        } catch (_: Exception) {}
    }

    private fun logResult(body: String, url: String) {
        android.util.Log.d(
            "WebFetch",
            "got: $url len=${body.length} challenge=${isChallenge(body)}",
        )
    }

    /** evaluateJavascript returns a JSON string literal; decode it properly. */
    private fun jsUnescape(raw: String?): String? {
        val text = raw?.trim() ?: return null
        if (text == "null") return null
        if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            return try { JSONTokener(text).nextValue().toString() } catch (_: Exception) { text }
        }
        return text
    }

    /** Synchronous-looking evaluateJavascript on the UI thread. */
    private suspend fun eval(webView: WebView, js: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { }
                try {
                    webView.evaluateJavascript(js) { result -> cont.resume(result) }
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        }

    /**
     * The session WebView: one WebView holding the solved DDoS-Guard session.
     * Loads the base site once; reloads while the guard interstitial persists;
     * subsequent fetches run as in-page XHR.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun session(baseUrl: String, timeoutMs: Long): WebView =
        withContext(Dispatchers.Main) {
            val activity = activityContext
                ?: throw IllegalStateException("No activity for WebView")
            web?.takeIf { it.context === activity }?.let { return@withContext it }

            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val startedAt = System.currentTimeMillis()

            suspendCancellableCoroutine { cont ->
                android.util.Log.d("WebFetch", "session: loading base $baseUrl")
                val webView = WebView(activity)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.blockNetworkImage = true
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(webView, true)

                var attempts = 0
                var resumed = false

                fun checkTitle() = mainHandler.postDelayed({
                    if (resumed) return@postDelayed
                    val title = try {
                        webView.evaluateJavascript("document.title") { r ->
                            val t = (r ?: "null").trim()
                            val isGuard = t.contains("DDoS-Guard") || t.contains("ddos")
                            android.util.Log.d("WebFetch", "session: title=$t guard=$isGuard attempt=$attempts")
                            if (isGuard) {
                                // Guard interstitial: wait for its mark-POST to finish,
                                // then try the navigation again (it sets real cookies).
                                mainHandler.postDelayed({ webView.loadUrl(baseUrl) }, 1800)
                            } else {
                                resumed = true
                                syncCookies(baseUrl)
                                if (cont.isActive) cont.resume(webView)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("WebFetch", "session: title eval failed", e)
                    }
                    Unit
                }, 1500)

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        android.util.Log.d("WebFetch", "session: onPageFinished url=$pageUrl")
                        attempts++
                        if (attempts <= 6) checkTitle() else if (cont.isActive) {
                            resumed = true
                            cont.resume(webView) // keep going anyway; XHR may still work
                        }
                    }
                }

                cont.invokeOnCancellation {
                    try { webView.destroy() } catch (_: Exception) {}
                }

                webView.loadUrl(baseUrl)
            }.also { wv ->
                android.util.Log.d(
                    "WebFetch",
                    "session: established after ${System.currentTimeMillis() - startedAt}ms",
                )
                web = wv
            }
        }

    private suspend fun ensureSession(baseUrl: String, timeoutMs: Long): WebView =
        gate.withLock { session(baseUrl, timeoutMs) }

    /** Warm up the session: solve any bot check once and sync cookies. */
    suspend fun warmup(baseUrl: String) {
        try {
            ensureSession(baseUrl, timeoutMs = 40_000)
        } catch (e: Exception) {
            android.util.Log.w("WebFetch", "warmup failed", e)
        }
    }

    /**
     * Fetch a URL through the solved browser session and return the body text
     * — raw document HTML, or JSON text for *.json endpoints.
     *
     * Strategy: fast in-page XHR first. If the site answers with a DDoS-Guard
     * interstitial anyway, run a real navigation in the session WebView so the
     * guard's browser check completes exactly like in Chrome, then harvest the
     * final DOM (or the JSON body text for *.json endpoints).
     */
    suspend fun fetch(url: String, timeoutMs: Long = 45_000): String = withContext(Dispatchers.Main) {
        val base = try {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) { "" }
        val webView = ensureSession(if (base.isNotEmpty()) base else "https://annas-archive.gl", 40_000)

        gate.withLock {
            var body = withContext(Dispatchers.Main) { xhr(webView, url, timeoutMs) }
            logResult(body, url)
            syncCookies(url)
            if (!isChallenge(body)) return@withLock body

            // Challenge → let the browser engine navigate for real; the guard
            // interstitial resolves itself with its mark request + reload.
            android.util.Log.d("WebFetch", "challenge on XHR — navigating instead: $url")
            val isJson = url.substringBefore('?').endsWith(".json")
            navigate(webView, url, timeoutMs)
            var dom = harvest(webView, isJson)
            logResult(dom, url)
            syncCookies(url)
            if (!isChallenge(dom)) return@withLock dom

            // Still blocked: the session itself is suspect (stale cookies, a
            // wedged guard check). Start over with a brand new WebView — a
            // fresh session solves the check on the first page load.
            android.util.Log.w("WebFetch", "guard still blocking after navigation — resetting session")
            resetSession()
            // NOTE: session() (not ensureSession) — we are already holding the
            // gate mutex here and it is not reentrant.
            val fresh = session(if (base.isNotEmpty()) base else "https://annas-archive.gl", 40_000)
            // Navigate in the fresh session: a solved browser gets through
            // where an in-page fetch may still be fingerprinted.
            navigate(fresh, url, timeoutMs)
            body = harvest(fresh, isJson)
            logResult(body, url)
            syncCookies(url)
            body
        }
    }

    /** Read the current document (or JSON body text) out of the WebView. */
    private suspend fun harvest(webView: WebView, isJson: Boolean): String =
        withContext(Dispatchers.Main) {
            val js = if (isJson) "document.body ? document.body.innerText : ''"
                     else "document.documentElement.outerHTML"
            var attempt = 0
            var text = ""
            while (attempt < 6) {
                kotlinx.coroutines.delay(1200)
                text = jsUnescape(eval(webView, js)) ?: ""
                if (text.isNotBlank() && !isChallenge(text)) break
                attempt++
            }
            text
        }

    /** Drop the session WebView so the next fetch starts from scratch. */
    private fun resetSession() {
        try { web?.destroy() } catch (_: Exception) {}
        web = null
    }

    /** One in-page fetch() of the URL; returns the body text (may be a challenge page). */
    private suspend fun xhr(webView: WebView, url: String, timeoutMs: Long): String {
        val startedAt = System.currentTimeMillis()
        val token = "aa${System.currentTimeMillis()}"
        val js = "window['$token']={s:0,r:null};" +
            "fetch('$url',{credentials:'include'})" +
            ".then(function(r){return r.text()})" +
            ".then(function(t){window['$token']={s:1,r:t}})" +
            ".catch(function(e){window['$token']={s:2,r:String(e)}});" +
            "void 0"
        android.util.Log.d("WebFetch", "fetch begin (xhr): $url")
        eval(webView, js)
        while (true) {
            if (System.currentTimeMillis() - startedAt > timeoutMs) {
                throw java.util.concurrent.TimeoutException("Browser fetch timed out: $url")
            }
            delay(300)
            val snapshot = eval(
                webView,
                "JSON.stringify({s:((window['$token']||{}).s|0),len:((window['$token']||{}).r||'').length})",
            )
            val state = runCatching {
                org.json.JSONObject(jsUnescape(snapshot) ?: "{}").optInt("s", 0)
            }.getOrDefault(0)
            when (state) {
                1 -> {
                    val body = jsUnescape(eval(webView, "window['$token'].r")) ?: ""
                    runCatching { eval(webView, "window['$token']=null;void 0") }
                    return body
                }
                2 -> {
                    val err = jsUnescape(eval(webView, "window['$token'].r")) ?: "failed"
                    throw IllegalStateException("Browser fetch failed: $err")
                }
            }
        }
    }

    /** Real navigation of the session WebView; waits for the page to finish. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun navigate(webView: WebView, url: String, timeoutMs: Long) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var resumed = false
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, pageUrl: String?) {
                        android.util.Log.d("WebFetch", "navigate: onPageFinished url=$pageUrl")
                        if (resumed) return
                        resumed = true
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
                cont.invokeOnCancellation { }
                webView.loadUrl(url)
                // Do not hang forever if the page never reaches onPageFinished.
                mainHandler.postDelayed({
                    if (!resumed) {
                        resumed = true
                        android.util.Log.w("WebFetch", "navigate: forced resume after timeout")
                        if (cont.isActive) cont.resume(Unit)
                    }
                }, timeoutMs)
            }
        }
}
