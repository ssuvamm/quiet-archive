package com.monochrome.annas.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * In-app reading support.
 *
 * EPUBs are unpacked once into the cache and read chapter by chapter, so
 * large books stay cheap to open. Plain text, Markdown, FB2 and loose HTML
 * are turned into the same chapter list. PDFs are paged by the reader screen
 * itself (PdfRenderer).
 */
data class ReaderChapter(val title: String, val href: String)

/** Chapter markup plus the base URL its relative images/styles resolve against. */
data class ReaderPage(val html: String, val baseUrl: String?)

interface ReaderDocument {
    val title: String
    val chapters: List<ReaderChapter>

    /** Chapter markup, themed and ready for a WebView. */
    suspend fun chapterPage(index: Int, fontSize: Float, theme: String): ReaderPage

    /** Plain text of a chapter (used by text-to-speech). */
    suspend fun chapterText(index: Int): String
}

object Reader {
    private val TEXT_EXT = setOf("txt", "text", "md", "markdown")
    private val HTML_EXT = setOf("html", "htm", "xhtml")
    private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "wav", "flac")

    fun extensionOf(item: LibraryItem): String {
        val fromName = item.filename.substringAfterLast('.', "").lowercase()
        if (fromName.isNotBlank()) return fromName
        val fromFormat = item.format.lowercase().trim('.')
        if (fromFormat.isNotBlank()) return fromFormat
        return item.mime.substringAfterLast('/', "").lowercase()
    }

    /** Formats the built-in reader understands. */
    fun isReadable(item: LibraryItem): Boolean = when (extensionOf(item)) {
        "epub", "fb2", "pdf" -> true
        in TEXT_EXT -> true
        in HTML_EXT -> true
        else -> false
    }

    fun isPdf(item: LibraryItem): Boolean = extensionOf(item) == "pdf"

    fun isAudio(item: LibraryItem): Boolean =
        extensionOf(item) in AUDIO_EXT || item.mime.startsWith("audio/")

    suspend fun open(context: Context, item: LibraryItem): ReaderDocument = withContext(Dispatchers.IO) {
        when (val ext = extensionOf(item)) {
            "epub" -> openEpub(context, item)
            "fb2" -> openFb2(context, item)
            in TEXT_EXT -> openText(context, item)
            in HTML_EXT -> openHtml(context, item)
            else -> throw IllegalArgumentException("This format can't be opened in the reader yet ($ext).")
        }
    }

    // ---- EPUB ------------------------------------------------------------

    private fun openEpub(context: Context, item: LibraryItem): ReaderDocument {
        val file = localFile(context, item)
        val root = File(context.cacheDir, "reader/${safeName(item.id)}")
        val marker = File(root, ".unpacked")
        if (!marker.isFile || marker.length() == 0L) {
            root.deleteRecursively()
            root.mkdirs()
            unpack(file, root)
            marker.writeText("ok")
        }
        val container = File(root, "META-INF/container.xml").takeIf { it.isFile }?.readText()
        val opfPath = container?.let {
            Regex("<rootfile[^>]*full-path=\"([^\"]+)\"").find(it)?.groupValues?.get(1)
        } ?: findOpf(root)
        if (opfPath == null) throw IllegalStateException("This EPUB is missing its package file.")
        val opfFile = File(root, opfPath)
        if (!opfFile.isFile) throw IllegalStateException("This EPUB is missing its package file.")
        val opf = opfFile.readText()
        val baseDir = opfPath.substringBeforeLast('/', "")

        val title = Regex("<dc:title[^>]*>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
            ?.takeIf { it.isNotBlank() } ?: item.title

        val manifest = mutableMapOf<String, String>()
        Regex("<item\\b[^>]*>").findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            manifest[id] = resolve(baseDir, href)
        }

        // Chapter titles: EPUB 3 nav document first, then EPUB 2 NCX.
        val titles = mutableMapOf<String, String>()
        val navItem = Regex("<item\\b[^>]*>").findAll(opf)
            .map { it.value }
            .firstOrNull { attr(it, "properties")?.contains("nav") == true }
        if (navItem != null) {
            val navPath = attr(navItem, "href")?.let { resolve(baseDir, it) }
            val navFile = navPath?.let { File(root, it) }
            if (navFile?.isFile == true) {
                Regex("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(navFile.readText())
                    .forEach { match ->
                        val href = resolve(navPath.substringBeforeLast('/', ""), match.groupValues[1])
                        val label = cleanInline(match.groupValues[2])
                        if (label.isNotBlank()) titles.putIfAbsent(href, label)
                    }
            }
        }
        val ncxItem = Regex("<item\\b[^>]*>").findAll(opf).map { it.value }
            .firstOrNull { attr(it, "media-type") == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxPath = attr(ncxItem, "href")?.let { resolve(baseDir, it) }
            val ncxFile = ncxPath?.let { File(root, it) }
            if (ncxFile?.isFile == true) {
                Regex("<navPoint\\b.*?</navPoint>", RegexOption.DOT_MATCHES_ALL).findAll(ncxFile.readText())
                    .forEach { point ->
                        val label = Regex("<text[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
                            .find(point.value)?.groupValues?.get(1)?.let(::cleanInline)
                        val src = Regex("<content[^>]*src=\"([^\"]+)\"").find(point.value)?.groupValues?.get(1)
                        if (label != null && src != null) {
                            val href = resolve(ncxPath.substringBeforeLast('/', ""), src)
                            if (label.isNotBlank()) titles.putIfAbsent(href, label)
                        }
                    }
            }
        }

        val spine = Regex("<itemref\\b[^>]*>").findAll(opf)
            .mapNotNull { attr(it.value, "idref") }
            .mapNotNull { manifest[it] }
            .filter { File(root, it).isFile }
            .toList()

        val htmlFiles = spine.ifEmpty {
            manifest.values.filter { it.substringAfterLast('.', "").lowercase() in HTML_EXT }
        }
        if (htmlFiles.isEmpty()) throw IllegalStateException("This EPUB has no readable chapters.")

        val chapters = htmlFiles.distinct().mapIndexed { i, href ->
            val label = titles[href]
                ?: File(root, href).takeIf { it.isFile }?.let { chapterHeading(it.readText()) }
                ?: "Chapter ${i + 1}"
            ReaderChapter(label, href)
        }
        return EpubDocument(root, title, chapters)
    }

    private fun findOpf(root: File): String? =
        root.walkTopDown().firstOrNull { it.isFile && it.name.lowercase().endsWith(".opf") }
            ?.relativeTo(root)?.invariantSeparatorsPath

    private fun unpack(epub: File, into: File) {
        val rootPath = into.canonicalFile.toPath()
        ZipInputStream(epub.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val target = File(into, entry.name).canonicalFile
                if (!target.toPath().startsWith(rootPath)) continue // zip-slip
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> zip.copyTo(out) }
                zip.closeEntry()
            }
        }
    }

    private class EpubDocument(
        private val root: File,
        override val title: String,
        override val chapters: List<ReaderChapter>,
    ) : ReaderDocument {
        override suspend fun chapterPage(index: Int, fontSize: Float, theme: String): ReaderPage =
            withContext(Dispatchers.IO) {
                val chapter = chapters.getOrNull(index) ?: return@withContext ReaderPage("", null)
                val file = File(root, chapter.href)
                if (!file.isFile) return@withContext ReaderPage("", null)
                val raw = file.readText()
                val base = file.parentFile?.toURI()?.toString() ?: root.toURI().toString()
                ReaderPage(decorate(raw, fontSize, theme), base)
            }

        override suspend fun chapterText(index: Int): String = withContext(Dispatchers.IO) {
            val chapter = chapters.getOrNull(index) ?: return@withContext ""
            val file = File(root, chapter.href)
            if (!file.isFile) "" else htmlToText(file.readText())
        }
    }

    // ---- plain text ------------------------------------------------------

    private fun openText(context: Context, item: LibraryItem): ReaderDocument {
        val text = decode(localFile(context, item).readBytes())
        val parts = splitText(text)
        val chapters = parts.mapIndexed { i, part -> ReaderChapter(part.first.ifBlank { "Part ${i + 1}" }, i.toString()) }
        return ChunkDocument(item.title, chapters, parts.map { it.second })
    }

    private fun openHtml(context: Context, item: LibraryItem): ReaderDocument {
        val raw = decode(localFile(context, item).readBytes())
        val chapter = ReaderChapter(chapterHeading(raw) ?: item.title, "0")
        return ChunkDocument(item.title, listOf(chapter), listOf(raw))
    }

    /** Turn a wall of text into readable chunks, respecting paragraph breaks. */
    private fun splitText(text: String): List<Pair<String, String>> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        val target = 9000
        for (block in normalized.split(Regex("\n\\s*\n"))) {
            if (buffer.length > 0 && buffer.length + block.length > target) {
                chunks.add(buffer.toString().trim())
                buffer.setLength(0)
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(block.trim())
        }
        if (buffer.isNotBlank()) chunks.add(buffer.toString().trim())
        if (chunks.isEmpty()) chunks.add(normalized.trim())
        return chunks.mapIndexed { i, body ->
            val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val looksLikeHeading = firstLine.length in 1..60 &&
                !firstLine.endsWith('.') && !firstLine.endsWith(',')
            val title = if (looksLikeHeading) firstLine else "Part ${i + 1}"
            title to body
        }
    }

    // ---- FB2 -------------------------------------------------------------

    private fun openFb2(context: Context, item: LibraryItem): ReaderDocument {
        val xml = decode(localFile(context, item).readBytes())
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var bookTitle = item.title
        var inDescription = false
        var inBookTitle = false
        val bookTitleBuf = StringBuilder()

        class Section(val paragraphs: MutableList<String> = mutableListOf()) {
            var title: String = ""
        }

        val sections = mutableListOf<Section>()
        var sectionDepth = 0
        var current: Section? = null
        var inTitle = false
        val titleBuf = StringBuilder()
        val para = StringBuilder()

        fun flush() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) current?.paragraphs?.add(t)
            para.setLength(0)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "description" -> inDescription = true
                    "book-title" -> if (inDescription) inBookTitle = true
                    "body" -> Unit
                    "section" -> {
                        sectionDepth++
                        if (sectionDepth == 1) {
                            flush()
                            current = Section().also { sections.add(it) }
                        }
                    }
                    "title" -> inTitle = true
                    "p" -> flush()
                    "empty-line" -> flush()
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty()
                    when {
                        inBookTitle -> bookTitleBuf.append(text)
                        inTitle -> titleBuf.append(text)
                        current != null -> para.append(text)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "description" -> inDescription = false
                    "book-title" -> inBookTitle = false
                    "p" -> flush()
                    "title" -> {
                        inTitle = false
                        if (current != null && current!!.title.isBlank()) current!!.title = titleBuf.toString().trim()
                        titleBuf.setLength(0)
                    }
                    "section" -> {
                        if (sectionDepth == 1) {
                            flush()
                            current = null
                        }
                        sectionDepth = maxOf(0, sectionDepth - 1)
                    }
                }
            }
            event = parser.next()
        }
        if (bookTitleBuf.isNotBlank()) bookTitle = bookTitleBuf.toString().trim()
        if (sections.isEmpty()) throw IllegalStateException("This FB2 has no readable sections.")
        val chapters = sections.mapIndexed { i, s ->
            ReaderChapter(s.title.ifBlank { "Section ${i + 1}" }, i.toString())
        }
        val bodies = sections.map { s ->
            val heading = if (s.title.isBlank()) "" else "<h2>${escape(s.title)}</h2>"
            heading + s.paragraphs.joinToString("") { "<p>${escape(it)}</p>" }
        }
        return ChunkDocument(bookTitle, chapters, bodies)
    }

    private class ChunkDocument(
        override val title: String,
        override val chapters: List<ReaderChapter>,
        private val bodies: List<String>,
    ) : ReaderDocument {
        override suspend fun chapterPage(index: Int, fontSize: Float, theme: String): ReaderPage =
            withContext(Dispatchers.IO) {
                val body = bodies.getOrNull(index) ?: return@withContext ReaderPage("", null)
                val wrapped = if (body.contains("<html", true)) body
                else "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body>$body</body></html>"
                ReaderPage(decorate(wrapped, fontSize, theme), null)
            }

        override suspend fun chapterText(index: Int): String = withContext(Dispatchers.IO) {
            htmlToText(bodies.getOrNull(index) ?: "")
        }
    }

    // ---- shared helpers --------------------------------------------------

    private fun localFile(context: Context, item: LibraryItem): File {
        item.localPath?.let { path -> File(path).takeIf { it.isFile }?.let { return it } }
        val uri = item.uri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: throw IllegalStateException("This file is no longer available.")
        val dir = File(context.cacheDir, "reader-files").apply { mkdirs() }
        val name = item.filename.ifBlank { "book.${extensionOf(item)}" }
        val out = File(dir, safeName(item.id.ifBlank { name }) + "-" + name)
        if (!out.isFile || out.length() == 0L) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not read this file.")
        }
        return out
    }

    private fun resolve(baseDir: String, href: String): String {
        val decoded = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
        val clean = decoded.substringBefore('#').substringBefore('?')
        val parts = ArrayDeque<String>()
        val path = if (clean.startsWith("/")) clean.trimStart('/') else "$baseDir/$clean"
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            ?: Regex("\\b$name\\s*=\\s*'([^']*)'").find(tag)?.groupValues?.get(1)

    private fun chapterHeading(html: String): String? {
        val heading = Regex("(?is)<h[12][^>]*>(.*?)</h[12]>").find(html)?.groupValues?.get(1)
            ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)
        return heading?.let(::cleanInline)?.takeIf { it.isNotBlank() }?.take(80)
    }

    private fun cleanInline(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "book" }

    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            // Older Russian/European plain text is usually Windows-1251.
            String(bytes, charset("windows-1251"))
        }
    }

    /** Strip scripts, then add the reader's own typography on top of the book's styles. */
    private fun decorate(raw: String, fontSize: Float, theme: String): String {
        var html = raw
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
            .replace(Regex("(?is)<iframe[^>]*>.*?</iframe>"), "")
        val css = readerCss(fontSize, theme)
        html = if (html.contains("</head>", true)) {
            html.replaceFirst(Regex("(?i)</head>"), "<style>$css</style></head>")
        } else {
            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<style>$css</style></head><body>$html</body></html>"
        }
        return html
    }

    private fun readerCss(fontSize: Float, theme: String): String {
        val (bg, fg, link) = when (theme) {
            "night" -> Triple("#121212", "#d8d4cd", "#9ab8d8")
            "sepia" -> Triple("#f2e8d5", "#3b3226", "#7a5a2e")
            else -> Triple("#faf8f4", "#1c1a17", "#3f5f8a")
        }
        return """
            html, body { background: $bg !important; color: $fg !important; }
            body {
              font-size: ${fontSize.toInt()}px !important;
              line-height: 1.65 !important;
              margin: 0 !important;
              padding: 14px 6px 48px 6px !important;
              font-family: Georgia, 'Noto Serif', serif !important;
              -webkit-text-size-adjust: none;
              overflow-wrap: break-word;
            }
            p, div, li, blockquote { color: $fg !important; }
            h1, h2, h3, h4, h5, h6 { color: $fg !important; line-height: 1.3 !important; }
            h1 { font-size: 1.45em !important; } h2 { font-size: 1.25em !important; }
            h3 { font-size: 1.1em !important; }
            img, svg, image { max-width: 100% !important; height: auto !important; }
            a { color: $link !important; }
            pre, code { white-space: pre-wrap !important; }
            body { --reader-bg: $bg; }
        """.trimIndent()
    }

    /** A file descriptor for PdfRenderer, whether the book is public or private. */
    fun openPdf(context: Context, item: LibraryItem): android.os.ParcelFileDescriptor {
        item.localPath?.let { path ->
            val file = File(path)
            if (file.isFile) {
                return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val uri = item.uri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: throw IllegalStateException("This file is no longer available.")
        return context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Could not read this PDF.")
    }

    fun htmlToText(html: String): String = decodeEntities(
        html
            .replace(Regex("(?is)<(script|style|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
    )
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\n *"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
