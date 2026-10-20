package com.piko.agent.overlay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Temporarily hides the floating overlay while a screenshot is captured so the
 * agent never mistakes Piko's own controls for app content.
 */
object OverlaySuppressor {
    @Volatile
    private var controller: ((Boolean) -> Unit)? = null

    fun register(controller: (Boolean) -> Unit) {
        this.controller = controller
    }

    fun unregister(controller: (Boolean) -> Unit) {
        if (this.controller === controller) this.controller = null
    }

    suspend fun <T> suppressed(block: suspend () -> T): T {
        val current = controller ?: return block()
        withContext(Dispatchers.Main) { current(false) }
        delay(140)
        return try {
            block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { current(true) }
        }
    }
}
