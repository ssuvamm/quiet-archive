package com.monochrome.annas.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turn a book into a spoken file with the phone's own text-to-speech engine —
 * the same idea as OpenAudioBookify, done entirely on-device.
 *
 * Chapters are spoken in sentence-sized pieces (engines cap how much text
 * they accept per call), the WAV fragments are joined into one file, and the
 * result is written to the public Downloads folder as an audiobook anyone can
 * play later.
 */
object AudioExport {
    private val cancelled = AtomicBoolean(false)

    /** Stop an export in progress (checked between every spoken chunk). */
    fun cancel() { cancelled.set(true) }

    suspend fun export(
        context: Context,
        doc: ReaderDocument,
        title: String,
        bookId: String?,
        onProgress: (chapter: Int, total: Int) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        cancelled.set(false)
        val engine = openEngine(context)
        try {
            val dir = File(context.cacheDir, "tts").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val parts = mutableListOf<File>()
            val total = doc.chapters.size
            for (i in doc.chapters.indices) {
                checkCancelled()
                val text = doc.chapterText(i).trim()
                if (text.isBlank()) continue
                onProgress(i + 1, total)
                chunk(text).forEachIndexed { n, piece ->
                    checkCancelled()
                    val out = File(dir, "part-$i-$n.wav")
                    synthesize(engine, piece, out)
                    if (out.length() > 44) parts.add(out) else out.delete()
                }
            }
            checkCancelled()
            if (parts.isEmpty()) throw IllegalStateException("There is no text to read in this book.")
            val merged = File(dir, "audiobook.wav")
            mergeWav(parts, merged)
            val name = sanitize(title) + " (audiobook).wav"
            val uri = saveAlongsideBook(context, merged, name, bookId)
            parts.forEach { it.delete() }
            merged.delete()
            name
        } finally {
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw CancellationException("Audiobook creation stopped")
    }

    private suspend fun openEngine(context: Context): TextToSpeech =
        suspendCancellableCoroutine { cont ->
            var engine: TextToSpeech? = null
            val chosen = context.getTtsEngine()
            val init = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    cont.resume(engine!!)
                } else if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("That speech engine could not start. Try the system default."))
                }
            }
            engine = if (chosen == null) {
                TextToSpeech(context.applicationContext, init)
            } else {
                TextToSpeech(context.applicationContext, init, chosen)
            }
            cont.invokeOnCancellation { runCatching { engine?.shutdown() } }
        }

    private suspend fun synthesize(engine: TextToSpeech, text: String, out: File) =
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Older engines call the int variant")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("The speech engine failed."))
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("The speech engine failed ($errorCode)."))
                }
            })
            val result = engine.synthesizeToFile(text, Bundle(), out, "export-${out.name}")
            if (result != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resumeWithException(IllegalStateException("The speech engine could not write audio."))
            }
        }

    /** Split on sentence boundaries near the engine's per-call text limit. */
    private fun chunk(text: String, max: Int = 3000): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + max, text.length)
            if (end < text.length) {
                val slice = text.substring(start, end)
                val cut = maxOf(
                    slice.lastIndexOf(". "),
                    slice.lastIndexOf("! "),
                    slice.lastIndexOf("? "),
                    slice.lastIndexOf("\n"),
                )
                if (cut > max / 3) end = start + cut + 1
            }
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            start = end
        }
        return out
    }

    /** Join WAV fragments: keep the first file's format chunk, concatenate the samples. */
    private fun mergeWav(parts: List<File>, out: File) {
        var fmt: ByteArray? = null
        val data = mutableListOf<ByteArray>()
        for (part in parts) {
            val bytes = part.readBytes()
            if (bytes.size < 44) continue
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = leInt(bytes, pos + 4)
                if (size < 0 || pos + 8 + size > bytes.size) break
                val body = bytes.copyOfRange(pos + 8, pos + 8 + size)
                when (id) {
                    "fmt " -> if (fmt == null) fmt = body
                    "data" -> data.add(body)
                }
                pos += 8 + size + (size and 1)
            }
        }
        val format = fmt ?: throw IllegalStateException("The speech engine produced no audio format.")
        if (data.isEmpty()) throw IllegalStateException("The speech engine produced no audio.")
        val dataSize = data.sumOf { it.size }
        out.outputStream().buffered().use { o ->
            fun le(v: Int) {
                o.write(v and 0xFF)
                o.write((v shr 8) and 0xFF)
                o.write((v shr 16) and 0xFF)
                o.write((v shr 24) and 0xFF)
            }
            o.write("RIFF".toByteArray(Charsets.US_ASCII))
            le(4 + (8 + format.size) + (8 + dataSize))
            o.write("WAVE".toByteArray(Charsets.US_ASCII))
            o.write("fmt ".toByteArray(Charsets.US_ASCII))
            le(format.size)
            o.write(format)
            o.write("data".toByteArray(Charsets.US_ASCII))
            le(dataSize)
            data.forEach { o.write(it) }
        }
    }

    private fun leInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /** Save next to the book when a folder is chosen, otherwise in Downloads. */
    private fun saveAlongsideBook(context: Context, file: File, name: String, bookId: String?): String {
        val folder = context.getFolderUri()
        if (folder != null) {
            try {
                val tree = Uri.parse(folder)
                val treeId = DocumentsContract.getTreeDocumentId(tree)
                    ?: throw IllegalStateException("Could not read the chosen folder")
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, "audio/wav", name)
                    ?: throw IllegalStateException("Could not create the audiobook file")
                context.contentResolver.openOutputStream(doc)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Could not write the audiobook")
                val uri = doc.toString()
                if (bookId != null) context.setAudiobookUri(bookId, uri)
                return uri
            } catch (e: Exception) {
                context.setFolderUri(null)
                Toast.makeText(context, "Chosen folder unavailable — saved to Downloads instead", Toast.LENGTH_LONG).show()
            }
        }
        val uri = saveToDownloads(context, file, name)
        if (bookId != null) context.setAudiobookUri(bookId, uri)
        return uri
    }

    private fun saveToDownloads(context: Context, file: File, name: String): String {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "audio/wav")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not write to Downloads.")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Could not write to Downloads.")
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } else {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            dir.mkdirs()
            val target = File(dir, name)
            file.copyTo(target, overwrite = true)
            return target.absolutePath
        }
    }

    private fun sanitize(title: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim()
        return clean.take(70).ifBlank { "Audiobook" }
    }
}
