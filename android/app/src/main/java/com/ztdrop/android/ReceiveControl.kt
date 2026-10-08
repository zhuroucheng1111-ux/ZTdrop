package com.ztdrop.android

import java.io.IOException
import java.net.Socket

/** One receive attempt owns all of its worker sockets, not the app's listeners. */
internal class ReceiveControl {
    private val gate = Any()
    @Volatile var cancelled = false
        private set
    @Volatile var discard = false
        private set
    @Volatile private var failure: Throwable? = null
    private var finished = false
    private val sockets = HashSet<Socket>()

    fun attach(value: Socket) = synchronized(gate) {
        try { checkActive() } catch (error: IOException) { value.close(); throw error }
        sockets.add(value)
        Unit
    }
    fun detach(value: Socket) { synchronized(gate) { sockets.remove(value) } }
    fun checkActive() {
        failure?.let { throw IOException("下载子任务失败", it) }
        if (cancelled || Thread.currentThread().isInterrupted) throw IOException(if (discard) "接收已停止" else "接收已暂停")
    }
    fun cancel(discard: Boolean = false): Boolean {
        val active = synchronized(gate) {
            if (finished) return false
            this.discard = this.discard || discard
            cancelled = true
            sockets.toList()
        }
        active.forEach { try { it.close() } catch (_: IOException) { } }
        return true
    }
    fun fail(error: Throwable) {
        val active = synchronized(gate) { if (failure == null) failure = error; sockets.toList() }
        active.forEach { try { it.close() } catch (_: IOException) { } }
    }
    fun finish(action: () -> Unit) = synchronized(gate) {
        checkActive()
        action()
        finished = true
    }
}
