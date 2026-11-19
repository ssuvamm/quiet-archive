package com.monochrome.annas.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Shared state between the export coroutine, the foreground service and the UI. */
object AudiobookExportController {
    data class State(val active: Boolean = false, val title: String = "", val progress: String = "")

    val state = MutableStateFlow(State())
    private val _stop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val stop = _stop.asSharedFlow()

    fun update(active: Boolean, title: String = "", progress: String = "") {
        state.value = State(active, title, progress)
    }

    fun requestStop() {
        AudioExport.cancel()
        _stop.tryEmit(Unit)
    }
}
