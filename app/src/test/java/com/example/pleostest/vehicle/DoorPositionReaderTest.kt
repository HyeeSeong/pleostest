package com.example.pleostest.vehicle

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import org.junit.Assert.*
import org.junit.Test

/** The front-left facade over the shared status reader keeps the original Door reader contract. */
class DoorPositionReaderTest {
    private val sdk = FakeVehicleSdk()
    private val scheduler = FakeScheduler()
    private val reader = DoorPositionReader(VehicleStatusReader(sdk, scheduler))
    private val results = mutableListOf<DoorReadResult>()
    private val readable = DoorCapability("READ", 1, 0.0, 1.0, "ROW_1_LEFT")

    private fun start(budget: Long = DoorPositionReader.TIMEOUT_MILLIS): DoorRequestTicket =
        (reader.read(budget, results::add) as DoorAdmission.Accepted).ticket
    private fun support(capability: DoorCapability = readable) { sdk.capability(toProperty(capability)) }

    private fun toProperty(capability: DoorCapability) = PropertyCapability(capability.access, capability.reportedAreas,
        if (capability.matchingAreas == 0) emptyMap()
        else mapOf("ROW_1_LEFT" to AreaSupport(capability.matchingAreas, capability.minimum, capability.maximum)))

    @Test fun groupedAreaNeverAuthorizesIndividualDoorRead() {
        val grouped = DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT)
        assertFalse(matchesDoorArea(grouped, DOOR_POSITION_TARGET))
        assertFalse(matchesDoorArea(DoorArea(DoorValue.ROW_1_RIGHT), DOOR_POSITION_TARGET))
        assertTrue(matchesDoorArea(DoorArea(DoorValue.ROW_1_LEFT), DOOR_POSITION_TARGET))
        start()
        support(readable.copy(matchingAreas = if (matchesDoorArea(grouped, DOOR_POSITION_TARGET)) 1 else 0))
        assertEquals(DoorReadStatus.AREA_UNSUPPORTED, results.single().status)
        assertEquals(0, sdk.readCalls)
    }

    @Test fun closedAndOpenComeFromThisRequestsValueAndReceiptTime() {
        for ((raw, expected) in listOf(0 to DoorReadStatus.CLOSED, 1 to DoorReadStatus.OPEN)) {
            start()
            support()
            sdk.value(raw, 1234L + raw)
            assertEquals(expected, results.last().status)
            assertEquals(raw, results.last().rawValue)
            assertEquals(1234L + raw, results.last().receivedAtMillis)
            assertEquals("ROW_1_LEFT", results.last().area)
        }
        assertEquals(2, sdk.readCalls)
    }

    @Test fun nullAndUnknownValuesNeverBecomeNormalStates() {
        for (raw in listOf(null, -1, 2)) {
            start()
            support()
            sdk.value(raw, 20)
            assertEquals(if (raw == null) DoorReadStatus.NO_VALUE else DoorReadStatus.UNKNOWN_VALUE,
                results.last().status)
            assertEquals(raw, results.last().rawValue)
        }
    }

    @Test fun anotherKnownRangePreservesRawValueWithoutGuessingMeaning() {
        start()
        support(readable.copy(maximum = 100.0))
        sdk.value(1, 20)
        assertEquals(DoorReadStatus.UNKNOWN_VALUE, results.single().status)
        assertEquals(1, results.single().rawValue)
    }

    @Test fun capabilityFailureIsNotAreaUnsupportedAndNeverReads() {
        start()
        sdk.capabilityError(SecurityException())
        support()
        assertEquals(DoorReadStatus.CAPABILITY_FAILED, results.single().status)
        assertEquals("SecurityException", results.single().errorType)
        assertEquals(0, sdk.readCalls)
    }

    @Test fun confirmedMissingAreaAndUnreadableAccessHaveDifferentOutcomes() {
        start()
        support(readable.copy(matchingAreas = 0, reportedAreas = "ROW_1_RIGHT"))
        assertEquals(DoorReadStatus.AREA_UNSUPPORTED, results.last().status)
        for (access in listOf("NONE", "WRITE")) {
            start()
            support(readable.copy(access = access))
            assertEquals(DoorReadStatus.READ_UNAVAILABLE, results.last().status)
        }
        assertEquals(0, sdk.readCalls)
    }

    @Test fun ambiguousOrInvalidBoundsDoNotProceedToGet() {
        for (capability in listOf(readable.copy(matchingAreas = 2),
            readable.copy(minimum = null), readable.copy(maximum = null),
            readable.copy(minimum = Double.NaN), readable.copy(maximum = Double.POSITIVE_INFINITY),
            readable.copy(minimum = 2.0))) {
            start()
            support(capability)
            assertEquals(DoorReadStatus.SUPPORT_UNCONFIRMED, results.last().status)
        }
        assertEquals(0, sdk.readCalls)
    }

    @Test fun readWriteAccessAllowsOneReadAndDuplicateCapabilityIsIgnored() {
        start()
        support(readable.copy(access = "READ_WRITE"))
        support()
        sdk.capabilityError(IllegalStateException())
        assertEquals(1, sdk.readCalls)
        assertTrue(results.isEmpty())
        sdk.value(1, 20)
        sdk.readError(IllegalStateException())
        sdk.value(0, 21)
        assertEquals(DoorReadStatus.OPEN, results.single().status)
    }

    @Test fun busyRequestIsRejectedAndNoAutomaticRetryOccurs() {
        start()
        assertSame(DoorAdmission.Busy, reader.read(complete = results::add))
        scheduler.advance(10_000)
        scheduler.advance(30_000)
        assertEquals(DoorReadStatus.TIMEOUT, results.single().status)
        assertEquals(1, sdk.capabilityCalls)
        assertEquals(0, sdk.readCalls)
    }

    @Test fun capabilityAndGetShareOneTenSecondDeadline() {
        start()
        scheduler.advance(6_000)
        support()
        scheduler.advance(3_999)
        assertTrue(results.isEmpty())
        scheduler.advance(1)
        sdk.value(1, 20)
        assertEquals(DoorReadStatus.TIMEOUT, results.single().status)
        assertNull(results.single().capability) // a timeout never exports partial evidence
        assertNull(results.single().rawValue)
        assertEquals(1, sdk.readCalls)
    }

    @Test fun valueBeforeDeadlineWinsAndCancelsTimeout() {
        start()
        scheduler.advance(6_000)
        support()
        scheduler.advance(3_999)
        sdk.value(1, 20)
        scheduler.advance(10_000)
        assertEquals(DoorReadStatus.OPEN, results.single().status)
    }

    @Test fun expiredCallbackCannotWinEvenWhenTimerDispatchIsDelayed() {
        start()
        support()
        scheduler.now = 10_000
        sdk.value(1, 20)
        assertEquals(DoorReadStatus.TIMEOUT, results.single().status)
    }

    @Test fun lateCapabilityCannotReadOrContaminateNextRequest() {
        start()
        val oldCapability = sdk.capability
        val oldFailure = sdk.capabilityError
        scheduler.advance(10_000)
        start()
        oldCapability(toProperty(readable))
        oldFailure(IllegalStateException())
        assertEquals(0, sdk.readCalls)
        support()
        sdk.value(0, 20)
        assertEquals(listOf(DoorReadStatus.TIMEOUT, DoorReadStatus.CLOSED), results.map { it.status })
    }

    @Test fun lateValueCannotContaminateNextRequest() {
        start()
        support()
        val oldValue = sdk.value
        scheduler.advance(10_000)
        start()
        support()
        oldValue(1, 20)
        assertEquals(1, results.size)
        sdk.value(0, 21)
        assertEquals(DoorReadStatus.CLOSED, results.last().status)
    }

    @Test fun readFailureWinsOnceAndDoesNotRetry() {
        start()
        support()
        sdk.readError(SecurityException())
        sdk.value(0, 20)
        scheduler.advance(20_000)
        assertEquals(DoorReadStatus.READ_FAILED, results.single().status)
        assertEquals(1, sdk.readCalls)
        assertEquals(1, sdk.capabilityCalls)
    }

    @Test fun synchronousSdkExceptionsAreTerminalAtTheirOwnStage() {
        sdk.throwCapability = true
        start()
        assertEquals(DoorReadStatus.CAPABILITY_FAILED, results.last().status)
        sdk.throwCapability = false
        sdk.throwRead = true
        start()
        support()
        assertEquals(DoorReadStatus.READ_FAILED, results.last().status)
        scheduler.advance(20_000)
        assertEquals(2, results.size)
    }

    @Test fun lifecycleCancelFinishesBeforeReleaseAndIgnoresAllLateCallbacks() {
        for (afterCapability in listOf(false, true)) {
            val boundary = FakeVehicleSdk()
            val clock = FakeScheduler()
            val subject = DoorPositionReader(VehicleStatusReader(boundary, clock))
            val received = mutableListOf<DoorReadResult>()
            subject.read {
                assertEquals(0, boundary.closeCalls)
                received.add(it)
            }
            val active = boundary.active
            if (afterCapability) boundary.capability(toProperty(readable))
            subject.close()
            subject.close()
            boundary.capability(toProperty(readable))
            boundary.capabilityError(IllegalStateException())
            if (afterCapability) {
                boundary.value(1, 20)
                boundary.readError(IllegalStateException())
            }
            clock.advance(20_000)
            assertEquals(DoorReadStatus.CANCELLED, received.single().status)
            assertEquals(1, boundary.closeCalls)
            assertSame(DoorAdmission.Unavailable, subject.read(complete = received::add))
            assertFalse(active())
        }
    }

    @Test fun bridgeBudgetIsSharedByCapabilityAndGet() {
        start(budget = 4_000)
        scheduler.advance(3_000)
        support()
        scheduler.advance(999)
        assertTrue(results.isEmpty())
        scheduler.advance(1)
        sdk.value(0, 20)
        assertEquals(DoorReadStatus.TIMEOUT, results.single().status)
        assertEquals(1, sdk.readCalls)
    }

    @Test fun budgetOutsideOneToTenSecondsIsRejectedBeforeSdk() {
        for (budget in listOf(0L, -1L, 10_001L)) {
            assertThrows(IllegalArgumentException::class.java) { reader.read(budget, results::add) }
        }
        assertEquals(0, sdk.capabilityCalls)
        start()
    }

    @Test fun ownerCancelEndsOnlyThatRequestAndAllowsANewRead() {
        val first = start()
        support()
        assertTrue(reader.cancel(first))
        assertEquals(DoorReadStatus.CANCELLED, results.single().status)
        sdk.value(1, 20)
        val second = start()
        assertFalse(reader.cancel(first))
        support()
        sdk.value(0, 21)
        assertEquals(listOf(DoorReadStatus.CANCELLED, DoorReadStatus.CLOSED), results.map { it.status })
        assertFalse(reader.cancel(second))
        start()
    }

    @Test fun expiredOrFinishedRequestsTellQueuedSdkWorkToSkip() {
        start()
        val capabilityActive = sdk.active
        assertTrue(capabilityActive())
        support()
        val readActive = sdk.active
        assertTrue(readActive())
        scheduler.now = 10_000
        assertFalse(readActive())
        assertFalse(capabilityActive())
        scheduler.advance(0)
        start()
        assertFalse(capabilityActive())
        assertTrue(sdk.active())
    }
}
