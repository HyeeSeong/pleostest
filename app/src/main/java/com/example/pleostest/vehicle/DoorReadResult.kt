package com.example.pleostest.vehicle

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue

internal val DOOR_POSITION_TARGET = DoorArea(DoorValue.ROW_1_LEFT)

data class DoorCapability(
    val access: String,
    val matchingAreas: Int,
    val minimum: Double?,
    val maximum: Double?,
    val reportedAreas: String,
)

enum class DoorReadStatus {
    CLOSED, OPEN, NO_VALUE, UNKNOWN_VALUE,
    CAPABILITY_FAILED, READ_UNAVAILABLE, AREA_UNSUPPORTED, SUPPORT_UNCONFIRMED,
    READ_FAILED, TIMEOUT, CANCELLED,
}

data class DoorReadResult(
    val status: DoorReadStatus,
    val capability: DoorCapability? = null,
    val rawValue: Int? = null,
    // Callback receipt time, not a vehicle measurement timestamp or freshness guarantee.
    val receivedAtMillis: Long? = null,
    val errorType: String? = null,
) {
    val area: String get() = DOOR_POSITION_TARGET.values.joinToString("+") { it.name }
}

/** Presents the one front-left item of a shared-reader result in the screen's original shape. */
internal fun VehicleReadResult.toDoorReadResult(): DoorReadResult {
    when (outcome) {
        VehicleOutcome.TIMEOUT -> return DoorReadResult(DoorReadStatus.TIMEOUT)
        VehicleOutcome.CANCELLED -> return DoorReadResult(DoorReadStatus.CANCELLED)
        else -> Unit
    }
    val item = items.single()
    val capability = item.capability?.let {
        DoorCapability(it.access, it.matchingAreas ?: 0, it.minimum, it.maximum, it.reportedAreas.orEmpty())
    }
    val status = when (item.status) {
        ItemStatus.OK -> if (item.meaning == "open") DoorReadStatus.OPEN else DoorReadStatus.CLOSED
        else -> DoorReadStatus.valueOf(item.status.name)
    }
    return DoorReadResult(status, capability, item.rawValue as Int?, item.receivedAtMillis, item.errorType)
}
