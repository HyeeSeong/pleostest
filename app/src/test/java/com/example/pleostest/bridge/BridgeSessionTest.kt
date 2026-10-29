package com.example.pleostest.bridge

import com.example.pleostest.vehicle.DoorAdmission
import com.example.pleostest.vehicle.DoorPositionReader
import com.example.pleostest.vehicle.FakeScheduler
import com.example.pleostest.vehicle.FakeVehicleSdk
import com.example.pleostest.vehicle.VehicleStatusReader
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSessionTest {
    /** Runs queued binds by hand, in submission order, like the single bind thread. */
    private class ManualExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class Parts(val name: String, val log: MutableList<String>) {
        val sdk = FakeVehicleSdk()
        val reader = DoorPositionReader(VehicleStatusReader(sdk, FakeScheduler()))
        var failBind: IOException? = null
        var admissionOpen = true
        val endpoint = object : BridgeEndpoint {
            override fun start() {
                log += "$name.start"
                failBind?.let { throw it }
            }
            override fun stop() { log += "$name.stop(sdkClosed=${sdk.closeCalls})" }
        }
        fun session(executor: Executor, statuses: MutableList<BridgeStatus>) = BridgeSession(
            reader,
            closeAdmission = { admissionOpen = false; log += "$name.admission(serverStopped=${log.contains("$name.stop(sdkClosed=0)")})" },
            endpoint = endpoint,
            bindExecutor = executor,
            onStatus = { _, status -> statuses += status },
        )
    }

    private val log = mutableListOf<String>()
    private val statuses = mutableListOf<BridgeStatus>()
    private val executor = ManualExecutor()

    @Test fun startBindsOnceOnTheBindExecutorAndReportsListening() {
        val parts = Parts("a", log)
        val session = parts.session(executor, statuses)
        session.start()
        session.start()
        assertTrue(log.isEmpty())
        assertEquals(listOf<BridgeStatus>(BridgeStatus.Starting), statuses)
        executor.runAll()
        assertEquals(listOf("a.start"), log)
        assertEquals(listOf(BridgeStatus.Starting, BridgeStatus.Listening), statuses)
    }

    @Test fun bindFailureIsReportedWithItsTypeOnly() {
        val parts = Parts("a", log).apply { failBind = IOException("secret detail") }
        parts.session(executor, statuses).start()
        executor.runAll()
        assertEquals(BridgeStatus.Failed("IOException"), statuses.last())
    }

    @Test fun stopClosesAdmissionThenServerThenReleasesSdk() {
        val parts = Parts("a", log)
        val session = parts.session(executor, statuses)
        session.start()
        executor.runAll()
        session.stop()
        assertEquals(listOf("a.start", "a.admission(serverStopped=false)", "a.stop(sdkClosed=0)"), log)
        assertEquals(1, parts.sdk.closeCalls)
        assertEquals(BridgeStatus.Stopped, statuses.last())
        assertEquals(DoorAdmission.Unavailable, parts.reader.read { })
    }

    @Test fun duplicateStopIsHarmless() {
        val parts = Parts("a", log)
        val session = parts.session(executor, statuses)
        session.start()
        executor.runAll()
        session.stop()
        session.stop()
        assertEquals(1, log.count { it.startsWith("a.stop") })
        assertEquals(1, parts.sdk.closeCalls)
        assertEquals(1, statuses.count { it == BridgeStatus.Stopped })
    }

    @Test fun stopBeforeTheQueuedBindRunsNeverBinds() {
        val parts = Parts("a", log)
        val session = parts.session(executor, statuses)
        session.start()
        session.stop()
        executor.runAll()
        assertTrue(log.none { it == "a.start" })
        assertEquals(BridgeStatus.Stopped, statuses.last())
    }

    @Test fun startAfterStopDoesNothing() {
        val parts = Parts("a", log)
        val session = parts.session(executor, statuses)
        session.stop()
        session.start()
        executor.runAll()
        assertTrue(log.none { it == "a.start" })
    }

    @Test fun hostStartIsIdempotentAndStopAllowsAFreshSession() {
        val made = mutableListOf<Parts>()
        val host = BridgeSessionHost {
            Parts("s${made.size + 1}", log).also(made::add).session(executor, statuses)
        }
        assertNull(host.current)
        val first = host.start()
        assertSame(first, host.start())
        assertEquals(1, made.size)
        executor.runAll()
        host.stop()
        host.stop()
        assertNull(host.current)
        val second = host.start()
        assertNotSame(first, second)
        assertNotSame(made[0].reader, made[1].reader)
        executor.runAll()
        // The new session binds only after the previous one released the port.
        assertEquals(
            listOf("s1.start", "s1.admission(serverStopped=false)", "s1.stop(sdkClosed=0)", "s2.start"), log)
        assertEquals(1, made[0].sdk.closeCalls)
        assertEquals(0, made[1].sdk.closeCalls)
        assertEquals(DoorAdmission.Unavailable, made[0].reader.read { })
    }

    @Test fun lateOldBindCannotRunAfterItsReplacementBinds() {
        val host = BridgeSessionHost { Parts("s${log.size}", log).session(executor, statuses) }
        host.start()
        host.stop()
        host.start()
        executor.runAll()
        assertEquals(1, log.count { it.endsWith(".start") })
    }
}
