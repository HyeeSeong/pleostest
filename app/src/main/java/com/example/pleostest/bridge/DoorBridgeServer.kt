package com.example.pleostest.bridge

import com.example.pleostest.vehicle.DoorAdmission
import com.example.pleostest.vehicle.DoorRequestTicket
import com.example.pleostest.vehicle.DoorScheduler
import com.example.pleostest.vehicle.VehicleOutcome
import com.example.pleostest.vehicle.VehicleReadResult
import com.example.pleostest.vehicle.VehicleStatusReader
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Called on a bridge worker. [respond] runs at most once, from any thread; the result cancels. */
fun interface DoorBridgeGateway {
    fun submit(request: BridgeRequest, receivedAtMillis: Long, respond: (BridgeResponse) -> Unit): () -> Unit
}

/**
 * Admits bridge requests on the reader's serial dispatcher. One gateway belongs to one screen
 * session; after [close] (on that dispatcher) nothing new reaches the reader.
 */
class ReaderBridgeGateway(
    private val reader: VehicleStatusReader,
    private val scheduler: DoorScheduler,
) : DoorBridgeGateway {
    @Volatile private var open = true

    override fun submit(request: BridgeRequest, receivedAtMillis: Long,
                        respond: (BridgeResponse) -> Unit): () -> Unit {
        val ticket = AtomicReference<DoorRequestTicket?>()
        val abandoned = AtomicBoolean()
        scheduler.execute {
            if (!open || abandoned.get()) {
                respond(DoorBridgeProtocol.notReady(request.requestId))
                return@execute
            }
            val remaining = request.remainingMs - (scheduler.nowMillis() - receivedAtMillis)
            if (remaining < 1) {
                respond(DoorBridgeProtocol.result(request.requestId, VehicleReadResult(request.query, VehicleOutcome.TIMEOUT)))
                return@execute
            }
            when (val admission = reader.read(request.query, remaining) { respond(DoorBridgeProtocol.result(request.requestId, it)) }) {
                is DoorAdmission.Accepted -> ticket.set(admission.ticket)
                DoorAdmission.Busy -> respond(DoorBridgeProtocol.busy(request.requestId))
                DoorAdmission.Unavailable -> respond(DoorBridgeProtocol.notReady(request.requestId))
            }
        }
        return {
            abandoned.set(true)
            scheduler.execute { ticket.get()?.let(reader::cancel) }
        }
    }

    fun close() { open = false }
}

/**
 * Loopback-only, single-route HTTP endpoint: one acceptor, two worker slots (a connection
 * without a free slot is closed, never queued), one deadline scheduler. Each connection carries one request and is closed by [readDeadlineMillis] for the
 * request bytes and [connectionDeadlineMillis] overall, even if a worker is blocked on I/O.
 */
class DoorBridgeServer(
    private val gateway: DoorBridgeGateway,
    private val clock: () -> Long,
    private val port: Int = BRIDGE_PORT,
    private val readDeadlineMillis: Long = 1_000,
    private val connectionDeadlineMillis: Long = 10_000,
) : BridgeEndpoint {
    private val expectedHost = "127.0.0.1:$port"
    private val slots = Semaphore(WORKERS)
    private val workers = Executors.newFixedThreadPool(WORKERS) {
        Thread(it, "door-bridge-worker").apply { isDaemon = true }
    }
    private val watchdog = ScheduledThreadPoolExecutor(1) {
        Thread(it, "door-bridge-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var stopped = false

    /** Binds 127.0.0.1 only. Blocking; call off the main thread. */
    @Throws(IOException::class)
    override fun start() {
        val socket = ServerSocket()
        try {
            socket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 4)
        } catch (error: IOException) {
            socket.close()
            throw error
        }
        listener = socket
        if (stopped) socket.close()
        Thread({ acceptLoop(socket) }, "door-bridge-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped) {
            val socket = try { server.accept() } catch (_: IOException) { return }
            if (!socket.inetAddress.isLoopbackAddress) {
                closeQuietly(socket)
                continue
            }
            val acceptedAt = clock()
            sockets.add(socket)
            if (stopped) {
                closeQuietly(socket)
                return
            }
            val closeAll = schedule(socket, connectionDeadlineMillis)
            val closeUnread = schedule(socket, readDeadlineMillis)
            val admitted = slots.tryAcquire() && try {
                workers.execute {
                    try { handle(socket, acceptedAt, closeUnread, closeAll) } finally { slots.release() }
                }
                true
            } catch (_: RejectedExecutionException) {
                slots.release()
                false
            }
            if (!admitted) {
                closeUnread?.cancel(false)
                closeAll?.cancel(false)
                sockets.remove(socket)
                closeQuietly(socket)
            }
        }
    }

    private fun schedule(socket: Socket, delay: Long): Future<*>? = try {
        watchdog.schedule({ closeQuietly(socket) }, delay, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
        closeQuietly(socket)
        null
    }

    private fun handle(socket: Socket, acceptedAt: Long, closeUnread: Future<*>?, closeAll: Future<*>?) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 2 * 1024)
            val response = exchange(input, acceptedAt, closeUnread) ?: return
            socket.getOutputStream().apply { write(response.toBytes()); flush() }
            socket.shutdownOutput()
            socket.soTimeout = DRAIN_MILLIS
            drain(input)
        } catch (_: IOException) {
        } finally {
            closeUnread?.cancel(false)
            closeAll?.cancel(false)
            sockets.remove(socket)
            closeQuietly(socket)
        }
    }

    /** Returns the one response for this connection, or null to close without writing. */
    private fun exchange(input: InputStream, acceptedAt: Long, closeUnread: Future<*>?): BridgeResponse? {
        val head = readHead(input) ?: return null
        if (head.isEmpty()) return DoorBridgeProtocol.invalid(400)
        val length = when (val check = DoorBridgeProtocol.checkHead(head, expectedHost)) {
            is HeadCheck.Rejected -> return check.response
            is HeadCheck.Accepted -> check.contentLength
        }
        val body = readExactly(input, length) ?: return null
        closeUnread?.cancel(false)
        if (input.available() > 0) return DoorBridgeProtocol.invalid(400)
        val request = DoorBridgeProtocol.parseBody(body) ?: return DoorBridgeProtocol.invalid(400)
        val receivedAt = clock()
        if (stopped) return null
        val reply = CompletableFuture<BridgeResponse>()
        val abandon = gateway.submit(request, receivedAt) { reply.complete(it) }
        val wait = connectionDeadlineMillis - (clock() - acceptedAt)
        return try {
            reply.get(wait.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            abandon()
            null
        } catch (_: InterruptedException) {
            abandon()
            null
        } catch (_: ExecutionException) {
            abandon()
            null
        }
    }

    /** Reads up to the blank line; "" when the head exceeds the limit, null on EOF. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var match = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            match = when {
                b == '\r'.code && (match == 0 || match == 2) -> match + 1
                b == '\n'.code && (match == 1 || match == 3) -> match + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (match == 4) return out.toString(Charsets.ISO_8859_1.name()).dropLast(4)
            if (out.size() >= MAX_HEAD_BYTES) return ""
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray? {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    /** Lets the peer read the response before close instead of seeing a reset. Bounded by deadlines. */
    private fun drain(input: InputStream) {
        val sink = ByteArray(1024)
        var total = 0
        while (total < 64 * 1024) {
            val n = input.read(sink)
            if (n < 0) return
            total += n
        }
    }

    /** Idempotent. Closes the listener and every open socket; in-flight workers are interrupted. */
    override fun stop() {
        if (stopped) return
        stopped = true
        listener?.let(::closeQuietly)
        sockets.forEach(::closeQuietly)
        workers.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun closeQuietly(closeable: java.io.Closeable) {
        try { closeable.close() } catch (_: IOException) { }
    }

    private companion object {
        const val WORKERS = 2
        const val DRAIN_MILLIS = 250
    }
}
