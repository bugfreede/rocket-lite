package com.bugfreede.rocketlite.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CoreState { IDLE, CONNECTING, CONNECTED, ERROR }

/**
 * The seam between the app and the proxy core.
 * Phase 2 replaces [StubCoreEngine] with a libbox-backed implementation:
 * the service keeps owning the Android VpnService side, the engine owns
 * the sing-box instance configured with [SingBoxConfig] output.
 */
interface CoreEngine {
    val state: StateFlow<CoreState>
    val error: StateFlow<String?>
    suspend fun start(configJson: String)
    suspend fun stop()
}

/**
 * Placeholder until libbox lands. It deliberately fails fast with an
 * honest message instead of pretending to tunnel traffic nowhere.
 */
class StubCoreEngine : CoreEngine {
    private val _state = MutableStateFlow(CoreState.IDLE)
    override val state: StateFlow<CoreState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    override suspend fun start(configJson: String) {
        _state.value = CoreState.CONNECTING
        // TODO(phase 2): hand configJson to libbox (Libbox.newStandaloneCommandClient
        // / BoxService) and report CONNECTED once the core is up.
        _error.value = "sing-box 核心尚未接入（二期接入 libbox 后可用）"
        _state.value = CoreState.ERROR
    }

    override suspend fun stop() {
        _error.value = null
        _state.value = CoreState.IDLE
    }
}

object EngineHolder {
    val engine: CoreEngine = StubCoreEngine()
}
