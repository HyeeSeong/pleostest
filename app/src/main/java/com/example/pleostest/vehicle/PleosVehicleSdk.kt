package com.example.pleostest.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import android.content.Context
import android.util.Log
import com.example.pleostest.vehicle.VehicleProperty.*
import java.util.concurrent.Executors

internal fun doorAreaOf(name: String) = DoorArea(DoorValue.valueOf(name))
internal fun windowAreaOf(name: String) = WindowArea(WindowValue.valueOf(name))

/** An area counts for a target only when its complete value set is exactly that one area. */
private fun support(areas: List<Pair<List<String>, AreaBounds<Int>?>>, target: String): AreaSupport {
    val matches = areas.filter { (names, _) -> names.toSet() == setOf(target) }
    val bounds = matches.singleOrNull()?.second
    return AreaSupport(matches.size, bounds?.minValue?.toDouble(), bounds?.maxValue?.toDouble())
}

private fun capability(config: CapabilityConfig, areas: List<Pair<List<String>, AreaBounds<Int>?>>) =
    PropertyCapability(config.accessType.name, areas.joinToString { (names, _) -> names.joinToString("+") },
        TARGET_AREAS.associateWith { support(areas, it) })

internal fun doorPositionCapability(config: CapabilityConfig, areas: Map<DoorArea, AreaBounds<Int>>) =
    capability(config, areas.map { (area, bounds) -> area.values.map { it.name } to bounds })

internal fun doorLockCapability(config: CapabilityConfig, areas: List<DoorArea>) =
    capability(config, areas.map { area -> area.values.map { it.name } to null })

internal fun windowPositionCapability(config: CapabilityConfig, areas: Map<WindowArea, AreaBounds<Int>>) =
    capability(config, areas.map { (area, bounds) -> area.values.map { it.name } to bounds })

/** Fuel and EV have no area: only the access type is reported. */
internal fun energyAccess(config: CapabilityConfig) = PropertyCapability(config.accessType.name, null, emptyMap())

/**
 * One Vehicle instance for every domain. SDK work stays off the UI thread; release is serialized
 * after submitted SDK calls. Only public get and capability APIs are used.
 */
class PleosVehicleSdk(context: Context) : VehicleSdk {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private var vehicle: Vehicle? = null
    private var initializationAttempted = false
    private var initializationFailure: Exception? = null
    @Volatile private var closed = false

    override fun checkCapability(
        property: VehicleProperty, active: () -> Boolean,
        complete: (PropertyCapability) -> Unit, failed: (Exception) -> Unit,
    ) = submit(active, failed) { vehicle ->
        when (property) {
            DOOR_POSITION -> vehicle.getDoor().checkDoorPositionCapabilities(
                { config, areas -> complete(doorPositionCapability(config, areas)) }, failed)
            DOOR_LOCK -> vehicle.getDoor().checkDoorLockCapabilities(
                { config, areas -> complete(doorLockCapability(config, areas)) }, failed)
            WINDOW_POSITION -> vehicle.getWindow().checkWindowPositionCapabilities(
                { config, areas -> complete(windowPositionCapability(config, areas)) }, failed)
            FUEL_PERCENT -> vehicle.getFuel().checkFuelPercentCapabilities(
                { config -> complete(energyAccess(config)) }, failed)
            EV_BATTERY_PERCENT -> vehicle.getEvBattery().checkEvBatteryStateOfChargeCapabilities(
                { config -> complete(energyAccess(config)) }, failed)
        }
    }

    override fun read(
        key: VehicleItemKey, active: () -> Boolean, complete: (Any?, Long) -> Unit, failed: (Exception) -> Unit,
    ) = submit(active, failed) { vehicle ->
        val deliver: (Any?) -> Unit = { value -> complete(value, System.currentTimeMillis()) }
        when (key.property) {
            DOOR_POSITION -> vehicle.getDoor().getCurrentDoorPosition(doorAreaOf(key.area!!), deliver, failed)
            DOOR_LOCK -> vehicle.getDoor().getDoorLockState(doorAreaOf(key.area!!), deliver, failed)
            WINDOW_POSITION -> vehicle.getWindow().getCurrentWindowPosition(windowAreaOf(key.area!!), deliver, failed)
            FUEL_PERCENT -> vehicle.getFuel().getFuelPercent(deliver, failed)
            EV_BATTERY_PERCENT -> vehicle.getEvBattery().getEvBatteryStateOfCharge(deliver, failed)
        }
    }

    private fun submit(active: () -> Boolean, failed: (Exception) -> Unit, call: (Vehicle) -> Unit) {
        worker.execute {
            if (closed || !active()) return@execute
            try {
                val connected = connect()
                if (closed || !active()) return@execute
                call(connected)
            } catch (error: Exception) { failed(error) }
        }
    }

    private fun connect(): Vehicle {
        if (!initializationAttempted) {
            initializationAttempted = true
            try {
                vehicle = Vehicle(context)
                vehicle!!.initialize()
            } catch (error: Exception) {
                initializationFailure = error
            }
        }
        initializationFailure?.let { throw it }
        return vehicle!!
    }

    override fun close() {
        if (closed) return
        closed = true
        worker.execute {
            try { vehicle?.release() }
            catch (error: Exception) {
                Log.w("PleosVehicleSdk", "release failed: ${error.javaClass.simpleName}")
            }
            finally { vehicle = null }
        }
        worker.shutdown()
    }
}
