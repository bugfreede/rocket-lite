package com.bugfreede.rocketlite.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CoreState { IDLE, CONNECTING, CONNECTED, ERROR }

/**
 * The seam between the app and the proxy core. [LibboxEngine] is the real
 * implementation (sing-box via libbox); [StubCoreEngine] remains only as a
 * pre-init fallback so the UI can always read a state flow.
 */
interface CoreEngine {
    val state: StateFlow<CoreState>
    val error: StateFlow<String?>
    suspend fun start(configJson: String)
    suspend fun stop()
}

/** Fails fast with an honest message; used before [EngineHolder.init]. */
class StubCoreEngine : CoreEngine {
    private val _state = MutableStateFlow(CoreState.IDLE)
    override val state: StateFlow<CoreState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    override suspend fun start(configJson: String) {
        _state.value = CoreState.CONNECTING
        _error.value = "核心尚未初始化"
        _state.value = CoreState.ERROR
    }

    override suspend fun stop() {
        _error.value = null
        _state.value = CoreState.IDLE
    }
}

object EngineHolder {
    @Volatile
    private var libboxEngine: LibboxEngine? = null

    private val fallback: CoreEngine = StubCoreEngine()

    fun init(context: Context) {
        if (libboxEngine == null) {
            synchronized(this) {
                if (libboxEngine == null) {
                    libboxEngine = LibboxEngine(context.applicationContext)
                }
            }
        }
    }

    val engine: CoreEngine
        get() = libboxEngine ?: fallback

    val libbox: LibboxEngine?
        get() = libboxEngine
}
