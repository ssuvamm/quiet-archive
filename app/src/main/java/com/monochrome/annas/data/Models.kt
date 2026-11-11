package com.monochrome.annas.data

import org.json.JSONArray
import org.json.JSONObject

data class SearchResult(
    val id: String,
    val title: String,
    val authors: List<String>,
    val publisher: String?,
    val year: String?,
    val language: String?,
    val format: String?,
    val rating: String?,
    val quality: String?,
    val cover: String?,
    val url: String,
    val isbn: String?,
    val size: String?,
)

fun parseSearchResults(json: String): List<SearchResult> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SearchResult(
            id = o.optString("id"),
            title = o.optString("title", "Untitled"),
            authors = o.optJSONArray("authors")?.toStringList() ?: emptyList(),
            publisher = o.optStringOrNull("publisher"),
            year = o.optStringOrNull("year"),
            language = o.optStringOrNull("language"),
            format = o.optStringOrNull("format"),
            rating = o.optStringOrNull("rating"),
            quality = o.optStringOrNull("quality"),
            cover = o.optStringOrNull("cover"),
            url = o.optString("url"),
            isbn = o.optStringOrNull("isbn"),
            size = o.optStringOrNull("size"),
        )
    }
} catch (_: Exception) { emptyList() }

data class MetaRow(val label: String, val value: String)
data class LinkRow(val label: String, val url: String, val kind: String, val note: String?)

/** A bookmarked book that is not downloaded; fetched lazily for the SAVED tab. */
data class SavedBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val cover: String?,
    val url: String,
    val meta: String,
)

data class Book(
    val id: String,
    val title: String,
    val authors: List<String>,
    val cover: String?,
    val description: String?,
    val rating: String?,
    val quality: String?,
    val comments: String?,
    val requestCode: String?,
    val metadata: List<MetaRow>,
    val downloads: List<LinkRow>,
    val actions: List<LinkRow>,
    val sourceUrl: String,
)

fun parseBook(json: String): Book? = try {
    val o = JSONObject(json).optJSONObject("book") ?: return null
    Book(
        id = o.optString("id"),
        title = o.optString("title", "Untitled"),
        authors = o.optJSONArray("authors")?.toStringList() ?: emptyList(),
        cover = o.optStringOrNull("cover"),
        description = o.optStringOrNull("description"),
        rating = o.optStringOrNull("rating"),
        quality = o.optStringOrNull("quality"),
        comments = o.optStringOrNull("comments"),
        requestCode = o.optStringOrNull("request_code"),
        metadata = o.optJSONArray("metadata")?.mapObjects { MetaRow(it.optString("label"), it.optString("value")) } ?: emptyList(),
        downloads = o.optJSONArray("downloads")?.mapObjects { LinkRow(it.optString("label"), it.optString("url"), it.optString("kind", "direct"), it.optStringOrNull("note")) } ?: emptyList(),
        actions = o.optJSONArray("actions")?.mapObjects { LinkRow(it.optString("label"), it.optString("url"), it.optString("kind", "external"), it.optStringOrNull("note")) } ?: emptyList(),
        sourceUrl = o.optString("source_url"),
    )
} catch (_: Exception) { null }

data class DownloadProgress(
    val id: String,
    val title: String,
    val authors: List<String>,
    val state: String,
    val received: Long,
    val total: Long,
    val speed: Long,
    val format: String,
    val filename: String,
    val tempPath: String?,
    val error: String?,
    val createdAt: Long,
)

fun parseDownloads(json: String): List<DownloadProgress> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        DownloadProgress(
            id = o.optString("id"),
            title = o.optString("title", "Download"),
            authors = o.optJSONArray("authors")?.toStringList() ?: emptyList(),
            state = o.optString("state", "queued"),
            received = o.optLong("received", 0),
            total = o.optLong("total", 0),
            speed = o.optLong("speed", 0),
            format = o.optString("format", ""),
            filename = o.optString("filename", ""),
            tempPath = o.optStringOrNull("temp_path"),
            error = o.optStringOrNull("error"),
            createdAt = o.optLong("created_at", 0),
        )
    }
} catch (_: Exception) { emptyList() }

data class LibraryItem(
    val id: String,
    val title: String,
    val authors: List<String>,
    val format: String,
    val size: Long,
    val cover: String?,
    val bookUrl: String?,
    val uri: String?,
    val localPath: String?,
    val filename: String,
    val mime: String,
    val savedAt: Long,
    val read: Boolean,
)

fun parseLibrary(json: String): List<LibraryItem> = try {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        LibraryItem(
            id = o.optString("id"),
            title = o.optString("title", "Untitled"),
            authors = o.optJSONArray("authors")?.toStringList() ?: emptyList(),
            format = o.optString("format", ""),
            size = o.optLong("size", 0),
            cover = o.optStringOrNull("cover"),
            bookUrl = o.optStringOrNull("book_url"),
            uri = o.optStringOrNull("uri"),
            localPath = o.optStringOrNull("local_path"),
            filename = o.optString("filename", ""),
            mime = o.optString("mime", "application/octet-stream"),
            savedAt = o.optLong("saved_at", 0),
            read = o.optBoolean("read", false),
        )
    }
} catch (_: Exception) { emptyList() }

data class DownloadRequest(
    val id: String,
    val title: String,
    val authors: List<String>,
    val format: String,
    val url: String,
    val cover: String?,
    val bookUrl: String?,
    val replace: Boolean,
)
fun parseOk(json: String): Triple<Boolean, String?, JSONObject?> {
    return try {
        val o = JSONObject(json)
        Triple(o.optBoolean("ok", false), o.optStringOrNull("error"), o)
    } catch (_: Exception) { Triple(false, "parse error", null) }
}

private fun org.json.JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { i -> optString(i).takeIf { it.isNotBlank() } }

private fun <T> JSONArray.mapObjects(map: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(map) }
