package com.monochrome.annas.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeController {
    private val _mode = MutableStateFlow("system")
    val mode = _mode.asStateFlow()
    fun init(context: Context) { _mode.value = context.getThemeMode() }
    fun set(mode: String) { _mode.value = mode }
}
