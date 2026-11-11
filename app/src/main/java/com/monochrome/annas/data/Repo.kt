package com.monochrome.annas.data

import com.monochrome.annas.Native
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Repo {
    suspend fun search(query: String, page: Int, ext: String, lang: String, sort: String) =
        withContext(Dispatchers.IO) { Native.search(query, page, ext, lang, sort) }

    suspend fun book(url: String) = withContext(Dispatchers.IO) { Native.book(url) }

    suspend fun library() = withContext(Dispatchers.IO) { Native.library() }
    suspend fun libraryItem(id: String) = withContext(Dispatchers.IO) { Native.libraryItem(id) }
    suspend fun setRead(id: String, read: Boolean) = withContext(Dispatchers.IO) { Native.setRead(id, read) }
    suspend fun deleteItem(id: String) = withContext(Dispatchers.IO) { Native.deleteItem(id) }
    suspend fun downloads() = withContext(Dispatchers.IO) { Native.downloads() }
    suspend fun startDownload(requestJson: String) = withContext(Dispatchers.IO) { Native.startDownload(requestJson) }
    suspend fun cancelDownload(id: String) = withContext(Dispatchers.IO) { Native.cancelDownload(id) }
    suspend fun finishSave(id: String, uri: String, localPath: String, filename: String, size: Long) =
        withContext(Dispatchers.IO) { Native.finishSave(id, uri, localPath, filename, size) }
    suspend fun failSave(id: String, message: String) = withContext(Dispatchers.IO) { Native.failSave(id, message) }
    suspend fun setBaseUrl(url: String) = withContext(Dispatchers.IO) { Native.setBaseUrl(url) }
    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) { Native.login(email, password) }
    suspend fun logout() = withContext(Dispatchers.IO) { Native.logout() }
    suspend fun isLoggedIn() = withContext(Dispatchers.IO) { Native.isLoggedIn() }
    suspend fun fetchBytes(url: String) = withContext(Dispatchers.IO) { Native.fetchBytes(url) }
    suspend fun setCookies(header: String) = withContext(Dispatchers.IO) { Native.setCookies(header) }
    suspend fun parseSearchHtml(html: String, query: String, page: Int) =
        withContext(Dispatchers.IO) { Native.parseSearchHtml(html, query, page) }
    suspend fun parseBookJson(json: String, url: String) =
        withContext(Dispatchers.IO) { Native.parseBookJson(json, url) }
    suspend fun parseBookHtml(html: String, url: String) =
        withContext(Dispatchers.IO) { Native.parseBookHtml(html, url) }
}
