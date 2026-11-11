package com.monochrome.annas.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.monochrome.annas.data.*

/**
 * Donation details — the same configuration as the author's other apps
 * (OnePair, Prism TV). PayPal uses a standard checkout link (the PayPal
 * "Donate" flow is not available in every country) and UPI covers India.
 * Both rows can copy the address, so the ID is available even when no
 * browser or UPI app is installed.
 */
private const val DONATE_PAYPAL_EMAIL = "ssuvamm@gmail.com"
private const val DONATE_PAYPAL_URL =
    "https://www.paypal.com/cgi-bin/webscr?cmd=_xclick&business=ssuvamm%40gmail.com&item_name=Support+Quiet+Archive&currency_code=USD"
private const val DONATE_UPI_ID = "ssuvamm@oksbi"
private const val DONATE_UPI_URL =
    "upi://pay?pa=ssuvamm@oksbi&pn=Quiet%20Archive&tn=Support%20Quiet%20Archive&cu=INR"

@Composable
private fun DonateRow(title: String, id: String, url: String) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        MonoLabel(id, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("PAY", onClick = {
                val opened = try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (_: Exception) { false }
                if (!opened) android.widget.Toast.makeText(context, "No app can open this link", android.widget.Toast.LENGTH_SHORT).show()
            })
            GhostButton("COPY ID", onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(title, id))
                android.widget.Toast.makeText(context, "Copied: $id", android.widget.Toast.LENGTH_SHORT).show()
            })
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var theme by remember { mutableStateOf(context.getThemeMode()) }
    var baseUrl by remember { mutableStateOf(context.getBaseUrl()) }
    var folder by remember { mutableStateOf(context.getFolderUri()) }
    var showSignIn by remember { mutableStateOf(false) }
    var loginStatus by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf("") }

val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
                try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            }
            context.setFolderUri(uri.toString())
            folder = uri.toString()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        MonoLabel("SETTINGS")
        Text("Settings", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(16.dp))

        SectionHeader("APPEARANCE")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                MonoLabel(if (theme == "system") "Follow system" else theme)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system", "light", "dark").forEach { t ->
                    GhostButton(t, onClick = { theme = t; context.setThemeMode(t); com.monochrome.annas.data.ThemeController.set(t) })
                }
            }
        }
        Hairline()

        SectionHeader("DOWNLOADS")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Storage folder", style = MaterialTheme.typography.titleSmall)
                MonoLabel(context.folderLabel(folder), modifier = Modifier.padding(top = 2.dp))
            }
            GhostButton("Change", onClick = { folderPicker.launch(null) })
        }
        if (folder != null) {
            GhostButton("Use app storage", onClick = { context.setFolderUri(null); folder = null })
        }
        Hairline()
        GhostButton("Clear download cache", onClick = { try { context.cacheDir.resolve("downloads").deleteRecursively() } catch (_: Exception) {} }, modifier = Modifier.padding(top = 8.dp))

        SectionHeader("SITE")
        Text("Base URL", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        SearchField(value = baseUrl, onValue = { baseUrl = it }, onSearch = {}, placeholder = "https://annas-archive.gl")
        Spacer(Modifier.height(8.dp))
        GhostButton("Apply", onClick = {
            val v = baseUrl.trim().ifBlank { "https://annas-archive.gl" }
            context.setBaseUrl(v)
            try { com.monochrome.annas.Native.setBaseUrl(v) } catch (_: Exception) {}
            scope.launch { runCatching { WebFetch.warmup(v) } }
        })

        SectionHeader("ACCOUNT")
        Text("Anna's Archive membership unlocks fast downloads and raises search limits. Sign in on the site in a secure in-app browser; this app reuses that session.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        GhostButton("SIGN IN ON THE SITE", { showSignIn = true }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        GhostButton("SYNC SESSION COOKIES", {
            scope.launch {
                loginStatus = try {
                    val cookie = android.webkit.CookieManager.getInstance().getCookie(context.getBaseUrl())
                    if (cookie.isNullOrBlank()) "No session cookies yet — sign in first."
                    else {
                        Repo.setCookies(cookie)
                        "Session synced (${cookie.split(";").size} cookies)."
                    }
                } catch (e: Exception) { e.message ?: "Sync failed" }
            }
        }, modifier = Modifier.fillMaxWidth())
        if (loginStatus.isNotBlank()) MonoLabel(loginStatus, modifier = Modifier.padding(top = 6.dp))
        Hairline()

        SectionHeader("SUPPORT")
        Text("Quiet Archive is free, ad-free and open source. If it makes your reading a little better, you can buy the developer a coffee.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        DonateRow("Pay with PayPal", "ssuvamm@gmail.com", DONATE_PAYPAL_URL)
        Spacer(Modifier.height(8.dp))
        DonateRow("Pay with UPI (India)", "ssuvamm@oksbi", DONATE_UPI_URL)
        Hairline()

        SectionHeader("ABOUT")
        Text("Quiet Archive — a reader for Anna's Archive", style = MaterialTheme.typography.titleSmall)
        MonoLabel("Rust core \u00b7 Jetpack Compose \u00b7 monochrome", modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        GhostButton("Storage stats", onClick = {
            try {
                val o = org.json.JSONObject(com.monochrome.annas.Native.storageStats())
                stats = "${o.optInt("count")} books \u00b7 ${formatBytes(o.optLong("bytes", 0))} \u00b7 ${o.optInt("unread")} unread"
            } catch (_: Exception) { stats = "unavailable" }
        })
        if (stats.isNotBlank()) MonoLabel(stats, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
    }

    if (showSignIn) {
        SignInDialog(baseUrl = context.getBaseUrl(), onDismiss = {
            showSignIn = false
            // Hand the fresh session cookies to the native core right away.
            scope.launch {
                loginStatus = try {
                    val cookie = android.webkit.CookieManager.getInstance().getCookie(context.getBaseUrl())
                    if (cookie.isNullOrBlank()) "No session cookies found."
                    else {
                        Repo.setCookies(cookie)
                        "Session synced (${cookie.split(";").size} cookies)."
                    }
                } catch (e: Exception) { e.message ?: "Sync failed" }
            }
        })
    }
}

@Composable
private fun SignInDialog(baseUrl: String, onDismiss: () -> Unit) {
    val webUrl = baseUrl.trimEnd('/') + "/account"
    val activity = WebFetch.activityContext
    val scope = rememberCoroutineScope()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Anna's Archive — sign in", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                GhostButton("DONE", onClick = onDismiss)
            }
            if (activity == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MonoLabel("Sign-in needs the app window. Try again from the main screen.")
                }
            } else {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val wv = android.webkit.WebView(ctx)
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                        // Hand the session to the native core as soon as it
                        // changes, so a member session is live even if the
                        // user leaves the dialog with the back gesture.
                        wv.webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(view: android.webkit.WebView, pageUrl: String?) {
                                scope.launch {
                                    val cookie = android.webkit.CookieManager.getInstance().getCookie(baseUrl)
                                    if (!cookie.isNullOrBlank()) runCatching { Repo.setCookies(cookie) }
                                }
                            }
                        }
                        wv
                    },
                ) { wv ->
                    if (wv.url == null) wv.loadUrl(webUrl)
                }
            }
        }
    }
}
