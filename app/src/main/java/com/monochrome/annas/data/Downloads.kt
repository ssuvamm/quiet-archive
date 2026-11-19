package com.monochrome.annas.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.monochrome.annas.Native
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _nativeTasks = MutableStateFlow<List<DownloadProgress>>(emptyList())

    /** Tasks whose download page is being resolved through the browser session. */
    private val _preparing = MutableStateFlow<List<DownloadProgress>>(emptyList())

    val tasks: StateFlow<List<DownloadProgress>> = combine<
        List<DownloadProgress>, List<DownloadProgress>, List<DownloadProgress>
    >(_nativeTasks, _preparing) { native, preparing ->
        (native + preparing).sortedByDescending { it.createdAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val saved = _saved.asSharedFlow()
    private var pollJob: Job? = null
    private val handled = mutableSetOf<String>()

    /** Ids already given one session-refresh retry after a protection block. */
    private val refreshed = mutableSetOf<String>()

    /** A retry is in flight for these ids (keeps the poller from looping). */
    private val retrying = mutableSetOf<String>()

    /** Ids that used up every link from the book page. */
    private val exhausted = mutableSetOf<String>()

    /** Ids the user cancelled — never auto-retried. */
    private val cancelled = mutableSetOf<String>()

    /** Stored request JSON per id, so a blocked download can be re-issued. */
    private val requests = mutableMapOf<String, String>()

    /** Untried mirror links for each id, in order of preference. */
    private val fallbacks = mutableMapOf<String, ArrayDeque<String>>()

    /** Browser-resolved downloads (the site's 403s the plain HTTP client). */
    private val browserRequests = mutableMapOf<String, DownloadRequest>()
    private val browserMirrors = mutableMapOf<String, List<String>>()
    private val saveFailed = mutableSetOf<String>()
    private val prepareJobs = mutableMapOf<String, Job>()
    private val attempts = mutableMapOf<String, Int>()

    /** Downloads waiting for a free slot (max 2) or for Wi-Fi. */
    private data class Queued(val request: DownloadRequest, val mirrors: List<String>)
    private val pending = ArrayDeque<Queued>()
    private var queueJob: Job? = null

    private fun onWifi(context: Context): Boolean = try {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    } catch (_: Exception) { false }

    private fun activeIds(): Set<String> =
        _nativeTasks.value.filter { it.state in setOf("queued", "downloading", "waiting", "saving") }.map { it.id }.toSet() +
            _preparing.value.map { it.id }.toSet()

    /**
     * Entry point used by the UI: enforces Wi-Fi-only, a two-download cap and
     * duplicate detection before handing the job to the browser resolver.
     */
    fun enqueue(context: Context, request: DownloadRequest, mirrors: List<String>) {
        if (runCatching { parseLibrary(Native.library()) }.getOrNull()?.any { it.id == request.id } == true) {
            android.widget.Toast.makeText(context, "This book is already in your library", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if ((context.getWifiOnly() && !onWifi(context)) || request.id in activeIds()) {
            if (pending.none { it.request.id == request.id }) pending.addLast(Queued(request, mirrors))
            android.widget.Toast.makeText(
                context,
                if (context.getWifiOnly() && !onWifi(context)) "Waiting for Wi-Fi…" else "Queued behind another download",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            pumpQueue(context.applicationContext)
            return
        }
        prepareAndStart(context, request, mirrors)
    }

    private fun pumpQueue(context: Context) {
        if (queueJob?.isActive == true) return
        queueJob = scope.launch {
            while (pending.isNotEmpty()) {
                if ((context.getWifiOnly() && !onWifi(context)) || activeIds().size >= 2) {
                    delay(2500)
                    continue
                }
                val next = pending.removeFirst()
                if (runCatching { parseLibrary(Native.library()) }.getOrNull()?.any { it.id == next.request.id } == true) continue
                prepareAndStart(context, next.request, next.mirrors)
            }
            queueJob = null
        }
    }

    /** Errors that mean the site's bot protection stopped the plain HTTP client. */
    private fun isBlocked(error: String?): Boolean =
        error != null && (error.contains("Blocked by the site protection", true) ||
            error.contains("browser verification", true) ||
            error.contains("HTTP 403", true))

    fun ensurePoller(context: Context) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try { _nativeTasks.value = parseDownloads(Native.downloads()) } catch (_: Exception) {}
                val current = _nativeTasks.value
                current.filter { it.state == "saving" && it.id !in handled }.forEach { task ->
                    handled.add(task.id)
                    scope.launch { save(context.applicationContext, task) }
                }
                // Recover a failed download: re-sync the browser session once
                // for a protection block, then walk the book page's other
                // servers. Gives up (and keeps the error visible) when there
                // is nothing left to try, or when the user cancelled.
                current.filter {
                    it.state == "error" && it.id !in exhausted && it.id !in cancelled && it.id !in retrying
                }.forEach { task ->
                    scope.launch { retryFailed(context.applicationContext, task) }
                }
                val hasActive = current.any { it.state in setOf("queued", "downloading", "waiting", "saving") }
                delay(if (hasActive) 400L else 2000L)
                if (!hasActive && current.isEmpty()) break
            }
            pollJob = null
        }
    }

    /**
     * One recovery step for a failed download.
     *
     * A DDoS-Guard block usually clears once the WebView session's cookies are
     * re-synced (the fetch also surfaces the manual check to the user). After
     * that — and for ordinary failures like a dead mirror — try the next
     * download link listed on the book page.
     */
    private suspend fun retryFailed(context: Context, task: DownloadProgress) {
        if (!retrying.add(task.id)) return
        // Browser-resolved downloads have their own retry chain; the native
        // poller must not race it and thrash the site with duplicate requests.
        if (task.id in browserRequests) {
            // A browser-resolved download has its own chain, but a mid-stream
            // network abort is worth one automatic Range-resume.
            if ((attempts[task.id] ?: 0) == 0 && task.error?.contains("interrupted", true) == true) {
                attempts[task.id] = 1
                retrying.remove(task.id)
                retry(context, task.id, auto = true)
            } else {
                retrying.remove(task.id)
            }
            return
        }
        val n = (attempts[task.id] ?: 0) + 1
        attempts[task.id] = n
        if (n > 3) {
            exhausted.add(task.id)
            retrying.remove(task.id)
            return
        }
        try {
            val json = requests[task.id] ?: return
            if (isBlocked(task.error) && refreshed.add(task.id)) {
                android.util.Log.d("Downloads", "blocked download — refreshing session")
                refreshSession(task.id)
                reissue(task.id, json)
                return
            }
            val next = fallbacks[task.id]?.removeFirstOrNull()
            if (next != null) {
                android.util.Log.d("Downloads", "retrying ${task.id} with another server")
                val updated = JSONObject(json).put("url", next).toString()
                requests[task.id] = updated
                reissue(task.id, updated)
                return
            }
            android.util.Log.d("Downloads", "no recovery left for ${task.id}: ${task.error}")
            exhausted.add(task.id)
        } finally {
            retrying.remove(task.id)
        }
    }

    private suspend fun refreshSession(id: String) {
        val json = requests[id] ?: return
        val bookUrl = try { JSONObject(json).optString("book_url") } catch (_: Exception) { "" }
        try {
            WebFetch.fetch(bookUrl.ifBlank { "https://annas-archive.gl" })
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "session refresh for download failed", e)
        }
    }

    private fun reissue(id: String, json: String) {
        try {
            val ok = JSONObject(Native.startDownload(json)).optBoolean("ok", false)
            android.util.Log.d("Downloads", "retry of $id: ok=$ok")
        } catch (e: Exception) {
            android.util.Log.w("Downloads", "retry of $id threw", e)
        }
    }

    /** Stop a download and forget its retry bookkeeping. */
    fun cancel(context: Context, id: String) {
        cancelled.add(id)
        exhausted.add(id)
        retrying.remove(id)
        requests.remove(id)
        fallbacks.remove(id)
        refreshed.remove(id)
        attempts.remove(id)
        prepareJobs.remove(id)?.cancel()
        pending.removeAll { it.request.id == id }
        clearPreparing(id)
        scope.launch {
            try { Native.cancelDownload(id) } catch (_: Exception) {}
        }
    }

    /** Re-issue the stored request after the user taps RETRY. */
    fun retry(context: Context, id: String, auto: Boolean = false) {
        // A save failure happens AFTER the file is fully downloaded: retrying
        // the save reuses the cached file instead of downloading it again.
        val nativeTask = runCatching { parseDownloads(Native.downloads()) }.getOrNull()
            ?.firstOrNull { it.id == id }
        if (id in saveFailed && nativeTask != null &&
            !nativeTask.tempPath.isNullOrBlank() && File(nativeTask.tempPath!!).isFile
        ) {
            scope.launch {
                save(context.applicationContext, nativeTask.copy(state = "saving", error = null))
            }
            return
        }
        // A network interruption mid-file can resume with a Range request:
        // reuse the native task's stored URL and the bytes already on disk.
        val resumable = nativeTask != null && !nativeTask.tempPath.isNullOrBlank() &&
            File(nativeTask.tempPath!!).let { it.isFile && it.length() > 0 } &&
            !isBlocked(nativeTask.error) && requests[id] != null
        if (resumable) {
            exhausted.remove(id)
            cancelled.remove(id)
            refreshed.remove(id)
            retrying.remove(id)
            if (!auto) attempts.remove(id)
            ensurePoller(context)
            reissue(id, requests[id]!!)
            return
        }
        val browser = browserRequests[id]
        if (browser != null) {
            prepareAndStart(context, browser, browserMirrors[id].orEmpty())
            return
        }
        val json = requests[id] ?: return
        exhausted.remove(id)
        cancelled.remove(id)
        refreshed.remove(id)
        retrying.remove(id)
        attempts.remove(id)
        ensurePoller(context)
        reissue(id, json)
    }

    // ---- browser-assisted downloads --------------------------------------
    //
    // Anna's Archive's DDoS-Guard fingerprints the plain HTTP client and
    // answers every /slow_download/ request with HTTP 403, even with fresh
    // cookies. The app's WebView session passes that check (that is why search
    // works), so resolve the waitlist page there and hand only the final file
    // URL to the native downloader — partner servers don't run that guard.

    /** Start a download, resolving slow links through the browser session first. */
    fun prepareAndStart(context: Context, request: DownloadRequest, mirrors: List<String>): String? {
        val json = requestJson(request, request.url)
        requests[request.id] = json.toString()
        browserRequests[request.id] = request
        browserMirrors[request.id] = mirrors.filter { it != request.url }
        refreshed.remove(request.id)
        retrying.remove(request.id)
        exhausted.remove(request.id)
        cancelled.remove(request.id)
        attempts.remove(request.id)
        fallbacks[request.id] = ArrayDeque(mirrors.filter { it != request.url })
        ensurePoller(context)
        prepareJobs.remove(request.id)?.cancel()
        _preparing.value = _preparing.value.filter { it.id != request.id } + preparingTask(request, "waiting", "Opening the download page…")
        prepareJobs[request.id] = scope.launch {
            try {
                runPrepare(context.applicationContext, request, json.toString())
            } catch (e: CancellationException) {
                clearPreparing(request.id)
            }
        }
        return request.id
    }

    private suspend fun runPrepare(context: Context, request: DownloadRequest, json: String) {
        val candidates = listOf(request.url) + (fallbacks[request.id]?.toList().orEmpty())
        var lastError = "Download failed"
        for (url in candidates) {
            if (request.id in cancelled) return
            try {
                val finalUrl = if (url.contains("/slow_download/")) {
                    resolveSlowWithBrowser(context, request, url)
                } else url
                val updated = JSONObject(json).put("url", finalUrl).toString()
                requests[request.id] = updated
                val result = withContext(Dispatchers.IO) { Native.startDownload(updated) }
                val o = JSONObject(result)
                if (o.optBoolean("ok", false)) {
                    clearPreparing(request.id)
                    ensurePoller(context) // restart the saver — there were no native tasks before this
                    return
                }
                lastError = o.optString("error", lastError)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: lastError
                if (isBlocked(lastError)) {
                    try { WebFetch.fetch(request.bookUrl ?: context.getBaseUrl()) } catch (_: Exception) {}
                }
            }
        }
        android.util.Log.w("Downloads", "browser-resolved download failed for ${request.id}: $lastError")
        exhausted.add(request.id)
        _preparing.value = _preparing.value.filter { it.id != request.id } + preparingTask(request, "error", lastError)
    }

    /** Fetch the slow-download page through WebFetch, waiting out its waitlist. */
    private suspend fun resolveSlowWithBrowser(context: Context, request: DownloadRequest, url: String): String {
        var current = url
        val startedAt = System.currentTimeMillis()
        repeat(8) { attempt ->
            if (request.id in cancelled) throw CancellationException("cancelled")
            if (System.currentTimeMillis() - startedAt > 240_000) {
                throw IllegalStateException("Download waitlist timed out. Try again shortly.")
            }
            val html = WebFetch.fetch(current, 60_000L)
            android.util.Log.d(
                "Downloads",
                "slow page attempt ${attempt + 1}: len=${html.length} challenge=${html.length < 2000}",
            )

            // Final partner file link, same shape the native core looks for.
            val pos = html.indexOf("mb-4 text-xl font-bold")
            if (pos >= 0) {
                val tail = html.substring(pos, minOf(pos + 3000, html.length))
                val h = tail.indexOf("href=\"")
                if (h >= 0) {
                    val end = tail.indexOf('"', h + 6)
                    if (end > h) {
                        val candidate = tail.substring(h + 6, end)
                        if (candidate.startsWith("http") &&
                            hostOf(candidate) != hostOf(url)
                        ) return candidate
                    }
                }
            }

            // Waitlist: the page asks the browser to come back in N seconds.
            val w = html.indexOf("waitSeconds")
            if (w >= 0) {
                val tail = html.substring(w, minOf(w + 60, html.length))
                val digits = tail.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
                val seconds = (digits.toIntOrNull() ?: 0).coerceIn(1, 90)
                updatePreparing(request, "waiting", "Waiting ${seconds}s before download…")
                delay(seconds * 1000L)
                return@repeat
            }

            if (html.contains("ddos-guard", true) || html.contains("checking your browser", true) || html.length < 2000) {
                throw IllegalStateException("Blocked by the site protection (HTTP 403). Refreshing the session…")
            }
            throw IllegalStateException("Could not resolve the download link. Trying another server…")
        }
        throw IllegalStateException("Download waitlist timed out. Try again shortly.")
    }

    private fun hostOf(url: String): String = try {
        java.net.URL(url).host.lowercase()
    } catch (_: Exception) { url }

    private fun requestJson(request: DownloadRequest, url: String): JSONObject = JSONObject().apply {
        put("id", request.id)
        put("title", request.title)
        put("authors", org.json.JSONArray(request.authors))
        put("format", request.format)
        put("url", url)
        put("cover", request.cover)
        put("book_url", request.bookUrl)
        put("replace", request.replace)
    }

    private fun preparingTask(request: DownloadRequest, state: String, error: String) = DownloadProgress(
        id = request.id,
        title = request.title,
        authors = request.authors,
        state = state,
        received = 0,
        total = 0,
        speed = 0,
        format = request.format,
        filename = "",
        tempPath = null,
        error = error,
        createdAt = System.currentTimeMillis(),
    )

    private fun updatePreparing(request: DownloadRequest, state: String, error: String) {
        _preparing.value = _preparing.value.map { t ->
            if (t.id == request.id) t.copy(state = state, error = error) else t
        }
    }

    private fun clearPreparing(id: String) {
        _preparing.value = _preparing.value.filter { it.id != id }
    }

    private suspend fun save(context: Context, task: DownloadProgress) {
        try {
            val temp = File(task.tempPath ?: "")
            val folder = context.getFolderUri()
            var usedFallback = false
            if (folder != null) {
                try {
                    saveToChosenFolder(context, folder, task, temp)
                } catch (e: Exception) {
                    // A stale SAF permission or a moved folder must not lose a
                    // finished download — put it in the public Downloads folder.
                    android.util.Log.w("Downloads", "chosen folder failed, using Downloads", e)
                    context.setFolderUri(null)
                    android.widget.Toast.makeText(
                        context,
                        "Chosen folder unavailable — saved to Downloads instead",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    usedFallback = true
                }
            }
            if (folder == null || usedFallback) saveToDownloads(context, task, temp)
            try { temp.delete() } catch (_: Exception) {}
            saveFailed.remove(task.id)
            _saved.tryEmit(Unit)
        } catch (e: Exception) {
            saveFailed.add(task.id)
            Repo.failSave(task.id, e.message ?: "Could not save file")
        } finally {
            // Handled is only a de-duplication guard for THIS save: clearing
            // it lets a later download of the same book (after a failed save,
            // or a delete + re-download) reach the save step again instead of
            // sticking in "saving" forever.
            handled.remove(task.id)
            requests.remove(task.id)
            browserRequests.remove(task.id)
            browserMirrors.remove(task.id)
            attempts.remove(task.id)
            pumpQueue(context.applicationContext)
        }
    }

    /**
     * DocumentsContract.createDocument needs a *document* URI for the parent
     * folder. Passing the raw tree URI (as we did) makes Android's external
     * storage provider throw "Invalid URI".
     */
    private suspend fun saveToChosenFolder(context: Context, folder: String, task: DownloadProgress, temp: File) {
        val treeUri = Uri.parse(folder)
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            ?: throw IllegalStateException("Could not read the chosen folder")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId)
        val mime = if (task.format.isBlank()) "application/octet-stream" else mimeFor(task.format)
        val docUri = DocumentsContract.createDocument(context.contentResolver, parentUri, mime, task.filename)
            ?: throw IllegalStateException("Could not create file in the chosen folder")
        try {
            context.contentResolver.openOutputStream(docUri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open destination stream")
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(docUri, null, null) }
            throw e
        }
        val name = context.contentResolver.query(
            docUri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: task.filename
        Repo.finishSave(task.id, docUri.toString(), "", name, temp.length())
    }

    private suspend fun saveToDownloads(context: Context, task: DownloadProgress, temp: File) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val mime = if (task.format.isBlank()) "application/octet-stream" else mimeFor(task.format)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, task.filename)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file in Downloads")
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Could not open destination stream")
            } catch (e: Exception) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw e
            }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            val name = resolver.query(
                itemUri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: task.filename
            Repo.finishSave(task.id, itemUri.toString(), "", name, temp.length())
        } else {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            dir.mkdirs()
            val target = uniqueFile(dir, task.filename)
            temp.copyTo(target, overwrite = true)
            Repo.finishSave(task.id, "", target.absolutePath, target.name, target.length())
        }
    }

    fun start(
        context: Context,
        request: DownloadRequest,
        mirrors: List<String> = emptyList(),
    ): String? {
        val json = JSONObject()
        json.put("id", request.id)
        json.put("title", request.title)
        json.put("authors", org.json.JSONArray(request.authors))
        json.put("format", request.format)
        json.put("url", request.url)
        json.put("cover", request.cover)
        json.put("book_url", request.bookUrl)
        json.put("replace", request.replace)
        ensurePoller(context)
        requests[request.id] = json.toString()
        // A fresh manual attempt resets every retry counter: session refresh,
        // mirror list, cancellation and the "gave up" flag.
        refreshed.remove(request.id)
        retrying.remove(request.id)
        exhausted.remove(request.id)
        cancelled.remove(request.id)
        attempts.remove(request.id)
        fallbacks[request.id] = ArrayDeque(mirrors.filter { it != request.url })
        val result = Native.startDownload(json.toString())
        return try {
            val o = JSONObject(result)
            if (o.optBoolean("ok", false)) {
                o.optString("id")
            } else {
                val msg = o.optString("error", "Download failed")
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                null
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Download failed", android.widget.Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            target = File(dir, "$stem ($i)$ext")
            if (!target.exists()) return target
            i++
        }
    }

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "mobi" -> "application/x-mobipocket-ebook"
        "azw3", "azw" -> "application/vnd.amazon.ebook"
        "djvu" -> "image/vnd.djvu"
        "fb2" -> "application/x-fictionbook+xml"
        "txt" -> "text/plain"
        "cbz" -> "application/vnd.comicbook+zip"
        "cbr" -> "application/vnd.comicbook-rar"
        "zip" -> "application/zip"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }
}
