package com.piko.agent.accessibility

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small process-wide bridge owned by the accessibility service. The service is
 * created by Android, not by Piko, so a singleton is the reliable way for the
 * foreground agent service to reach it.
 */
object AccessibilityBridge {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _windowEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val windowEvents: SharedFlow<Unit> = _windowEvents.asSharedFlow()

    @Volatile
    var service: PikoAccessibilityService? = null
        private set

    @Volatile
    var lastForegroundPackage: String? = null
        private set

    @Volatile
    var lastEventAtMs: Long = 0
        private set

    fun attach(service: PikoAccessibilityService) {
        this.service = service
        _connected.value = true
        lastEventAtMs = System.currentTimeMillis()
    }

    fun detach(service: PikoAccessibilityService) {
        if (this.service === service) {
            this.service = null
            _connected.value = false
        }
    }

    fun isConnected(): Boolean = service != null

    fun requireService(): PikoAccessibilityService =
        service ?: throw IllegalStateException(
            "Piko's accessibility service is not connected. Enable it in Android Settings > Accessibility > Piko.",
        )

    fun notifyEvent(packageName: String?) {
        if (!packageName.isNullOrBlank()) lastForegroundPackage = packageName
        lastEventAtMs = System.currentTimeMillis()
        _windowEvents.tryEmit(Unit)
    }
}
