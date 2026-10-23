package com.piko.agent.overlay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serialize captures and restore controls even if cancellation hits the settle delay. */
object OverlaySuppressor {
    private val captureMutex = Mutex()
    @Volatile private var controller: ((Boolean) -> Unit)? = null
    @Volatile private var captureSuppressed = false
    @Volatile private var securitySuppressed = false
    val isSuppressed: Boolean get() = captureSuppressed || securitySuppressed

    /** Permission/authentication surfaces must remain unobscured even after screenshot cleanup. */
    fun setSecuritySuppressed(value: Boolean) {
        securitySuppressed = value
        controller?.invoke(!isSuppressed)
    }

    fun register(controller: (Boolean) -> Unit) {
        this.controller = controller
        if (isSuppressed) controller(false)
    }
    fun unregister(controller: (Boolean) -> Unit) {
        if (this.controller === controller) this.controller = null
    }

    suspend fun <T> suppressed(block: suspend () -> T): T = captureMutex.withLock {
        if (controller == null) return@withLock block()
        try {
            withContext(Dispatchers.Main) {
                captureSuppressed = true
                controller?.invoke(false)
            }
            delay(140)
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                captureSuppressed = false
                controller?.invoke(!securitySuppressed)
            }
        }
    }
}
