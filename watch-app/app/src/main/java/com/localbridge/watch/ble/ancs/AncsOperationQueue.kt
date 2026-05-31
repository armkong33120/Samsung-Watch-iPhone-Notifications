package com.localbridge.watch.ble.ancs

import android.os.Handler
import android.util.Log
import java.util.ArrayDeque

class AncsOperationQueue(
    private val handler: Handler,
    private val tag: String,
    private val onTimeout: (String) -> Unit = {}
) {
    private data class Operation(
        val phase: String,
        val timeoutMs: Long,
        val start: () -> Boolean
    )

    private val pending = ArrayDeque<Operation>()
    private var active: Operation? = null
    private val timeoutRunnable = Runnable {
        val phase = active?.phase ?: return@Runnable
        Log.w(tag, "ANCS_OPERATION_TIMEOUT phase=$phase")
        onTimeout(phase)
        active = null
        drain()
    }

    fun enqueue(phase: String, timeoutMs: Long, start: () -> Boolean) {
        pending.add(Operation(phase, timeoutMs, start))
        drain()
    }

    fun completeActive(success: Boolean, reason: String = "") {
        val phase = active?.phase ?: return
        handler.removeCallbacks(timeoutRunnable)
        if (!success) {
            Log.w(tag, "ANCS_OPERATION_FAILED phase=$phase reason=$reason")
        }
        active = null
        drain()
    }

    fun clear() {
        pending.clear()
        handler.removeCallbacks(timeoutRunnable)
        active = null
    }

    fun activePhase(): String? = active?.phase

    private fun drain() {
        if (active != null) return
        val next = pending.poll() ?: return
        active = next
        Log.d(tag, "ANCS_OPERATION_START phase=${next.phase}")
        val started = runCatching { next.start() }.getOrDefault(false)
        if (!started) {
            Log.w(tag, "ANCS_OPERATION_START_FAILED phase=${next.phase}")
            active = null
            drain()
            return
        }
        handler.postDelayed(timeoutRunnable, next.timeoutMs)
    }
}
