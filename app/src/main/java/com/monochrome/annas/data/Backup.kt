package com.monochrome.annas.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Portable backup of the library index, bookmarks, progress and preferences. */
object Backup {
    private const val VERSION = 1
    private val RESTORE_KEYS = listOf(
        "saved_books", "saved_books_cache", "reading_progress",
        "reader_font", "reader_theme", "reader_line", "reader_font_family",
        "reader_margin", "reader_brightness", "reader_swipe", "reader_tap_zones",
        "audio_speed", "wifi_only", "audio_format", "tts_engine",
        "search_history", "search_pins", "folder_uri", "base_url",
    )

    suspend fun export(context: Context, target: Uri): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("annas", Context.MODE_PRIVATE)
        val prefsJson = JSONObject()
        for ((key, value) in prefs.all) {
            if (key in RESTORE_KEYS || key.startsWith("audiobook_")) {
                if (value is String || value is Boolean || value is Int || value is Long || value is Float) {
                    prefsJson.put(key, value)
                }
            }
        }
        val library = JSONArray(Repo.library())
        val root = JSONObject()
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("prefs", prefsJson)
            .put("library", library)
        val output = context.contentResolver.openOutputStream(target)
            ?: throw IllegalStateException("Could not write the backup file")
        output.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
        library.length()
    }

    suspend fun import(context: Context, source: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not read the backup file")
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val prefs = context.getSharedPreferences("annas", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val saved = root.optJSONObject("prefs") ?: JSONObject()
        saved.keys().forEach { key ->
            if (key in RESTORE_KEYS || key.startsWith("audiobook_")) {
                val value = saved.get(key)
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> editor.putFloat(key, value.toFloat())
                }
            }
        }
        editor.apply()

        val library = root.optJSONArray("library") ?: JSONArray()
        var restored = 0
        for (i in 0 until library.length()) {
            val item = library.optJSONObject(i) ?: continue
            val result = runCatching { JSONObject(Repo.importLibraryItem(item.toString())) }.getOrNull()
            if (result?.optBoolean("ok", false) == true) restored++
        }
        restored
    }
}
