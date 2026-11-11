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
                val hasActive = current.any { it.state in setOf("queued", "downloading", "waiting", "saving") }
                delay(if (hasActive) 400L else 2000L)
                if (!hasActive && current.isEmpty()) break
            }
            pollJob = null
        }
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
        }
    }

    fun start(context: Context, request: DownloadRequest): String? {
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
