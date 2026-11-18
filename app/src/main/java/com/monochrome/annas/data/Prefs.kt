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

// ---- Audio --------------------------------------------------------------

fun Context.getAudioPosition(id: String): Long = sp().getLong("audio_pos_$id", 0L)
fun Context.setAudioPosition(id: String, ms: Long) { sp().edit().putLong("audio_pos_$id", ms).apply() }

fun Context.getAudioSpeed(): Float = sp().getFloat("audio_speed", 1f)
fun Context.setAudioSpeed(v: Float) { sp().edit().putFloat("audio_speed", v).apply() }
