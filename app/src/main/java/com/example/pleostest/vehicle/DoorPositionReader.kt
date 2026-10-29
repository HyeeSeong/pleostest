package com.example.pleostest.vehicle

import ai.pleos.playground.vehicle.api.model.area.DoorArea

/** Compare the complete area, not membership in a multi-door group. */
internal fun matchesDoorArea(area: DoorArea, target: DoorArea): Boolean =
    area.values.toSet() == target.values.toSet()

interface DoorScheduler {
    fun nowMillis(): Long
    fun execute(action: () -> Unit)
    fun after(delayMillis: Long, action: () -> Unit): () -> Unit
}

/** Identity of one admitted request; only its owner can cancel it. */
class DoorRequestTicket internal constructor()

sealed interface DoorAdmission {
    class Accepted(val ticket: DoorRequestTicket) : DoorAdmission
    /** Another request owns the single slot; it keeps running and nothing is queued. */
    data object Busy : DoorAdmission
    /** The reader was closed with its screen session. */
    data object Unavailable : DoorAdmission
}

/**
 * The screen's front-left door view of the shared [VehicleStatusReader]: same slot, same SDK owner,
 * and the same deadline/cancel contract as before the other domains existed.
 */
class DoorPositionReader(private val reader: VehicleStatusReader) {
    /** [budgetMillis] is what remains of the caller's total deadline; the UI uses the full 10 s. */
    fun read(budgetMillis: Long = TIMEOUT_MILLIS, complete: (DoorReadResult) -> Unit): DoorAdmission =
        reader.read(VehicleQuery.FRONT_LEFT_DOOR_POSITION, budgetMillis) { complete(it.toDoorReadResult()) }

    fun cancel(ticket: DoorRequestTicket): Boolean = reader.cancel(ticket)

    fun close() = reader.close()

    companion object { const val TIMEOUT_MILLIS = VehicleStatusReader.TIMEOUT_MILLIS }
}
