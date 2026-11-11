package com.monochrome.annas.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.monochrome.annas.data.Book
import com.monochrome.annas.data.DownloadProgress
import com.monochrome.annas.data.LibraryItem
import com.monochrome.annas.data.*
import com.monochrome.annas.data.Repo
import com.monochrome.annas.data.SearchResult
import com.monochrome.annas.data.parseBook
import com.monochrome.annas.data.parseLibrary
import com.monochrome.annas.data.parseOk
import com.monochrome.annas.data.parseSearchResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AnnasVM"

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext

    init {
        // Solve any site bot-check once in the background and hand the
        // session cookies to the native core, so plain HTTP (downloads,
        // covers) works afterwards too.
        viewModelScope.launch {
            kotlinx.coroutines.delay(2500)
            WebFetch.warmup(context.getBaseUrl())
        }
    }

    var searchQuery by mutableStateOf("")
    var searchExt by mutableStateOf("any")
    var searchLang by mutableStateOf("any")
    var searchSort by mutableStateOf("relevance")

    var searchLoading by mutableStateOf(false)
    var searchError by mutableStateOf<String?>(null)
    var searchResults by mutableStateOf<List<SearchResult>>(emptyList())
    var searchHasMore by mutableStateOf(false)
    var searchExhausted by mutableStateOf(false)
    var searchTook by mutableStateOf(0L)
    var searchPage by mutableStateOf(1)

    var bookLoading by mutableStateOf(false)
    var bookError by mutableStateOf<String?>(null)
    var book by mutableStateOf<Book?>(null)
    var bookUrl by mutableStateOf<String?>(null)

    private val _library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val library = _library.asStateFlow()
    var libraryLoading by mutableStateOf(false)

    private fun searchUrl(page: Int): String {
        val base = context.getBaseUrl().trimEnd('/')
        val sb = StringBuilder(base).append("/search?q=")
            .append(java.net.URLEncoder.encode(searchQuery.trim(), "UTF-8"))
        if (searchExt.isNotBlank() && !searchExt.equals("any", true)) {
            sb.append("&ext=").append(java.net.URLEncoder.encode(searchExt, "UTF-8"))
        }
        if (searchLang.isNotBlank() && !searchLang.equals("any", true)) {
            sb.append("&lang=").append(java.net.URLEncoder.encode(searchLang, "UTF-8"))
        }
        if (searchSort.isNotBlank() && !searchSort.equals("relevance", true)) {
            sb.append("&sort=").append(java.net.URLEncoder.encode(searchSort, "UTF-8"))
        }
        if (page > 1) sb.append("&page=").append(page)
        return sb.toString()
    }

    // Errors worth retrying through the WebView path: browser checks and
    // transient site trouble (5xx, timeouts) rather than hard failures.
    private fun isDdg(error: String?): Boolean =
        error != null && (error.contains("ddg_challenge")
            || error.contains("403")
            || error.contains("protection")
            || error.contains("HTTP 5")
            || error.contains("timed out")
            || error.contains("timeout"))

    // Turn technical errors into something a reader can act on.
    fun friendlyError(error: String?): String {
        val e = error.orEmpty()
        if (e.isBlank()) return "Something went wrong."
        val lower = e.lowercase()
        return when {
            lower.contains("ddg_challenge") || lower.contains("browser check") ->
                "The site wants to check your browser first. Give it a moment, then tap RETRY."
            lower.contains("http 5") ->
                "The site is having trouble right now. Tap RETRY in a moment."
            lower.contains("timeout") || lower.contains("timed out") ->
                "The site took too long to answer. Check your connection and tap RETRY."
            lower.contains("403") ->
                "The site blocked this request. Sign in from Settings, then try again."
            else -> e
        }
    }

    fun search(reset: Boolean = true) {
        if (reset) { searchPage = 1; searchResults = emptyList(); searchExhausted = false }
        searchLoading = true
        searchError = null
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeout(110_000L) {
                var obj: org.json.JSONObject? = null
                // Fast path: native HTTP client.
                try {
                    val raw = Repo.search(searchQuery, searchPage, searchExt, searchLang, searchSort)
                    val o = org.json.JSONObject(raw)
                    if (o.optBoolean("ok", false)) obj = o else if (!isDdg(o.optString("error"))) {
                        searchError = friendlyError(o.optString("error"))
                    }
                    android.util.Log.d(TAG, "search: fast p=$searchPage ok=${o.optBoolean("ok")} err=${o.optString("error").take(80)}")
                } catch (e: Exception) { android.util.Log.w(TAG, "search: fast path threw", e) }
                // Slow path: WebView bridge (passes the site's browser check).
                if (obj == null && searchError == null) {
                    try {
                        var html = WebFetch.fetch(searchUrl(searchPage))
                        var raw = Repo.parseSearchHtml(html, searchQuery, searchPage)
                        var o = org.json.JSONObject(raw)
                        // The site occasionally serves a header-only shell with
                        // no result cards (backend reloading). One retry, like
                        // a browser reload would do.
                        if (o.optBoolean("ok", false) && o.optJSONArray("results")?.length() == 0) {
                            android.util.Log.d(TAG, "search: empty page shell — retrying once")
                            html = WebFetch.fetch(searchUrl(searchPage))
                            raw = Repo.parseSearchHtml(html, searchQuery, searchPage)
                            o = org.json.JSONObject(raw)
                        }
                        android.util.Log.d(TAG, "search: webview p=$searchPage ok=${o.optBoolean("ok")}")
                        if (o.optBoolean("ok", false)) obj = o else searchError = friendlyError(o.optString("error"))
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "search: webview path threw", e)
                        searchError = friendlyError(e.message)
                    }
                }
                if (obj != null) {
                    val results = parseSearchResults(obj.optJSONArray("results")?.toString() ?: "[]")
                    searchResults = if (reset) results else (searchResults + results).distinctBy { it.id }
                    searchHasMore = obj.optBoolean("has_more", false)
                    searchExhausted = results.isEmpty() && !searchHasMore
                    searchTook = obj.optLong("took_ms", 0)
                    android.util.Log.d(TAG, "search: done results=${results.size} hasMore=$searchHasMore")
                } else if (searchError == null) {
                    searchError = "Search failed"
                }
                // An exhausted transient page shouldn't skip a whole page on retry.
                if (!reset && searchError != null && searchPage > 1) searchPage--
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "search: failed", e)
                searchError = e.message ?: "Search failed"
            } finally {
                searchLoading = false
            }
        }
    }

    fun loadMore() {
        if (searchLoading || searchExhausted) return
        searchPage++
        search(false)
    }

    fun openBook(url: String, preview: SearchResult?) {
        bookUrl = url
        book = null
        bookError = null
        bookLoading = true
        viewModelScope.launch {
            try {
                var raw: String? = null
                // Fast path: native HTTP client (site JSON record).
                try {
                    val r = Repo.book(url)
                    val o = org.json.JSONObject(r)
                    if (o.optBoolean("ok", false)) raw = r else if (!isDdg(o.optString("error"))) {
                        bookError = friendlyError(o.optString("error"))
                    }
                } catch (_: Exception) {}
                // Slow path: WebView bridge.
                if (raw == null && bookError == null) {
                    try {
                        // The /db/aarecord JSON endpoint is not exposed on the
                        // .gl deployment — fetch the /md5/<hash> page itself.
                        val html = WebFetch.fetch(url)
                        raw = Repo.parseBookHtml(html, url)
                    } catch (e: Exception) {
                        bookError = friendlyError(e.message)
                    }
                }
                if (raw != null) {
                    val o = org.json.JSONObject(raw)
                    if (o.optBoolean("ok", false)) {
                        book = parseBook(raw)
                    } else {
                        bookError = friendlyError(o.optString("error"))
                    }
                } else if (bookError == null) {
                    bookError = "Could not load this book."
                }
            } catch (e: Exception) {
                bookError = friendlyError(e.message)
            } finally {
                bookLoading = false
            }
        }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            libraryLoading = true
            try {
                _library.value = parseLibrary(Repo.library())
            } catch (_: Exception) {}
            libraryLoading = false
        }
    }

    fun toggleRead(item: LibraryItem) {
        viewModelScope.launch {
            Repo.setRead(item.id, !item.read)
            refreshLibrary()
        }
    }

    fun deleteItem(item: LibraryItem, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                if (item.uri != null) {
                    val uri = android.net.Uri.parse(item.uri)
                    try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                } else if (item.localPath != null) {
                    java.io.File(item.localPath).delete()
                }
            } catch (_: Exception) {}
            Repo.deleteItem(item.id)
            refreshLibrary()
            onDone()
        }
    }

    fun startDownload(book: Book, link: com.monochrome.annas.data.LinkRow?, replace: Boolean = false) {
        val url = link?.url ?: book.downloads.firstOrNull()?.url ?: return
        // The book's own metadata carries the real format (e.g. "MOBI");
        // slow links don't reveal an extension, so seed it from there.
        val metaFormat = book.metadata.firstOrNull { it.label.equals("Format", true) }
            ?.value?.trim(' ', '.')?.lowercase().orEmpty()
        val req = com.monochrome.annas.data.DownloadRequest(
            id = book.id,
            title = book.title,
            authors = book.authors,
            format = link?.kind?.let { if (it == "direct") book.downloads.firstOrNull()?.label?.substringAfterLast('.') ?: "" else metaFormat } ?: "",
            url = url,
            cover = book.cover,
            bookUrl = book.sourceUrl,
            replace = replace,
        )
        com.monochrome.annas.data.Downloads.start(context, req)
    }

    var hasDownloadsFilter by mutableStateOf(false)
    val downloadAvailability = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    // Bookmarked books that are not downloaded, fetched lazily for the SAVED tab.
    var savedBooks by mutableStateOf<List<SavedBook>>(emptyList())
    private var savedFetchedOnce = false

    /** Build the SAVED tab list: bookmarks the library cannot show. */
    fun refreshSaved(force: Boolean = false) {
        if (savedFetchedOnce && !force) return
        savedFetchedOnce = true
        viewModelScope.launch {
            val downloadedIds = _library.value.map { it.id }.toSet()
            val ids = context.getSavedBooks().filter { it !in downloadedIds }
            // Keep what we already know while refreshing.
            val known = savedBooks.associateBy { it.id }.toMutableMap()
            savedBooks = ids.mapNotNull { known[it] }
            withContext(Dispatchers.IO) {
                ids.forEach { id ->
                    if (known.containsKey(id)) return@forEach
                    val url = "${context.getBaseUrl().trimEnd('/')}/md5/$id"
                    try {
                        val html = WebFetch.fetch(url)
                        val book = parseBook(Repo.parseBookHtml(html, url))
                        if (book != null) {
                            val meta = listOfNotNull(
                                book.metadata.firstOrNull { it.label.equals("Year", true) }?.value,
                                book.metadata.firstOrNull { it.label.equals("Format", true) }?.value?.trim('.')?.uppercase(),
                                book.metadata.firstOrNull { it.label.equals("Language", true) }?.value?.uppercase(),
                                book.metadata.firstOrNull { it.label.equals("Size", true) }?.value?.uppercase(),
                            ).joinToString(" · ")
                            known[id] = SavedBook(id, book.title, book.authors, book.cover, url, meta)
                        }
                    } catch (_: Exception) {
                        known[id] = SavedBook(id, "Saved book", emptyList(), null, url, "")
                    }
                    // Publish progressively so rows appear as they resolve.
                    savedBooks = context.getSavedBooks()
                        .filter { it !in downloadedIds }
                        .mapNotNull { known[it] }
                }
            }
        }
    }

    /** Trim the saved list to the current bookmarks (used after un-saving). */
    fun pruneSaved() {
        val ids = context.getSavedBooks().toSet()
        savedBooks = savedBooks.filter { it.id in ids }
    }

    // On the real Anna's Archive nearly every record offers a slow download,
    // so the filter treats results as available unless a probe said otherwise.
    fun checkDownloadAvailability() {
        if (!hasDownloadsFilter) return
        viewModelScope.launch {
            searchResults.forEach { r ->
                if (r.id !in downloadAvailability) {
                    downloadAvailability[r.id] = true
                }
            }
        }
    }
    fun saveBookmark(id: String): Boolean = context.toggleSaved(id)
    fun isBookmarked(id: String): Boolean = context.isSaved(id)
}
