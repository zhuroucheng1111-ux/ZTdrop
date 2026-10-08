package com.ztdrop.android

import android.content.Context

/** One application-context engine, shared by visible pages and the opt-in service. */
internal object LanRuntime {
    private var engine: LanEngine? = null
    private val listeners = LinkedHashMap<Any, () -> Unit>()
    private var serviceHeld = false
    private var requested = false

    private fun getEngine(context: Context): LanEngine = engine ?: LanEngine(context.applicationContext) {
        notifyListeners()
    }.also { engine = it }

    @Synchronized fun attach(context: Context, owner: Any, changed: () -> Unit): LanEngine {
        listeners[owner] = changed
        return getEngine(context)
    }
    @Synchronized fun detach(owner: Any) {
        listeners.remove(owner)
        closeIfIdle()
    }
    @Synchronized fun requestKeepAlive(enabled: Boolean) {
        requested = enabled
        closeIfIdle()
        notifyListeners()
    }
    @Synchronized fun retainService(context: Context) {
        getEngine(context).start()
        serviceHeld = true
        requested = true
        notifyListeners()
    }
    @Synchronized fun releaseService() {
        serviceHeld = false
        requested = false
        closeIfIdle()
        notifyListeners()
    }
    @Synchronized fun isServiceRunning(): Boolean = serviceHeld
    @Synchronized private fun notifyListeners() {
        listeners.values.toList().forEach { callback -> runCatching { callback() } }
    }
    private fun closeIfIdle() {
        if (listeners.isEmpty() && !serviceHeld && !requested) {
            val old = engine
            engine = null
            old?.stop()
        }
    }
}
