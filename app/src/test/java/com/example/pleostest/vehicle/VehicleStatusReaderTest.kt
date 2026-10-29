package com.example.pleostest.vehicle

import com.example.pleostest.vehicle.VehicleProperty.*
import com.example.pleostest.vehicle.VehicleQuery.*
import org.junit.Assert.*
import org.junit.Test

class VehicleStatusReaderTest {
    private val sdk = FakeVehicleSdk()
    private val scheduler = FakeScheduler()
    private val reader = VehicleStatusReader(sdk, scheduler)
    private val results = mutableListOf<VehicleReadResult>()
    private val allDoors = areaCapability(*DOOR_AREAS.toTypedArray())
    private val allLocks = areaCapability(*DOOR_AREAS.toTypedArray(), minimum = null, maximum = null)
    private val allWindows = areaCapability(*DOOR_AREAS.toTypedArray(), minimum = 0.0, maximum = 100.0)

    private fun start(query: VehicleQuery, budget: Long = VehicleStatusReader.TIMEOUT_MILLIS): DoorRequestTicket =
        (reader.read(query, budget, results::add) as DoorAdmission.Accepted).ticket

    private fun key(property: VehicleProperty, area: String?) = VehicleItemKey(property, area)
    private fun VehicleReadResult.item(property: VehicleProperty, area: String?) =
        items.single { it.key == key(property, area) }

    /** Answers the pending sequence of reads, one callback per submitted GET. */
    private fun answer(vararg raws: Any?, at: Long = 1_000L) { raws.forEach { sdk.value(it, at) } }

    private fun completeDoors(positions: List<Any?> = listOf(0, 1, 1, 0), locks: List<Any?> = listOf(true, false, false, true)) {
        sdk.capability(allDoors)
        answer(*positions.toTypedArray())
        sdk.capability(allLocks)
        answer(*locks.toTypedArray())
    }

    @Test fun queriesExposeTheExactFixedItemSets() {
        assertEquals(8, DOOR_STATUS.keys.size)
        assertEquals(DOOR_AREAS.map { key(DOOR_POSITION, it) } + DOOR_AREAS.map { key(DOOR_LOCK, it) }, DOOR_STATUS.keys)
        assertEquals(DOOR_AREAS.map { key(WINDOW_POSITION, it) }, WINDOW_STATUS.keys)
        assertEquals(listOf(key(FUEL_PERCENT, null), key(EV_BATTERY_PERCENT, null)), ENERGY_LEVEL.keys)
        assertEquals(listOf(key(DOOR_POSITION, "ROW_1_LEFT")), FRONT_LEFT_DOOR_POSITION.keys)
        assertEquals(VehicleQuery.entries.toSet(), setOf("door_status", "window_status", "energy_level",
            "front_left_door_position").map { VehicleQuery.fromWire(it)!! }.toSet())
        assertNull(VehicleQuery.fromWire("DOOR_STATUS"))
        assertNull(VehicleQuery.fromWire("speed"))
    }

    @Test fun doorStatusKeepsFourAreasApartAndChecksLocksIndependently() {
        start(DOOR_STATUS)
        assertEquals(listOf(DOOR_POSITION), sdk.capabilityLog)
        completeDoors()
        val result = results.single()
        assertEquals(VehicleOutcome.COMPLETE, result.outcome)
        assertEquals(DOOR_STATUS.keys, result.items.map { it.key })
        assertEquals(listOf(DOOR_POSITION, DOOR_LOCK), sdk.capabilityLog)
        assertEquals(DOOR_STATUS.keys, sdk.readLog)
        val expectedPositions = mapOf("ROW_1_LEFT" to "closed", "ROW_1_RIGHT" to "open",
            "ROW_2_LEFT" to "open", "ROW_2_RIGHT" to "closed")
        for ((area, meaning) in expectedPositions) {
            val item = result.item(DOOR_POSITION, area)
            assertEquals(ItemStatus.OK, item.status)
            assertEquals(meaning, item.meaning)
            assertEquals(if (meaning == "open") 1 else 0, item.rawValue)
            assertEquals(1000L, item.receivedAtMillis)
        }
        val expectedLocks = mapOf("ROW_1_LEFT" to "locked", "ROW_1_RIGHT" to "unlocked",
            "ROW_2_LEFT" to "unlocked", "ROW_2_RIGHT" to "locked")
        for ((area, meaning) in expectedLocks) {
            assertEquals(meaning, result.item(DOOR_LOCK, area).meaning)
            assertEquals(meaning == "locked", result.item(DOOR_LOCK, area).rawValue)
        }
        assertEquals(1, sdk.capabilityLog.count { it == DOOR_POSITION })
    }

    @Test fun lockSupportIsIndependentOfPositionSupport() {
        start(DOOR_STATUS)
        sdk.capability(areaCapability("ROW_1_LEFT"))
        answer(0)
        sdk.capability(areaCapability("ROW_1_RIGHT", "ROW_2_LEFT", minimum = null, maximum = null))
        answer(false, true)
        val result = results.single()
        assertEquals(VehicleOutcome.PARTIAL, result.outcome)
        assertEquals(listOf(key(DOOR_POSITION, "ROW_1_LEFT"), key(DOOR_LOCK, "ROW_1_RIGHT"), key(DOOR_LOCK, "ROW_2_LEFT")),
            sdk.readLog)
        assertEquals(ItemStatus.OK, result.item(DOOR_POSITION, "ROW_1_LEFT").status)
        assertEquals(ItemStatus.AREA_UNSUPPORTED, result.item(DOOR_POSITION, "ROW_1_RIGHT").status)
        assertEquals(ItemStatus.AREA_UNSUPPORTED, result.item(DOOR_LOCK, "ROW_1_LEFT").status)
        assertEquals("unlocked", result.item(DOOR_LOCK, "ROW_1_RIGHT").meaning)
        assertEquals("locked", result.item(DOOR_LOCK, "ROW_2_LEFT").meaning)
        // Unsupported items carry the capability evidence and never a value or time.
        val unsupported = result.item(DOOR_POSITION, "ROW_2_RIGHT")
        assertEquals(0, unsupported.capability!!.matchingAreas)
        assertNull(unsupported.rawValue)
        assertNull(unsupported.receivedAtMillis)
    }

    @Test fun groupedOrAmbiguousAreaSupportNeverAuthorizesASingleRead() {
        start(DOOR_STATUS)
        sdk.capability(PropertyCapability("READ", "ROW_1_LEFT+ROW_1_RIGHT", mapOf(
            "ROW_1_LEFT" to AreaSupport(0, null, null), "ROW_1_RIGHT" to AreaSupport(2, 0.0, 1.0),
            "ROW_2_LEFT" to AreaSupport(1, null, 1.0), "ROW_2_RIGHT" to AreaSupport(1, 2.0, 1.0))))
        sdk.capability(PropertyCapability("READ", "", emptyMap()))
        val result = results.single()
        assertEquals(0, sdk.readCalls)
        assertEquals(VehicleOutcome.FAILED, result.outcome)
        assertEquals(ItemStatus.AREA_UNSUPPORTED, result.item(DOOR_POSITION, "ROW_1_LEFT").status)
        assertEquals(ItemStatus.SUPPORT_UNCONFIRMED, result.item(DOOR_POSITION, "ROW_1_RIGHT").status)
        assertEquals(ItemStatus.SUPPORT_UNCONFIRMED, result.item(DOOR_POSITION, "ROW_2_LEFT").status)
        assertEquals(ItemStatus.SUPPORT_UNCONFIRMED, result.item(DOOR_POSITION, "ROW_2_RIGHT").status)
    }

    @Test fun windowKeepsRawAndBoundsWithoutPercentOrDoorMeaning() {
        start(WINDOW_STATUS)
        assertEquals(listOf(WINDOW_POSITION), sdk.capabilityLog)
        sdk.capability(allWindows)
        answer(0, 1, 37, 100, at = 55L)
        val result = results.single()
        assertEquals(VehicleOutcome.COMPLETE, result.outcome)
        assertEquals(WINDOW_STATUS.keys, sdk.readLog)
        assertEquals(listOf(0, 1, 37, 100), result.items.map { it.rawValue })
        for (item in result.items) {
            assertEquals(ItemStatus.OK, item.status)
            assertNull(item.meaning)
            assertNull(item.unit)
            assertEquals(0.0, item.capability!!.minimum)
            assertEquals(100.0, item.capability!!.maximum)
            assertEquals(55L, item.receivedAtMillis)
        }
        assertEquals(1, sdk.capabilityCalls)
    }

    @Test fun windowRawZeroOrOneIsNotReadAsAClosedOrOpenDoor() {
        start(WINDOW_STATUS)
        sdk.capability(allWindows.copy(areas = allWindows.areas.mapValues { AreaSupport(1, 0.0, 1.0) }))
        answer(0, 1, 0, 1)
        assertTrue(results.single().items.all { it.meaning == null })
    }

    @Test fun energyReportsOnlyTheSupportedScalarAndNeverInventsTheOther() {
        for (fuelFirst in listOf(true, false)) {
            results.clear()
            val boundary = FakeVehicleSdk()
            val subject = VehicleStatusReader(boundary, scheduler)
            subject.read(ENERGY_LEVEL, complete = results::add)
            assertEquals(listOf(FUEL_PERCENT), boundary.capabilityLog)
            boundary.capability(energyCapability(if (fuelFirst) "READ" else "NONE"))
            if (fuelFirst) boundary.value(63, 7L)
            assertEquals(listOf(FUEL_PERCENT, EV_BATTERY_PERCENT), boundary.capabilityLog)
            boundary.capability(energyCapability(if (fuelFirst) "NONE" else "READ_WRITE"))
            if (!fuelFirst) boundary.value(81, 9L)
            val result = results.single()
            assertEquals(VehicleOutcome.PARTIAL, result.outcome)
            val fuel = result.item(FUEL_PERCENT, null)
            val ev = result.item(EV_BATTERY_PERCENT, null)
            val (ok, missing) = if (fuelFirst) fuel to ev else ev to fuel
            assertEquals(ItemStatus.OK, ok.status)
            assertEquals("%", ok.unit)
            assertEquals(if (fuelFirst) 63 else 81, ok.rawValue)
            assertNull(ok.capability!!.matchingAreas)
            assertEquals(ItemStatus.READ_UNAVAILABLE, missing.status)
            assertNull(missing.rawValue)
            assertNull(missing.unit)
            assertEquals(1, boundary.readCalls)
        }
    }

    @Test fun bothEnergyScalarsComplete() {
        start(ENERGY_LEVEL)
        sdk.capability(energyCapability())
        sdk.value(40, 1L)
        sdk.capability(energyCapability("READ_WRITE"))
        sdk.value(0, 2L)
        val result = results.single()
        assertEquals(VehicleOutcome.COMPLETE, result.outcome)
        assertEquals(listOf(40, 0), result.items.map { it.rawValue })
        assertEquals(listOf("%", "%"), result.items.map { it.unit })
    }

    @Test fun energyOutsideZeroToOneHundredKeepsRawButIsNotAPercent() {
        for (raw in listOf(-1, 101)) {
            results.clear()
            val boundary = FakeVehicleSdk()
            VehicleStatusReader(boundary, scheduler).read(ENERGY_LEVEL, complete = results::add)
            boundary.capability(energyCapability())
            boundary.value(raw, 1L)
            boundary.capability(energyCapability("NONE"))
            val fuel = results.single().item(FUEL_PERCENT, null)
            assertEquals(ItemStatus.UNKNOWN_VALUE, fuel.status)
            assertEquals(raw, fuel.rawValue)
            assertNull(fuel.unit)
            assertEquals(VehicleOutcome.FAILED, results.single().outcome)
        }
    }

    @Test fun successWithNullOrFailureIsPartialAndNothingIsDefaulted() {
        start(DOOR_STATUS)
        sdk.capability(allDoors)
        sdk.value(0, 1L)
        sdk.value(null, 2L)
        sdk.readError(IllegalStateException())
        sdk.readError(SecurityException())
        sdk.capabilityError(java.io.IOException())
        val result = results.single()
        assertEquals(VehicleOutcome.PARTIAL, result.outcome)
        assertEquals(ItemStatus.OK, result.item(DOOR_POSITION, "ROW_1_LEFT").status)
        val noValue = result.item(DOOR_POSITION, "ROW_1_RIGHT")
        assertEquals(ItemStatus.NO_VALUE, noValue.status)
        assertNull(noValue.rawValue)
        assertEquals(2L, noValue.receivedAtMillis)
        assertNull(noValue.meaning)
        assertEquals(ItemStatus.READ_FAILED, result.item(DOOR_POSITION, "ROW_2_LEFT").status)
        assertEquals("IllegalStateException", result.item(DOOR_POSITION, "ROW_2_LEFT").errorType)
        // Failed reads stop at their own stage; the group moves on without retrying.
        assertEquals(ItemStatus.READ_FAILED, result.item(DOOR_POSITION, "ROW_2_RIGHT").status)
        assertEquals("SecurityException", result.item(DOOR_POSITION, "ROW_2_RIGHT").errorType)
        // The lock capability check failed, so all four locks are capability failures.
        for (area in DOOR_AREAS) {
            val lock = result.item(DOOR_LOCK, area)
            assertEquals(ItemStatus.CAPABILITY_FAILED, lock.status)
            assertEquals("IOException", lock.errorType)
            assertNull(lock.capability)
        }
        assertEquals(4, sdk.readCalls)
    }

    @Test fun everyMeaningUnconfirmedIsFailedNotPartial() {
        start(WINDOW_STATUS)
        sdk.capability(allWindows)
        sdk.value(null, 1L)
        sdk.value(null, 2L)
        sdk.readError(IllegalStateException())
        sdk.readError(IllegalStateException())
        assertEquals(VehicleOutcome.FAILED, results.single().outcome)
        reader.read(DOOR_STATUS, complete = results::add)
        sdk.capability(allDoors.copy(areas = allDoors.areas.mapValues { AreaSupport(1, 0.0, 100.0) }))
        answer(0, 1, 2, 3)
        sdk.capability(allLocks)
        answer(true, null, true, true)
        val second = results.last()
        assertEquals(VehicleOutcome.PARTIAL, second.outcome) // three locks are real successes
        assertEquals(DOOR_AREAS.map { ItemStatus.UNKNOWN_VALUE }, DOOR_AREAS.map { second.item(DOOR_POSITION, it).status })
        assertEquals(listOf(0, 1, 2, 3), DOOR_AREAS.map { second.item(DOOR_POSITION, it).rawValue })
    }

    @Test fun doorPositionOutsideTheZeroOneRangeKeepsRawValueWithoutMeaning() {
        start(FRONT_LEFT_DOOR_POSITION)
        sdk.capability(areaCapability("ROW_1_LEFT", maximum = 100.0))
        sdk.value(1, 20)
        val item = results.single().items.single()
        assertEquals(ItemStatus.UNKNOWN_VALUE, item.status)
        assertEquals(1, item.rawValue)
        assertNull(item.meaning)
        assertEquals(VehicleOutcome.FAILED, results.single().outcome)
    }

    @Test fun capabilityFailureOfOneDomainPropertyDoesNotStopTheNext() {
        start(DOOR_STATUS)
        sdk.capabilityError(SecurityException())
        assertEquals(listOf(DOOR_POSITION, DOOR_LOCK), sdk.capabilityLog)
        sdk.capability(allLocks)
        answer(true, true, false, false)
        val result = results.single()
        assertEquals(VehicleOutcome.PARTIAL, result.outcome)
        assertEquals(ItemStatus.CAPABILITY_FAILED, result.item(DOOR_POSITION, "ROW_1_LEFT").status)
        assertEquals("SecurityException", result.item(DOOR_POSITION, "ROW_1_LEFT").errorType)
        assertEquals(4, sdk.readCalls)
    }

    @Test fun synchronousSdkExceptionsAreTerminalAtTheirOwnStageForOnlyThatItem() {
        sdk.throwCapability = true
        start(ENERGY_LEVEL)
        assertEquals(VehicleOutcome.FAILED, results.single().outcome)
        assertEquals(listOf(ItemStatus.CAPABILITY_FAILED, ItemStatus.CAPABILITY_FAILED), results.single().items.map { it.status })
        assertEquals(listOf(FUEL_PERCENT, EV_BATTERY_PERCENT), sdk.capabilityLog)
        sdk.throwCapability = false
        sdk.throwRead = true
        start(WINDOW_STATUS)
        sdk.capability(allWindows)
        assertEquals(VehicleOutcome.FAILED, results.last().outcome)
        assertTrue(results.last().items.all { it.status == ItemStatus.READ_FAILED })
        assertEquals(4, sdk.readCalls)
        scheduler.advance(20_000)
        assertEquals(2, results.size)
    }

    @Test fun nextItemStallingAfterAFirstSuccessTimesOutWithoutAnyUsablePayload() {
        for (query in listOf(DOOR_STATUS, WINDOW_STATUS)) {
            results.clear()
            val boundary = FakeVehicleSdk()
            val clock = FakeScheduler()
            val subject = VehicleStatusReader(boundary, clock)
            subject.read(query, complete = results::add)
            boundary.capability(areaCapability(*DOOR_AREAS.toTypedArray(), minimum = 0.0, maximum = 1.0))
            boundary.value(1, 5L)
            val stalled = boundary.value
            val active = boundary.active
            clock.advance(9_999)
            assertTrue(results.isEmpty())
            assertTrue(active())
            clock.advance(1)
            assertFalse(active())
            val result = results.single()
            assertEquals(VehicleOutcome.TIMEOUT, result.outcome)
            assertTrue(result.items.isEmpty())
            val submitted = boundary.readCalls
            stalled(0, 6L)
            clock.advance(20_000)
            assertEquals(submitted, boundary.readCalls)
            assertEquals(1, results.size)
        }
    }

    @Test fun energyStallTimesOutAndLateCallbackCannotContaminateTheNextRequest() {
        start(ENERGY_LEVEL)
        sdk.capability(energyCapability())
        val lateValue = sdk.value
        val lateCapability = sdk.capability
        scheduler.advance(10_000)
        assertEquals(VehicleOutcome.TIMEOUT, results.single().outcome)
        start(ENERGY_LEVEL)
        val readsBefore = sdk.readCalls
        lateValue(77, 1L)
        lateCapability(energyCapability())
        assertEquals(readsBefore, sdk.readCalls)
        assertEquals(1, results.size)
        sdk.capability(energyCapability())
        sdk.value(10, 2L)
        sdk.capability(energyCapability("NONE"))
        assertEquals(10, results.last().item(FUEL_PERCENT, null).rawValue)
    }

    @Test fun duplicateCallbacksAreIgnoredAndEachSupportedItemIsReadAtMostOnce() {
        start(WINDOW_STATUS)
        sdk.capability(allWindows)
        sdk.capability(allWindows)
        sdk.capabilityError(IllegalStateException())
        assertEquals(1, sdk.readCalls)
        val firstValue = sdk.value
        val firstError = sdk.readError
        firstValue(10, 1L)
        firstValue(11, 2L)
        firstError(IllegalStateException())
        assertEquals(2, sdk.readCalls)
        answer(12, 13, 14)
        val result = results.single()
        assertEquals(listOf(10, 12, 13, 14), result.items.map { it.rawValue })
        assertEquals(4, sdk.readCalls)
        assertEquals(1, sdk.capabilityCalls)
    }

    @Test fun oneSlotIsSharedAcrossEveryDomainAndBusyKeepsTheRunningRequest() {
        start(DOOR_STATUS)
        for (query in VehicleQuery.entries) assertSame(DoorAdmission.Busy, reader.read(query, complete = results::add))
        assertEquals(1, sdk.capabilityCalls)
        completeDoors()
        assertEquals(VehicleOutcome.COMPLETE, results.single().outcome)
        assertTrue(reader.read(WINDOW_STATUS, complete = results::add) is DoorAdmission.Accepted)
    }

    @Test fun ownerCancelEndsOnlyThatRequestAndStaleTicketsDoNothing() {
        val first = start(ENERGY_LEVEL)
        sdk.capability(energyCapability())
        assertTrue(reader.cancel(first))
        assertEquals(VehicleOutcome.CANCELLED, results.single().outcome)
        assertTrue(results.single().items.isEmpty())
        sdk.value(1, 20)
        val second = start(WINDOW_STATUS)
        assertFalse(reader.cancel(first))
        sdk.capability(allWindows)
        assertEquals(1, results.size)
        answer(1, 2, 3, 4)
        assertEquals(listOf(VehicleOutcome.CANCELLED, VehicleOutcome.COMPLETE), results.map { it.outcome })
        assertFalse(reader.cancel(second))
    }

    @Test fun lifecycleCloseCancelsBeforeReleaseAndIgnoresEveryLateCallback() {
        for (stage in 0..2) {
            val boundary = FakeVehicleSdk()
            val clock = FakeScheduler()
            val subject = VehicleStatusReader(boundary, clock)
            val received = mutableListOf<VehicleReadResult>()
            subject.read(DOOR_STATUS) {
                assertEquals(0, boundary.closeCalls)
                received.add(it)
            }
            val active = boundary.active
            if (stage >= 1) boundary.capability(allDoors)
            if (stage >= 2) boundary.value(0, 1L)
            subject.close()
            subject.close()
            val readsAtClose = boundary.readCalls
            boundary.capability(allDoors)
            boundary.capabilityError(IllegalStateException())
            if (stage >= 1) { boundary.value(1, 20); boundary.readError(IllegalStateException()) }
            clock.advance(20_000)
            assertEquals(VehicleOutcome.CANCELLED, received.single().outcome)
            assertTrue(received.single().items.isEmpty())
            assertEquals(1, boundary.closeCalls)
            assertEquals(readsAtClose, boundary.readCalls)
            assertSame(DoorAdmission.Unavailable, subject.read(DOOR_STATUS, complete = received::add))
            assertFalse(active())
        }
    }

    @Test fun reenteredSessionGetsAFreshReaderThatOldCallbacksCannotReach() {
        start(DOOR_STATUS)
        sdk.capability(allDoors)
        val oldValue = sdk.value
        reader.close()
        val freshSdk = FakeVehicleSdk()
        val fresh = VehicleStatusReader(freshSdk, scheduler)
        fresh.read(FRONT_LEFT_DOOR_POSITION, complete = results::add)
        oldValue(1, 1L)
        assertEquals(1, results.size)
        assertEquals(0, freshSdk.readCalls)
        freshSdk.capability(areaCapability("ROW_1_LEFT"))
        freshSdk.value(0, 3L)
        assertEquals(listOf(VehicleOutcome.CANCELLED, VehicleOutcome.COMPLETE), results.map { it.outcome })
    }

    @Test fun oneDeadlineCoversAllStagesAndTheBudgetAndRejectsBadValues() {
        start(WINDOW_STATUS, budget = 4_000)
        scheduler.advance(3_000)
        sdk.capability(allWindows)
        answer(1, 2)
        scheduler.advance(999)
        assertTrue(results.isEmpty())
        scheduler.advance(1)
        assertEquals(VehicleOutcome.TIMEOUT, results.single().outcome)
        assertEquals(3, sdk.readCalls)
        for (budget in listOf(0L, -1L, 10_001L)) {
            assertThrows(IllegalArgumentException::class.java) { reader.read(WINDOW_STATUS, budget, results::add) }
        }
    }

    @Test fun expiredCallbackCannotWinEvenWhenTimerDispatchIsDelayed() {
        start(ENERGY_LEVEL)
        sdk.capability(energyCapability())
        scheduler.now = 10_000
        sdk.value(10, 20)
        assertEquals(VehicleOutcome.TIMEOUT, results.single().outcome)
        assertTrue(results.single().items.isEmpty())
    }

    @Test fun frontLeftQueryTouchesOnlyTheFrontLeftPosition() {
        start(FRONT_LEFT_DOOR_POSITION)
        assertEquals(listOf(DOOR_POSITION), sdk.capabilityLog)
        sdk.capability(allDoors)
        sdk.value(1, 5L)
        assertEquals(listOf(key(DOOR_POSITION, "ROW_1_LEFT")), sdk.readLog)
        assertEquals(listOf(DOOR_POSITION), sdk.capabilityLog)
        val item = results.single().items.single()
        assertEquals(key(DOOR_POSITION, "ROW_1_LEFT"), item.key)
        assertEquals("open", item.meaning)
        assertEquals(VehicleOutcome.COMPLETE, results.single().outcome)
    }

    @Test fun readWriteAccessAllowsReadsButNoneAndWriteDoNot() {
        for (access in listOf("NONE", "WRITE")) {
            results.clear()
            val boundary = FakeVehicleSdk()
            VehicleStatusReader(boundary, scheduler).read(WINDOW_STATUS, complete = results::add)
            boundary.capability(allWindows.copy(access = access))
            assertEquals(0, boundary.readCalls)
            assertEquals(listOf(ItemStatus.READ_UNAVAILABLE), results.single().items.map { it.status }.distinct())
        }
        start(WINDOW_STATUS)
        sdk.capability(allWindows.copy(access = "READ_WRITE"))
        assertEquals(1, sdk.readCalls)
    }
}
