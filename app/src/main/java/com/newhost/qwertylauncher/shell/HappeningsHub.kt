package com.newhost.qwertylauncher.shell

import com.newhost.qwertylauncher.core.Happening
import com.newhost.qwertylauncher.core.Happenings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The shell's [Happenings]: the latest of each kind, and a stream that never blocks whoever posts. */
internal class HappeningsHub : Happenings {
    private val _latest = MutableStateFlow<Map<String, Happening>>(emptyMap())
    override val latest: StateFlow<Map<String, Happening>> = _latest.asStateFlow()

    private val _stream = MutableSharedFlow<Happening>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val stream: SharedFlow<Happening> = _stream.asSharedFlow()

    override fun post(kind: String, value: String) {
        val happening = Happening(kind, value, System.currentTimeMillis())
        _latest.update { it + (kind to happening) }
        _stream.tryEmit(happening)
    }

    private companion object {
        const val BUFFER = 32
    }
}
