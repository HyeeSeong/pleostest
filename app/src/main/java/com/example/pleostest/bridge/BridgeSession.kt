package com.example.pleostest.bridge

import com.example.pleostest.vehicle.DoorPositionReader
import java.io.IOException
import java.util.concurrent.Executor

/** The socket half of a session; [DoorBridgeServer] in production. */
interface BridgeEndpoint {
    /** Blocking; called on the bind executor. */
    @Throws(IOException::class)
    fun start()
    fun stop()
}

sealed interface BridgeStatus {
    data object Starting : BridgeStatus
    data object Listening : BridgeStatus
    /** Only the exception type is kept. */
    data class Failed(val errorType: String) : BridgeStatus
    data object Stopped : BridgeStatus
}

/**
 * Owns one bridge session (shared reader, admission gate, socket server) without any Android
 * type. A session is single use: after [stop] it cannot start again; the host creates a new one.
 * [onStatus] may be called from the bind executor or the caller of [stop].
 */
class BridgeSession(
    val reader: DoorPositionReader,
    private val closeAdmission: () -> Unit,
    private val endpoint: BridgeEndpoint,
    private val bindExecutor: Executor,
    private val onStatus: (BridgeSession, BridgeStatus) -> Unit,
) {
    private val lock = Any()
    private var started = false
    private var stopped = false

    /** Queues the bind behind any earlier session's bind/stop. Repeated calls are ignored. */
    fun start() {
        synchronized(lock) {
            if (started || stopped) return
            started = true
        }
        onStatus(this, BridgeStatus.Starting)
        bindExecutor.execute {
            if (synchronized(lock) { stopped }) return@execute
            val failure = try {
                endpoint.start()
                null
            } catch (error: Exception) {
                error.javaClass.simpleName
            }
            if (synchronized(lock) { stopped }) return@execute
            onStatus(this, if (failure == null) BridgeStatus.Listening else BridgeStatus.Failed(failure))
        }
    }

    /** Admission, then sockets, then pending request and SDK. Idempotent. */
    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
        }
        try {
            closeAdmission()
            endpoint.stop()
        } finally {
            reader.close()
        }
        onStatus(this, BridgeStatus.Stopped)
    }
}

/** Holds at most one live session; main-thread use. */
class BridgeSessionHost(private val create: () -> BridgeSession) {
    var current: BridgeSession? = null
        private set

    fun start(): BridgeSession = current ?: create().also {
        current = it
        it.start()
    }

    fun stop() {
        val session = current ?: return
        current = null
        session.stop()
    }
}
