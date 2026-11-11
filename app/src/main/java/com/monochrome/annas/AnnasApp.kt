package com.monochrome.annas

import android.app.Application
import android.content.Context
import com.monochrome.annas.data.ThemeController
import com.monochrome.annas.data.getBaseUrl

class AnnasApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Native.initCore(filesDir.absolutePath, cacheDir.absolutePath, getBaseUrl())
        ThemeController.init(this)
    }
}

fun Context.prefs(): android.content.SharedPreferences =
    getSharedPreferences("annas", android.content.Context.MODE_PRIVATE)
