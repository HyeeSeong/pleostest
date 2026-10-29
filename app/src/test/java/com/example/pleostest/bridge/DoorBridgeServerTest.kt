package com.example.pleostest.bridge

import com.example.pleostest.vehicle.DOOR_AREAS
import com.example.pleostest.vehicle.DoorAdmission
import com.example.pleostest.vehicle.DoorScheduler
import com.example.pleostest.vehicle.PropertyCapability
import com.example.pleostest.vehicle.VehicleItemKey
import com.example.pleostest.vehicle.VehicleProperty
import com.example.pleostest.vehicle.VehicleQuery
import com.example.pleostest.vehicle.VehicleSdk
import com.example.pleostest.vehicle.VehicleStatusReader
import com.example.pleostest.vehicle.areaCapability
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback sockets; only the vehicle SDK boundary is fake. Not an AVD listener check. */
class DoorBridgeServerTest {
    private val main = Executors.newSingleThreadScheduledExecutor()
    private val scheduler = object : DoorScheduler {
        override fun nowMillis() = System.nanoTime() / 1_000_000
        override fun execute(action: () -> Unit) { main.execute(action) }
        override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = main.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
            return { task.cancel(false) }
        }
    }
    private val sdk = HeldSdk()
    private val reader = VehicleStatusReader(sdk, scheduler)
    private val gateway = ReaderBridgeGateway(reader, scheduler)
    private val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    private val host = "127.0.0.1:$port"
    private val server = DoorBridgeServer(gateway, scheduler::nowMillis, port,
        readDeadlineMillis = 300, connectionDeadlineMillis = 2_000)
    private val frontLeft = areaCapability("ROW_1_LEFT")

    @After fun tearDown() {
        onMain { gateway.close() }
        server.stop()
        onMain { reader.close() }
        main.shutdownNow()
    }

    private fun onMain(action: () -> Unit) { main.submit(action).get(2, TimeUnit.SECONDS) }

    private fun body(id: String = UUID.randomUUID().toString(), remaining: Long = 10_000,
                     query: String = "front_left_door_position") =
        """{"requestId":"$id","remainingMs":$remaining,"query":"$query"}"""

    private fun request(body: String = body(), hostHeader: String = host, extra: String = "",
                        path: String = "/v2/vehicle-read"): String {
        val bytes = body.toByteArray()
        return "POST $path HTTP/1.1\r\nHost: $hostHeader\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n$extra\r\n$body"
    }

    private fun connect() = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }

    private fun send(raw: String): Socket = connect().apply {
        getOutputStream().write(raw.toByteArray())
        getOutputStream().flush()
    }

    private fun Socket.response(): String = use { String(it.getInputStream().readBytes(), Charsets.UTF_8) }

    private fun exchange(raw: String) = send(raw).response()

    @Test fun rejectedRequestsNeverReachTheReader() {
        server.start()
        val responses = listOf(
            exchange(request(hostHeader = "localhost:$port")),
            exchange(request(extra = "Origin: http://evil.example\r\n")),
            exchange(request(extra = "Transfer-Encoding: chunked\r\n")),
            exchange(request(body = """{"requestId":"x","remainingMs":10,"query":"door_status"}""")),
            exchange(request(path = "/v1/door-position")),
            exchange(request(body = body(query = "vehicle_speed"))),
            exchange(request(body = """{"requestId":"${UUID.randomUUID()}","remainingMs":10000}""")),
            exchange("POST /v2/vehicle-read HTTP/1.1\r\nHost: $host\r\n" +
                "Content-Type: application/json\r\nContent-Length: 2000\r\n\r\n"),
            exchange("GET / HTTP/1.1\r\n" + "X: ${"a".repeat(9_000)}\r\n\r\n"),
        )
        assertEquals(listOf(403, 403, 400, 400, 400, 400, 400, 413, 400), responses.map(::status))
        responses.forEach { assertFalse(it, it.contains("requestId")) }
        assertEquals(0, sdk.capabilityCalls.get())
    }

    @Test fun secondRequestOfAnotherDomainIsBusyWhileTheFirstKeepsRunning() {
        server.start()
        val firstId = UUID.randomUUID().toString()
        val first = send(request(body(firstId)))
        sdk.awaitCapabilityCalls(1)
        val secondId = UUID.randomUUID().toString()
        val busy = exchange(request(body(secondId, query = "energy_level")))
        assertEquals(409, status(busy))
        assertTrue(busy.endsWith("""{"requestId":"$secondId","ok":false,"code":"BUSY"}"""))
        sdk.capability!!(frontLeft)
        sdk.awaitReadCalls(1)
        sdk.value!!(0, 0L)
        val closed = first.response()
        assertEquals(200, status(closed))
        assertTrue(closed, closed.contains(""""requestId":"$firstId","query":"front_left_door_position","outcome":"COMPLETE""""))
        assertTrue(closed.contains(""""property":"DOOR_POSITION","area":"ROW_1_LEFT","status":"OK""""))
        assertEquals(1, sdk.capabilityCalls.get())
        assertEquals(1, sdk.readCalls.get())
    }

    @Test fun anUiRequestAndAnAiRequestShareTheSingleSlot() {
        server.start()
        var ticket: com.example.pleostest.vehicle.DoorRequestTicket? = null
        onMain { ticket = (reader.read(VehicleQuery.WINDOW_STATUS) { } as DoorAdmission.Accepted).ticket }
        sdk.awaitCapabilityCalls(1)
        val busy = exchange(request(body(query = "window_status")))
        assertEquals(409, status(busy))
        assertEquals(1, sdk.capabilityCalls.get())
        // Cancelling the UI owner frees the slot, and only that request ended.
        onMain { assertTrue(reader.cancel(ticket!!)) }
        val id = UUID.randomUUID().toString()
        val next = send(request(body(id, remaining = 400)))
        sdk.awaitCapabilityCalls(2)
        assertEquals(200, status(next.response()))
    }

    @Test fun doorStatusRunsAllEightReadsThroughOneHttpExchange() {
        server.start()
        val id = UUID.randomUUID().toString()
        val pending = send(request(body(id, query = "door_status")))
        sdk.awaitCapabilityCalls(1)
        sdk.capability!!(areaCapability(*DOOR_AREAS.toTypedArray()))
        for (i in 0 until 4) { sdk.awaitReadCalls(i + 1); sdk.value!!(i % 2, 1_000L) }
        sdk.awaitCapabilityCalls(2)
        sdk.capability!!(areaCapability(*DOOR_AREAS.toTypedArray(), minimum = null, maximum = null))
        for (i in 4 until 8) { sdk.awaitReadCalls(i + 1); sdk.value!!(i % 2 == 0, 2_000L) }
        val response = pending.response()
        assertEquals(200, status(response))
        assertTrue(response, response.contains(""""query":"door_status","outcome":"COMPLETE""""))
        assertEquals(8, Regex(""""status":"OK"""").findAll(response).count())
        assertEquals(8, sdk.readCalls.get())
        assertEquals(listOf(VehicleProperty.DOOR_POSITION, VehicleProperty.DOOR_LOCK), sdk.capabilityLog.toList())
        assertTrue(response.toByteArray().size < 16 * 1024)
    }

    @Test fun stallAfterTheFirstSuccessEndsAsTimeoutWithoutAnyItem() {
        server.start()
        val pending = send(request(body(remaining = 400, query = "window_status")))
        sdk.awaitCapabilityCalls(1)
        sdk.capability!!(areaCapability(*DOOR_AREAS.toTypedArray(), maximum = 100.0))
        sdk.awaitReadCalls(1)
        sdk.value!!(10, 0L)
        sdk.awaitReadCalls(2)
        val response = pending.response()
        assertEquals(200, status(response))
        assertTrue(response, response.endsWith(""""outcome":"TIMEOUT","sdkVersion":"2.3.2","items":[]}"""))
        assertFalse(response.contains("\"OK\""))
        onMain { }
        assertFalse(sdk.active!!())
        val readsAtTimeout = sdk.readCalls.get()
        sdk.value!!(11, 1L)
        onMain { }
        assertEquals(readsAtTimeout, sdk.readCalls.get())
    }

    @Test fun bridgeBudgetEndsTheReaderRequestAsTimeout() {
        server.start()
        val started = System.nanoTime()
        val response = exchange(request(body(remaining = 200)))
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(200, status(response))
        assertTrue(response.contains(""""outcome":"TIMEOUT""""))
        assertTrue("elapsed $elapsed", elapsed in 150..1_500)
        onMain { }
        assertNotNull(sdk.capability)
        assertFalse(sdk.active!!())
    }

    @Test fun extraSocketsDoNotQueueAndSlowClientsAreClosedAtTheReadDeadline() {
        server.start()
        val idle = listOf(connect(), connect())
        Thread.sleep(100)
        val rejected = connect()
        val started = System.nanoTime()
        assertTrue(closedByServer(rejected))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 250)
        idle.forEach { socket ->
            socket.getOutputStream().write("POST /v2/vehicle".toByteArray())
            assertTrue(closedByServer(socket))
            socket.close()
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue("elapsed $elapsed", elapsed in 100..1_500)
        Thread.sleep(50)
        val after = exchange(request(hostHeader = "localhost:$port"))
        assertEquals(403, status(after))
        assertEquals(0, sdk.capabilityCalls.get())
    }

    @Test fun closedAdmissionAnswersAppNotReadyWithoutReading() {
        server.start()
        onMain { gateway.close() }
        val id = UUID.randomUUID().toString()
        val response = exchange(request(body(id)))
        assertEquals(503, status(response))
        assertTrue(response.endsWith("""{"requestId":"$id","ok":false,"code":"APP_NOT_READY"}"""))
        assertEquals(0, sdk.capabilityCalls.get())
    }

    @Test fun stopDuringSdkWaitCancelsWithoutNewReadsOrSuccess() {
        server.start()
        val pending = send(request())
        sdk.awaitCapabilityCalls(1)
        onMain { gateway.close() }
        server.stop()
        onMain { reader.close() }
        val leftover = try { pending.response() } catch (_: IOException) { "" }
        assertFalse(leftover, leftover.contains("\"OK\""))
        sdk.capability!!(frontLeft)
        onMain { }
        assertEquals(0, sdk.readCalls.get())
        assertFalse(sdk.active!!())
        assertThrows(ConnectException::class.java) { connect().close() }
    }

    @Test fun clientThatNeverReadsIsReleasedByTheConnectionDeadlineAndFreesTheSlot() {
        server.start()
        val stuck = send(request())
        sdk.awaitCapabilityCalls(1)
        val started = System.nanoTime()
        stuck.soTimeout = 4_000
        val text = try { String(stuck.getInputStream().readBytes()) }
            catch (_: SocketTimeoutException) { null }
            catch (_: IOException) { "" }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertNotNull("socket was not closed", text)
        assertTrue("elapsed $elapsed", elapsed < 3_000)
        stuck.close()
        onMain { }
        // The abandoned owner was cancelled, so a new request is admitted instead of answered BUSY.
        val next = exchange(request(body(remaining = 200)))
        assertEquals(next, 200, status(next))
        assertTrue(next.contains(""""outcome":"TIMEOUT""""))
        assertEquals(2, sdk.capabilityCalls.get())
    }

    @Test fun newGenerationAfterStopIsNotContaminatedByTheOldReader() {
        server.start()
        val old = send(request())
        sdk.awaitCapabilityCalls(1)
        onMain { gateway.close() }
        server.stop()
        onMain { reader.close() }
        val oldCapability = sdk.capability!!

        val freshSdk = HeldSdk()
        val freshReader = VehicleStatusReader(freshSdk, scheduler)
        val freshGateway = ReaderBridgeGateway(freshReader, scheduler)
        val freshServer = DoorBridgeServer(freshGateway, scheduler::nowMillis, port,
            readDeadlineMillis = 300, connectionDeadlineMillis = 2_000)
        try {
            freshServer.start()
            val id = UUID.randomUUID().toString()
            val next = send(request(body(id)))
            freshSdk.awaitCapabilityCalls(1)
            oldCapability(frontLeft)
            onMain { }
            assertEquals(0, sdk.readCalls.get())
            assertEquals(0, freshSdk.readCalls.get())
            freshSdk.capability!!(frontLeft)
            freshSdk.awaitReadCalls(1)
            freshSdk.value!!(1, 0L)
            val open = next.response()
            assertEquals(200, status(open))
            assertTrue(open.contains(""""requestId":"$id","query":"front_left_door_position","outcome":"COMPLETE""""))
            assertTrue(open.contains(""""meaning":"open""""))
            val leftover = try { old.response() } catch (_: IOException) { "" }
            assertFalse(leftover, leftover.contains("\"OK\""))
        } finally {
            onMain { freshGateway.close() }
            freshServer.stop()
            onMain { freshReader.close() }
        }
    }

    /** Windows may report a server-side close of an unread connection as an abort instead of EOF. */
    private fun closedByServer(socket: Socket): Boolean = try {
        socket.getInputStream().read() == -1
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    @Test fun stopBeforeStartNeverBindsThePort() {
        server.stop()
        server.start()
        ServerSocket(port, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).close()
    }

    private fun status(response: String): Int =
        Regex("^HTTP/1.1 (\\d{3}) ").find(response)?.groupValues?.get(1)?.toInt() ?: -1

    private class HeldSdk : VehicleSdk {
        val capabilityCalls = AtomicInteger()
        val readCalls = AtomicInteger()
        val capabilityLog = java.util.concurrent.CopyOnWriteArrayList<VehicleProperty>()
        @Volatile var active: (() -> Boolean)? = null
        @Volatile var capability: ((PropertyCapability) -> Unit)? = null
        @Volatile var value: ((Any?, Long) -> Unit)? = null
        override fun checkCapability(property: VehicleProperty, active: () -> Boolean,
                                     complete: (PropertyCapability) -> Unit, failed: (Exception) -> Unit) {
            capabilityLog.add(property)
            this.active = active
            capability = complete
            capabilityCalls.incrementAndGet()
        }
        override fun read(key: VehicleItemKey, active: () -> Boolean, complete: (Any?, Long) -> Unit,
                          failed: (Exception) -> Unit) {
            this.active = active
            value = complete
            readCalls.incrementAndGet()
        }
        override fun close() = Unit
        fun awaitCapabilityCalls(count: Int) = await { capabilityCalls.get() >= count }
        fun awaitReadCalls(count: Int) = await { readCalls.get() >= count }
        private fun await(condition: () -> Boolean) {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < limit) Thread.sleep(5)
            assertTrue(condition())
        }
    }
}
