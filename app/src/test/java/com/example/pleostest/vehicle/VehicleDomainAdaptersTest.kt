package com.example.pleostest.vehicle

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import org.junit.Assert.*
import org.junit.Test

/** Only the pure mapping from public SDK capability models; no Vehicle Service is involved. */
class VehicleDomainAdaptersTest {
    private val read = CapabilityConfig(AccessType.READ)

    @Test fun doorPositionCapabilityMapsOnlyExactSingleAreasWithTheirBounds() {
        val capability = doorPositionCapability(CapabilityConfig(AccessType.READ_WRITE), mapOf(
            DoorArea(DoorValue.ROW_1_LEFT) to AreaBounds(0, 1),
            DoorArea(DoorValue.ROW_1_RIGHT) to AreaBounds(0, 1),
            DoorArea(DoorValue.ROW_2_LEFT) to AreaBounds(0, 100),
            DoorArea(DoorValue.HOOD) to AreaBounds(0, 1),
        ))
        assertEquals("READ_WRITE", capability.access)
        assertEquals(AreaSupport(1, 0.0, 1.0), capability.areas.getValue("ROW_1_LEFT"))
        assertEquals(AreaSupport(1, 0.0, 1.0), capability.areas.getValue("ROW_1_RIGHT"))
        assertEquals(AreaSupport(1, 0.0, 100.0), capability.areas.getValue("ROW_2_LEFT"))
        assertEquals(AreaSupport(0, null, null), capability.areas.getValue("ROW_2_RIGHT"))
        assertEquals(DOOR_AREAS.toSet(), capability.areas.keys)
        assertTrue(capability.reportedAreas!!.contains("HOOD"))
    }

    @Test fun groupedDoorAreaNeverCountsAsASingleDoorAndDuplicatesAreAmbiguous() {
        val grouped = doorPositionCapability(read, mapOf(
            DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT) to AreaBounds(0, 1)))
        assertEquals(0, grouped.areas.getValue("ROW_1_LEFT").matchingAreas)
        assertEquals(0, grouped.areas.getValue("ROW_1_RIGHT").matchingAreas)
        assertEquals("ROW_1_LEFT+ROW_1_RIGHT", grouped.reportedAreas)

        val duplicate = doorPositionCapability(read, mapOf(
            DoorArea(DoorValue.ROW_1_LEFT) to AreaBounds(0, 1),
            DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_LEFT) to AreaBounds(0, 1)))
        assertEquals(2, duplicate.areas.getValue("ROW_1_LEFT").matchingAreas)
        assertTrue(matchesDoorArea(DoorArea(DoorValue.ROW_1_LEFT), DOOR_POSITION_TARGET))
        assertFalse(matchesDoorArea(DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT), DOOR_POSITION_TARGET))
    }

    @Test fun doorLockCapabilityUsesItsOwnAreaListWithoutBounds() {
        val capability = doorLockCapability(read, listOf(
            DoorArea(DoorValue.ROW_1_RIGHT), DoorArea(DoorValue.ROW_2_LEFT), DoorArea(DoorValue.ROW_2_LEFT)))
        assertEquals(AreaSupport(0, null, null), capability.areas.getValue("ROW_1_LEFT"))
        assertEquals(AreaSupport(1, null, null), capability.areas.getValue("ROW_1_RIGHT"))
        assertEquals(AreaSupport(2, null, null), capability.areas.getValue("ROW_2_LEFT"))
        assertEquals(DOOR_AREAS.toSet(), capability.areas.keys)
    }

    @Test fun windowCapabilityIsIndependentOfDoorsAndIgnoresNonDoorWindows() {
        val capability = windowPositionCapability(read, mapOf(
            WindowArea(WindowValue.ROW_1_LEFT) to AreaBounds(0, 100),
            WindowArea(WindowValue.ROW_2_RIGHT) to AreaBounds(5, 95),
            WindowArea(WindowValue.FRONT_WINDSHIELD) to AreaBounds(0, 1),
            WindowArea(WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT) to AreaBounds(0, 100),
        ))
        assertEquals(AreaSupport(1, 0.0, 100.0), capability.areas.getValue("ROW_1_LEFT"))
        assertEquals(AreaSupport(1, 5.0, 95.0), capability.areas.getValue("ROW_2_RIGHT"))
        assertEquals(0, capability.areas.getValue("ROW_1_RIGHT").matchingAreas)
        assertEquals(0, capability.areas.getValue("ROW_2_LEFT").matchingAreas)
        assertEquals(DOOR_AREAS.toSet(), capability.areas.keys)
        assertTrue(capability.reportedAreas!!.contains("FRONT_WINDSHIELD"))
    }

    @Test fun energyCapabilityHasNoAreaAndOnlyTheAccessType() {
        for (access in AccessType.entries) {
            val capability = energyAccess(CapabilityConfig(access))
            assertEquals(access.name, capability.access)
            assertNull(capability.reportedAreas)
            assertTrue(capability.areas.isEmpty())
        }
    }

    @Test fun targetAreasAreExactlyTheFourFixedSingleAreas() {
        assertEquals(DOOR_AREAS, TARGET_AREAS)
        assertEquals(DOOR_AREAS, DOOR_AREAS.map { doorAreaOf(it).values.single().name })
        assertEquals(DOOR_AREAS, DOOR_AREAS.map { windowAreaOf(it).values.single().name })
    }
}
