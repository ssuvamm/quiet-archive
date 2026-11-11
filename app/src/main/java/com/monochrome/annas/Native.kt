package com.monochrome.annas

object Native {
    init { System.loadLibrary("annas_core") }
    external fun initCore(filesDir: String, cacheDir: String, baseUrl: String)
    external fun setBaseUrl(url: String)
    external fun search(query: String, page: Int, ext: String, lang: String, sort: String): String
    external fun book(url: String): String
    external fun startDownload(requestJson: String): String
    external fun downloads(): String
    external fun cancelDownload(id: String)
    external fun finishSave(id: String, uri: String, localPath: String, filename: String, size: Long): String
    external fun failSave(id: String, message: String)
    external fun library(): String
    external fun libraryItem(id: String): String
    external fun setRead(id: String, read: Boolean): String
    external fun deleteItem(id: String): String
    external fun fetchBytes(url: String): ByteArray?
    external fun storageStats(): String
    external fun setCookies(header: String)
    external fun parseSearchHtml(html: String, query: String, page: Int): String
    external fun parseBookJson(json: String, url: String): String
    external fun parseBookHtml(html: String, url: String): String
    external fun login(email: String, password: String): String
    external fun logout(): String
    external fun isLoggedIn(): String
}
