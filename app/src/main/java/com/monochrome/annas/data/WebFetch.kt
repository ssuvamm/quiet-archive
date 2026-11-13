package com.monochrome.annas.data

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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

    /**
     * True while the guard's manual check is surfaced to the user in-app.
     * The UI shows a "verification required" banner while this is set; the
     * session WebView itself is attached full-screen for the user to solve.
     */
    val verification = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** How long the user gets to solve the manual check once it is visible. */
    private const val VERIFICATION_MS = 300_000L

    /**
     * Set by [settle] at budget end: did the manual check get surfaced to a
     * human during that settle? If so the caller retries in-session instead
     * of resetting — a reset would throw away the user's half-finished solve.
     */
    @Volatile
    private var lastSettleSurfaced = false

    /** Cap on one evaluateJavascript round-trip (see [eval]). */
    private const val EVAL_TIMEOUT_MS = 8_000L

    /**
     * Surface the session WebView to the user. The guard's manual check needs
     * a human (checkbox → image CAPTCHA), and the resulting clearance cookies
     * live in this WebView's session jar — so the check must be solved HERE,
     * not in an external browser. Attaching the WebView to the activity's
     * content view makes the interstitial visible and interactive; settle()
     * keeps polling underneath and hides it again once the page clears.
     */
    private fun showVerification(webView: WebView) {
        if (verification.value) return
        val activity = activityContext as? android.app.Activity ?: return
        try {
            val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            webView.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            content.addView(webView)
            webView.requestFocus()
            verification.value = true
            android.util.Log.d("WebFetch", "verification: session WebView surfaced to the user")
        } catch (e: Exception) {
            android.util.Log.w("WebFetch", "verification: surfacing failed", e)
        }
    }

    /** Take the session WebView back off the screen (idempotent). */
    private fun hideVerification(webView: WebView) {
        if (!verification.value) return
        try {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        } catch (_: Exception) {}
        verification.value = false
        android.util.Log.d("WebFetch", "verification: cleared — session WebView hidden")
    }

    /** Bounded waits so one stubborn page can never stall a search forever. */
    private const val NAV_MS = 20_000L    // The guard's full solve takes longer than it looks: ~15s of self-reloads
    // while it fingerprints, check.js mark pings, then a multi-round WebSocket
    // exchange whose first server message can take 6–8s to arrive. 25s cut us
    // off mid-solve (observed: replies sent at t=24.8s, budget ended t=24.83s).
    private const val SETTLE_MS = 60_000L

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

    /**
     * Synchronous-looking evaluateJavascript on the UI thread.
     *
     * The callback is NOT guaranteed to fire: the guard's synchronous
     * fingerprint rounds (a ReDoS timing probe among them) can peg the
     * renderer's JS thread for minutes, and a wedged renderer never invokes
     * the callback at all. A bounded wait turns that hang into a skipped
     * poll so the settle loop keeps running on its budget instead of
     * stalling a search forever.
     */
    private suspend fun eval(webView: WebView, js: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                var done = false
                val timeout = Runnable {
                    if (!done) {
                        done = true
                        if (cont.isActive) cont.resume(null)
                    }
                }
                mainHandler.postDelayed(timeout, EVAL_TIMEOUT_MS)
                try {
                    webView.evaluateJavascript(js) { result ->
                        if (!done) {
                            done = true
                            mainHandler.removeCallbacks(timeout)
                            cont.resume(result)
                        }
                    }
                } catch (e: Exception) {
                    if (!done) {
                        done = true
                        mainHandler.removeCallbacks(timeout)
                        cont.resumeWithException(e)
                    }
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
                // Images must load: the guard's check.js marks the session with
                // two new Image().src pings — blocking images (as this headless
                // WebView used to) silently drops them and the browser check
                // never completes, so every search stays on the interstitial.
                webView.settings.blockNetworkImage = false
                // The emulator's UA advertises `sdk_gphone64_x86_64` — a flag
                // the guard's fingerprint rounds read verbatim. Present as the
                // retail device, and drop the WebView markers so we look like
                // Chrome mobile rather than an embedded WebView.
                runCatching {
                    val ua = webView.settings.userAgentString ?: ""
                    webView.settings.userAgentString = ua
                        .replace("sdk_gphone64_x86_64", "Pixel 9")
                        .replace("; wv)", ")")
                        .replace("Version/4.0 ", "")
                }
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(webView, true)

                // Surface page-script failures: a stuck guard check is otherwise
                // invisible from logcat.
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val level = consoleMessage?.messageLevel() ?: return false
                        if (level == ConsoleMessage.MessageLevel.ERROR || level == ConsoleMessage.MessageLevel.WARNING) {
                            android.util.Log.w(
                                "WebFetch",
                                "console[$level]: ${consoleMessage.message()} " +
                                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                            )
                        }
                        return true
                    }
                }

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
                    override fun onPageStarted(
                        view: WebView,
                        pageUrl: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        // Spoof before the guard's scripts read the fingerprint.
                        view.evaluateJavascript(SPOOF_JS, null)
                    }

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

                // Empirically the guard serves two different things: a fresh jar
                // gets the plain JS challenge (which check.js + the WS check can
                // solve), while a jar left over from an earlier unsolved check
                // gets its CAPTCHA page — unsolvable for a headless session.
                // __ddg1_ is NOT a clearance marker (it is set even on unsolved
                // 403 responses), so there is nothing reliable to preserve:
                // every new session WebView starts as a brand-new browser, and
                // the ddg_last_challenge history marker goes with it.
                try {
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()
                } catch (_: Exception) {}
                // Same for storage: localStorage's `requestedCaptchaTime`
                // otherwise survives from an earlier run and forces every
                // checkbox click onto the `ddgc?reason=reset` image-captcha
                // path instead of the first-check path.
                try {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                } catch (_: Exception) {}

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
     * interstitial anyway, run a real navigation in the session WebView and
     * wait for the guard's browser check to finish on its own (it reloads the
     * page when it is done), then take the settled DOM — or the JSON body
     * text for *.json endpoints.
     */
    suspend fun fetch(url: String, timeoutMs: Long = 45_000): String = withContext(Dispatchers.Main) {
        val base = try {
            val u = java.net.URL(url)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) { "" }
        val webView = ensureSession(if (base.isNotEmpty()) base else "https://annas-archive.gl", 40_000)

        gate.withLock {
            try {
                var body = withContext(Dispatchers.Main) { xhr(webView, url, timeoutMs) }
                logResult(body, url)
                syncCookies(url)
                if (!isChallenge(body)) return@withLock body

                // Challenge → let the browser engine navigate for real, then ride
                // the guard's check out: it runs as page JS and reloads the page
                // by itself, so we keep polling until a real document appears.
                android.util.Log.d("WebFetch", "challenge on XHR — navigating instead: $url")
                val isJson = url.substringBefore('?').endsWith(".json")
                navigate(webView, url, NAV_MS)
                var dom = settle(webView, url, isJson, SETTLE_MS)
                logResult(dom, url)
                syncCookies(url)
                if (!isChallenge(dom)) return@withLock dom

                // Still blocked. If the user was mid-solve in the surfaced
                // WebView, keep their session: a reset would throw away the
                // half-finished solve. Re-navigate the SAME session instead —
                // the guard re-escalates to its check page and the user can
                // simply try again. Only a session nobody engaged with gets
                // the fresh-jar restart.
                if (lastSettleSurfaced) {
                    android.util.Log.w(
                        "WebFetch",
                        "guard still blocking — user was solving; retrying in-session",
                    )
                    navigate(webView, url, NAV_MS)
                    body = settle(webView, url, isJson, SETTLE_MS)
                    logResult(body, url)
                    syncCookies(url)
                    return@withLock body
                }
                android.util.Log.w("WebFetch", "guard still blocking after navigation — resetting session")
                resetSession()
                // NOTE: session() (not ensureSession) — we are already holding the
                // gate mutex here and it is not reentrant.
                val fresh = session(if (base.isNotEmpty()) base else "https://annas-archive.gl", 40_000)
                // Navigate in the fresh session: a solved browser gets through
                // where an in-page fetch may still be fingerprinted.
                navigate(fresh, url, NAV_MS)
                body = settle(fresh, url, isJson, SETTLE_MS)
                logResult(body, url)
                syncCookies(url)
                body
            } finally {
                // If this fetch is abandoned (caller timeout, cancellation,
                // exception) while the manual check was on screen, take the
                // WebView back off the UI — otherwise it would sit attached
                // with nobody polling for the solve.
                web?.let { hideVerification(it) }
            }
        }
    }

    /** Script that reads the current document (or the JSON body text). */
    private fun docJs(isJson: Boolean): String =
        if (isJson) "document.body ? document.body.innerText : ''"
        else "document.documentElement.outerHTML"

    /**
     * Cheap probe: is the loaded document still a guard interstitial, is the
     * manual-check widget (`#ddg-captcha`) mounted, and where does the
     * WebView currently sit? Mirrors [isChallenge] so the decision costs one
     * small string instead of the whole page.
     */
    private const val STATE_JS =
        "(function(){try{var e=document.documentElement;var h=e?e.outerHTML:'';" +
            "var l=h.toLowerCase();" +
            "var c=h.length<=20000&&(l.indexOf('ddos-guard')>=0||" +
            "l.indexOf('checking your browser')>=0||l.indexOf('just a moment')>=0);" +
            "var p=!!document.getElementById('ddg-captcha');" +
            "return JSON.stringify({u:String(location.href),c:c,p:p});" +
            "}catch(err){return JSON.stringify({u:'',c:true,p:false});}})()"

    /**
     * The guard's CAPTCHA interstitial is a checkbox ("Verify you are human")
     * whose click handler runs the same fingerprint verification that just
     * succeeded over WebSocket — then it clears the session. A real user taps
     * it once; we do the same from settle().
     */
    private const val CLICK_JS =
        "(function(){try{" +
            "var el=document.querySelector('.ddg-captcha__checkbox');" +
            "var via='main';" +
            "if(!el){" +
            "  var f=document.getElementById('ddg-iframe');" +
            "  if(f&&f.contentDocument){" +
            "    el=f.contentDocument.querySelector('.ddg-captcha__checkbox');" +
            "    via='iframe';" +
            "  }" +
            "}" +
            "if(!el)return 'absent';" +
            // At most two clicks per page: extra clicks restart verification
            // with ddgc?reason=reset, which only leads to the image CAPTCHA.
            "if((window.__aaClicks||0)>=2)return 'done';" +
            "window.__aaClicks=(window.__aaClicks||0)+1;" +
            "el.click();" +
            "return 'clicked:'+via+" +
            "' n:'+(window.__aaClicks);" +
            "}catch(e){return 'err:'+e.message}})()"

    /**
     * Report `navigator.platform` as a real phone: the emulator answers
     * "Linux x86_64" while the UA says Android — a mismatch the guard's
     * fingerprint rounds compare. Injected at onPageStarted, before the
     * guard's own scripts evaluate their rounds.
     */
    private const val SPOOF_JS =
        "(function(){try{" +
            "if(window.__aaSpoofed)return;window.__aaSpoofed=1;" +
            "Object.defineProperty(Navigator.prototype,'platform'," +
            "{get:function(){return 'Linux aarch64'},configurable:true});" +
            "}catch(e){}})()"

    /**
     * Read the WebView until it shows a real page, or the budget runs out.
     *
     * The guard's check runs as page JavaScript and then reloads/redirects
     * the page *itself*, so the first onPageFinished always lands on the
     * interstitial. We therefore poll across those self-navigations instead
     * of reading the DOM once, and when the interstitial sits still for
     * several seconds (the check script never fired) we start the load over.
     */
    private suspend fun settle(
        webView: WebView,
        url: String,
        isJson: Boolean,
        budgetMs: Long,
    ): String = withContext(Dispatchers.Main) {
        val startedAt = System.currentTimeMillis()
        var deadline = startedAt + budgetMs
        var lastClick = 0L
        var lastClickResult = "-"
        var captchaSeen = 0 // consecutive polls with the manual-check widget up
        var surfaced = false
        lastSettleSurfaced = false
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(700)
            val state = runCatching {
                org.json.JSONObject(jsUnescape(eval(webView, STATE_JS)) ?: "{}")
            }.getOrNull() ?: continue

            if (!state.optBoolean("c", true)) {
                val doc = jsUnescape(eval(webView, docJs(isJson))) ?: ""
                if (doc.isNotBlank() && !isChallenge(doc)) {
                    hideVerification(webView)
                    android.util.Log.d(
                        "WebFetch",
                        "settle: solved in ${System.currentTimeMillis() - startedAt}ms " +
                            "url=${state.optString("u")}",
                    )
                    return@withContext doc
                }
                continue
            }

            // Keep the hooks installed (the page reloads itself).
            // (Diagnostic JS hooks removed — the fixes they informed are in.)

            // Manual check (checkbox/image CAPTCHA widget mounted): the
            // automatic path just failed — the server rejected the verdict
            // even after a clean WebSocket exchange, and real Chrome on this
            // device escalates the same way. It is human-solvable though
            // (verified end-to-end), and the clearance cookies would live in
            // this session jar — so surface the WebView and let the user do
            // it in-app. A few polls of grace first: the auto-click may still
            // clear it without bothering anyone.
            if (state.optBoolean("p", false)) {
                captchaSeen++
                if (captchaSeen >= 3 && !surfaced) {
                    surfaced = true
                    showVerification(webView)
                    // The widget mounts `display:none` when its page loaded
                    // while the WebView was still detached (it keys off
                    // document visibility). A fresh load of the manual-check
                    // page now that it is on screen shows the checkbox the
                    // way a normal browser would.
                    runCatching { eval(webView, "location.reload()") }
                    // A human needs typing time; extend the poll budget —
                    // the caller's outer timeout (240s) still bounds us.
                    deadline = System.currentTimeMillis() + VERIFICATION_MS
                    android.util.Log.d(
                        "WebFetch",
                        "settle: manual check persists — surfaced for the user and reloading",
                    )
                }
            } else {
                captchaSeen = 0
            }

            // CAPTCHA interstitial: tick its checkbox like a user would. The
            // click handler runs the fingerprint verification (which passes —
            // see the accepted WebSocket exchange) and then clears the session.
            val now = System.currentTimeMillis()
            if (now - lastClick > 4_000) {
                lastClick = now
                val res = jsUnescape(eval(webView, CLICK_JS)) ?: "?"
                if (res != lastClickResult || res == "clicked") {
                    android.util.Log.d(
                        "WebFetch",
                        "settle-captcha: '$res' after ${now - startedAt}ms",
                    )
                    lastClickResult = res
                }
            }
        }
        android.util.Log.d(
            "WebFetch",
            "settle: budget of ${budgetMs}ms spent — returning the current document",
        )
        lastSettleSurfaced = surfaced
        hideVerification(webView)
        jsUnescape(eval(webView, docJs(isJson))) ?: ""
    }

    /** Drop the session WebView so the next fetch starts from scratch. */
    private fun resetSession() {
        web?.let { hideVerification(it) }
        try { web?.destroy() } catch (_: Exception) {}
        web = null
        // The old session's jar is suspect (it failed — that is why we reset),
        // so drop cookies and storage too: the next WebView must look like a
        // brand-new browser, not a repeat failure.
        try {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        } catch (_: Exception) {}
        try {
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
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
                    override fun onPageStarted(
                        view: WebView,
                        pageUrl: String?,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        view.evaluateJavascript(SPOOF_JS, null)
                    }

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
