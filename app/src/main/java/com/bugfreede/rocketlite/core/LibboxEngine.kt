package com.bugfreede.rocketlite.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import libbox.BoxService
import libbox.Libbox
import libbox.SetupOptions
import java.io.File

/**
 * sing-box core through the libbox AAR. The VpnService installs a
 * [com.bugfreede.rocketlite.vpn.LibboxPlatform] before calling start();
 * during start the core calls back into it (openTun, protect, interface
 * monitoring), so config comes from [SingBoxConfig] unchanged.
 */
class LibboxEngine(private val appContext: Context) : CoreEngine {

    private val _state = MutableStateFlow(CoreState.IDLE)
    override val state: StateFlow<CoreState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    @Volatile
    var platform: libbox.PlatformInterface? = null

    private var service: BoxService? = null

    companion object {
        @Volatile
        private var setupDone = false
    }

    private fun ensureSetup() {
        if (setupDone) return
        synchronized(LibboxEngine::class.java) {
            if (setupDone) return
            val base = File(appContext.filesDir, "libbox").apply { mkdirs() }
            val options = SetupOptions().apply {
                basePath = base.absolutePath
                workingPath = File(base, "working").apply { mkdirs() }.absolutePath
                tempPath = File(base, "temp").apply { mkdirs() }.absolutePath
                fixAndroidStack = true
            }
            Libbox.setup(options)
            setupDone = true
        }
    }

    override suspend fun start(configJson: String) {
        if (_state.value == CoreState.CONNECTING || _state.value == CoreState.CONNECTED) return
        _state.value = CoreState.CONNECTING
        _error.value = null
        try {
            ensureSetup()
            val p = platform ?: throw IllegalStateException("VPN 平台接口未就绪")
            val svc = Libbox.newService(configJson, p)
            svc.start()
            service = svc
            _state.value = CoreState.CONNECTED
        } catch (e: Exception) {
            _error.value = e.message ?: "核心启动失败"
            _state.value = CoreState.ERROR
        }
    }

    override suspend fun stop() {
        try {
            service?.close()
        } catch (_: Exception) {
        }
        service = null
        _state.value = CoreState.IDLE
    }
}
