package com.monochrome.annas.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.monochrome.annas.Native
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DownloadProgress>>(emptyList())
    val tasks = _tasks.asStateFlow()
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

    /** Errors that mean the site's bot protection stopped the plain HTTP client. */
    private fun isBlocked(error: String?): Boolean =
        error != null && (error.contains("Blocked by the site protection", true) ||
            error.contains("browser verification", true) ||
            error.contains("HTTP 403", true))

    fun ensurePoller(context: Context) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                try { _tasks.value = parseDownloads(Native.downloads()) } catch (_: Exception) {}
                val current = _tasks.value
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
        scope.launch {
            try { Native.cancelDownload(id) } catch (_: Exception) {}
        }
    }

    /** Re-issue the stored request after the user taps RETRY. */
    fun retry(context: Context, id: String) {
        val json = requests[id] ?: return
        exhausted.remove(id)
        cancelled.remove(id)
        refreshed.remove(id)
        retrying.remove(id)
        ensurePoller(context)
        reissue(id, json)
    }

    private suspend fun save(context: Context, task: DownloadProgress) {
        try {
            val folder = context.getFolderUri()
            val temp = File(task.tempPath ?: "")
            if (folder != null) {
                val treeUri = Uri.parse(folder)
                val mime = if (task.format.isBlank()) "application/octet-stream" else mimeFor(task.format)
                val name = task.filename
                val docUri = DocumentsContract.createDocument(context.contentResolver, treeUri, mime, name)
                    ?: throw IllegalStateException("Could not create file in the chosen folder")
                context.contentResolver.openOutputStream(docUri)?.use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Could not open destination stream")
                val size = temp.length()
                Repo.finishSave(task.id, docUri.toString(), "", name, size)
            } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                // No folder picked: save where the user can actually see it —
                // the public Downloads folder — instead of app-private
                // storage that most file managers refuse to show.
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
                    try { resolver.delete(itemUri, null, null) } catch (_: Exception) {}
                    throw e
                }
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
                // The provider may have renamed the file on a collision.
                val name = resolver.query(itemUri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: task.filename
                Repo.finishSave(task.id, itemUri.toString(), "", name, temp.length())
            } else {
                val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                dir.mkdirs()
                val target = uniqueFile(dir, task.filename)
                temp.copyTo(target, overwrite = true)
                Repo.finishSave(task.id, "", target.absolutePath, target.name, target.length())
            }
            try { temp.delete() } catch (_: Exception) {}
            _saved.tryEmit(Unit)
        } catch (e: Exception) {
            Repo.failSave(task.id, e.message ?: "Could not save file")
        } finally {
            // Handled is only a de-duplication guard for THIS save: clearing
            // it lets a later download of the same book (after a failed save,
            // or a delete + re-download) reach the save step again instead of
            // sticking in "saving" forever.
            handled.remove(task.id)
            requests.remove(task.id)
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
