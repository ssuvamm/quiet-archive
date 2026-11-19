package com.monochrome.annas.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private fun Context.sp() = getSharedPreferences("annas", Context.MODE_PRIVATE)

fun Context.getThemeMode(): String = sp().getString("theme", "system") ?: "system"
fun Context.setThemeMode(v: String) { sp().edit().putString("theme", v).apply() }

fun Context.getOnboarded(): Boolean = sp().getBoolean("onboarded", false)
fun Context.setOnboarded(v: Boolean) { sp().edit().putBoolean("onboarded", v).apply() }

fun Context.getFolderUri(): String? = sp().getString("folder_uri", null)
fun Context.setFolderUri(v: String?) { sp().edit().putString("folder_uri", v).apply() }

/** Human readable label for the picked storage folder, e.g. "Internal storage/Download/Books". */
fun Context.folderLabel(uri: String?): String {
    if (uri.isNullOrBlank()) return "App storage (no permission needed)"
    return try {
        val docId = java.net.URLDecoder.decode(uri.substringAfter("/tree/", ""), "UTF-8")
        val volume = docId.substringBefore(':')
        val path = docId.substringAfter(':', "").trim('/')
        val vol = if (volume.equals("primary", true)) "Internal storage" else "SD card"
        if (path.isBlank()) vol else "$vol/$path"
    } catch (_: Exception) {
        "Selected folder"
    }
}

fun Context.getBaseUrl(): String = sp().getString("base_url", "https://annas-archive.gl") ?: "https://annas-archive.gl"
fun Context.setBaseUrl(v: String) { sp().edit().putString("base_url", v).apply() }

fun Context.getSavedBooks(): List<String> {
    val raw = sp().getString("saved_books", "[]") ?: "[]"
    return try { val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) } } catch (_: Exception) { emptyList() }
}
fun Context.setSavedBooks(ids: List<String>) {
    val a = JSONArray()
    ids.forEach { a.put(it) }
    sp().edit().putString("saved_books", a.toString()).apply()
}
fun Context.isSaved(id: String): Boolean = getSavedBooks().contains(id)
fun Context.toggleSaved(id: String): Boolean {
    val list = getSavedBooks().toMutableList()
    val now = if (list.remove(id)) false else { list.add(id); true }
    setSavedBooks(list)
    return now
}

// ---- Saved-book metadata cache ------------------------------------------
//
// Bookmarks used to re-fetch every /md5/ page on each launch, which made the
// SAVED tab look empty for tens of seconds. Titles, authors, covers and
// metadata almost never change, so keep them on device and only refresh in
// the background when the cache is old (or a bookmark is new).

private const val SAVED_CACHE_KEY = "saved_books_cache"

fun Context.getSavedBookCache(): List<SavedBook> {
    val raw = sp().getString(SAVED_CACHE_KEY, null) ?: return emptyList()
    return try {
        val o = JSONObject(raw)
        val arr = o.optJSONArray("books") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val b = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = b.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val authors = b.optJSONArray("authors")
            SavedBook(
                id = id,
                title = b.optString("title", "Saved book"),
                authors = if (authors == null) emptyList() else
                    (0 until authors.length()).mapNotNull { j -> authors.optString(j).takeIf { it.isNotBlank() } },
                cover = b.optString("cover").takeIf { it.isNotBlank() },
                url = b.optString("url"),
                meta = b.optString("meta"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Age of the on-device metadata cache; Long.MAX_VALUE when there is none. */
fun Context.getSavedCacheAgeMs(): Long {
    val raw = sp().getString(SAVED_CACHE_KEY, null) ?: return Long.MAX_VALUE
    return try {
        System.currentTimeMillis() - JSONObject(raw).optLong("ts", 0L)
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}

fun Context.setSavedBookCache(books: List<SavedBook>) {
    val arr = JSONArray()
    books.forEach { b ->
        val o = JSONObject()
            .put("id", b.id)
            .put("title", b.title)
            .put("url", b.url)
            .put("meta", b.meta)
        val authors = JSONArray()
        b.authors.forEach { authors.put(it) }
        o.put("authors", authors)
        if (!b.cover.isNullOrBlank()) o.put("cover", b.cover)
        arr.put(o)
    }
    val root = JSONObject()
        .put("ts", System.currentTimeMillis())
        .put("books", arr)
    sp().edit().putString(SAVED_CACHE_KEY, root.toString()).apply()
}

// ---- Reader -------------------------------------------------------------

/** Position inside a book: chapter index plus how far down that chapter we are. */
data class ReadingPosition(val chapter: Int, val fraction: Float)

private fun Context.readingMap(): JSONObject {
    val raw = sp().getString("reading_progress", "{}") ?: "{}"
    return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
}

fun Context.getReadingPosition(id: String): ReadingPosition {
    val o = readingMap().optJSONObject(id) ?: return ReadingPosition(0, 0f)
    return ReadingPosition(o.optInt("chapter", 0), o.optDouble("fraction", 0.0).toFloat())
}

fun Context.setReadingPosition(id: String, position: ReadingPosition) {
    val map = readingMap()
    map.put(id, JSONObject().put("chapter", position.chapter).put("fraction", position.fraction.toDouble()))
    sp().edit().putString("reading_progress", map.toString()).apply()
}

/** Reader body size in sp, remembered across books. */
fun Context.getReaderFontSize(): Float = sp().getFloat("reader_font", 18f)
fun Context.setReaderFontSize(v: Float) { sp().edit().putFloat("reader_font", v).apply() }

/** "paper", "sepia" or "night". */
fun Context.getReaderTheme(): String = sp().getString("reader_theme", "paper") ?: "paper"
fun Context.setReaderTheme(v: String) { sp().edit().putString("reader_theme", v).apply() }

fun Context.getReaderLineHeight(): Float = sp().getFloat("reader_line", 1.65f)
fun Context.setReaderLineHeight(v: Float) { sp().edit().putFloat("reader_line", v).apply() }

fun Context.getReaderFontFamily(): String = sp().getString("reader_font_family", "serif") ?: "serif"
fun Context.setReaderFontFamily(v: String) { sp().edit().putString("reader_font_family", v).apply() }

fun Context.getReaderMargin(): Int = sp().getInt("reader_margin", 6)
fun Context.setReaderMargin(v: Int) { sp().edit().putInt("reader_margin", v).apply() }

fun Context.getReaderBrightness(): Float = sp().getFloat("reader_brightness", 1f)
fun Context.setReaderBrightness(v: Float) { sp().edit().putFloat("reader_brightness", v).apply() }

fun Context.getReaderSwipeMode(): String = sp().getString("reader_swipe", "chapters") ?: "chapters"
fun Context.setReaderSwipeMode(v: String) { sp().edit().putString("reader_swipe", v).apply() }

fun Context.getReaderTapZones(): Boolean = sp().getBoolean("reader_tap_zones", false)
fun Context.setReaderTapZones(v: Boolean) { sp().edit().putBoolean("reader_tap_zones", v).apply() }

data class ReaderHighlight(val chapter: Int, val text: String, val note: String)

fun Context.getReaderHighlights(bookId: String): List<ReaderHighlight> {
    val raw = sp().getString("reader_highlights_$bookId", "[]") ?: "[]"
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ReaderHighlight(o.optInt("chapter", 0), o.optString("text"), o.optString("note"))
        }
    } catch (_: Exception) { emptyList() }
}

fun Context.addReaderHighlight(bookId: String, highlight: ReaderHighlight) {
    val list = getReaderHighlights(bookId).toMutableList()
    list.add(highlight)
    val arr = JSONArray()
    list.forEach { h ->
        arr.put(JSONObject().put("chapter", h.chapter).put("text", h.text).put("note", h.note))
    }
    sp().edit().putString("reader_highlights_$bookId", arr.toString()).apply()
}

fun Context.removeReaderHighlight(bookId: String, index: Int) {
    val list = getReaderHighlights(bookId).toMutableList()
    if (index in list.indices) list.removeAt(index)
    val arr = JSONArray()
    list.forEach { h ->
        arr.put(JSONObject().put("chapter", h.chapter).put("text", h.text).put("note", h.note))
    }
    sp().edit().putString("reader_highlights_$bookId", arr.toString()).apply()
}

fun Context.getReaderBookmarks(id: String): Set<Int> {
    val raw = sp().getString("reader_marks_$id", "") ?: ""
    return raw.split(',').mapNotNull { it.toIntOrNull() }.toSet()
}

fun Context.toggleReaderBookmark(id: String, chapter: Int): Set<Int> {
    val marks = getReaderBookmarks(id).toMutableSet()
    if (!marks.add(chapter)) marks.remove(chapter)
    sp().edit().putString("reader_marks_$id", marks.joinToString(",")).apply()
    return marks
}

// ---- Audio --------------------------------------------------------------

fun Context.getAudioPosition(id: String): Long = sp().getLong("audio_pos_$id", 0L)
fun Context.setAudioPosition(id: String, ms: Long) { sp().edit().putLong("audio_pos_$id", ms).apply() }

fun Context.getAudioSpeed(): Float = sp().getFloat("audio_speed", 1f)
fun Context.setAudioSpeed(v: Float) { sp().edit().putFloat("audio_speed", v).apply() }

// ---- Search history -----------------------------------------------------

fun Context.getSearchHistory(): List<String> {
    val raw = sp().getString("search_history", "[]") ?: "[]"
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) { emptyList() }
}

fun Context.addSearchHistory(query: String) {
    val clean = query.trim()
    if (clean.isBlank()) return
    val list = getSearchHistory().filter { !it.equals(clean, true) }.toMutableList()
    list.add(0, clean)
    sp().edit().putString("search_history", JSONArray(list.take(12)).toString()).apply()
}

fun Context.clearSearchHistory() {
    sp().edit().putString("search_history", "[]").apply()
}

fun Context.getPinnedSearches(): Set<String> {
    val raw = sp().getString("search_pins", "[]") ?: "[]"
    return try {
        val a = JSONArray(raw)
        (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (_: Exception) { emptySet() }
}

fun Context.toggleSearchPin(query: String) {
    val pins = getPinnedSearches().toMutableSet()
    if (!pins.add(query)) pins.remove(query)
    sp().edit().putString("search_pins", JSONArray(pins).toString()).apply()
}

fun Context.removeSearchHistory(query: String) {
    val list = getSearchHistory().filter { !it.equals(query, true) }
    sp().edit().putString("search_history", JSONArray(list).toString()).apply()
}

// ---- Downloads ----------------------------------------------------------

fun Context.getWifiOnly(): Boolean = sp().getBoolean("wifi_only", false)
fun Context.setWifiOnly(v: Boolean) { sp().edit().putBoolean("wifi_only", v).apply() }

fun Context.getAudioFormat(): String = sp().getString("audio_format", "m4a") ?: "m4a"
fun Context.setAudioFormat(v: String) { sp().edit().putString("audio_format", v).apply() }

// ---- Text-to-speech -----------------------------------------------------

/** Package name of the chosen TTS engine, or null for the system default. */
fun Context.getTtsEngine(): String? = sp().getString("tts_engine", null)?.takeIf { it.isNotBlank() }
fun Context.setTtsEngine(engine: String?) {
    if (engine.isNullOrBlank()) sp().edit().remove("tts_engine").apply()
    else sp().edit().putString("tts_engine", engine).apply()
}

// ---- Linked audiobooks --------------------------------------------------

fun Context.getAudiobookUri(bookId: String): String? = sp().getString("audiobook_$bookId", null)

fun Context.setAudiobookUri(bookId: String, uri: String?) {
    if (uri == null) sp().edit().remove("audiobook_$bookId").apply()
    else sp().edit().putString("audiobook_$bookId", uri).apply()
}

fun Context.getAudiobookLinks(): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val all = sp().all
    for ((key, value) in all) {
        if (key.startsWith("audiobook_") && value is String && value.isNotBlank()) {
            out[key.removePrefix("audiobook_")] = value
        }
    }
    return out
}

/** Bookmarks in an audiobook: position in ms -> label. */
fun Context.getAudioBookmarks(bookId: String): Map<Long, String> {
    val raw = sp().getString("audiobook_marks_$bookId", "{}") ?: "{}"
    return try {
        val o = JSONObject(raw)
        (0 until o.length()).associate { i ->
            val key = o.names()?.getString(i) ?: return@associate 0L to ""
            key.toLongOrNull()?.let { it to o.optString(key) } ?: (0L to "")
        }.filter { it.value.isNotBlank() }
    } catch (_: Exception) { emptyMap() }
}

fun Context.addAudioBookmark(bookId: String, positionMs: Long, label: String) {
    val marks = getAudioBookmarks(bookId).toMutableMap()
    marks[positionMs] = label
    val o = JSONObject()
    marks.forEach { (pos, name) -> o.put(pos.toString(), name) }
    sp().edit().putString("audiobook_marks_$bookId", o.toString()).apply()
}

fun Context.removeAudioBookmark(bookId: String, positionMs: Long) {
    val marks = getAudioBookmarks(bookId).toMutableMap()
    marks.remove(positionMs)
    val o = JSONObject()
    marks.forEach { (pos, name) -> o.put(pos.toString(), name) }
    sp().edit().putString("audiobook_marks_$bookId", o.toString()).apply()
}
