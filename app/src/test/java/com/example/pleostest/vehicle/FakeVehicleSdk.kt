package com.example.pleostest.vehicle

/** Test double for the SDK boundary: records submissions and lets a test deliver callbacks by hand. */
class FakeVehicleSdk : VehicleSdk {
    val capabilityLog = mutableListOf<VehicleProperty>()
    val readLog = mutableListOf<VehicleItemKey>()
    val capabilityCalls get() = capabilityLog.size
    val readCalls get() = readLog.size
    var closeCalls = 0
    var throwCapability = false
    var throwRead = false
    lateinit var active: () -> Boolean
    lateinit var capability: (PropertyCapability) -> Unit
    lateinit var capabilityError: (Exception) -> Unit
    lateinit var value: (Any?, Long) -> Unit
    lateinit var readError: (Exception) -> Unit

    override fun checkCapability(
        property: VehicleProperty, active: () -> Boolean,
        complete: (PropertyCapability) -> Unit, failed: (Exception) -> Unit,
    ) {
        capabilityLog.add(property)
        this.active = active
        if (throwCapability) throw IllegalStateException()
        capability = complete
        capabilityError = failed
    }

    override fun read(
        key: VehicleItemKey, active: () -> Boolean, complete: (Any?, Long) -> Unit, failed: (Exception) -> Unit,
    ) {
        readLog.add(key)
        this.active = active
        if (throwRead) throw IllegalStateException()
        value = complete
        readError = failed
    }

    override fun close() { closeCalls++ }
}

class FakeScheduler : DoorScheduler {
    private data class Timer(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
    @Volatile var now = 0L
    private val timers = mutableListOf<Timer>()
    override fun nowMillis() = now
    override fun execute(action: () -> Unit) = action()
    override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
        val timer = Timer(now + delayMillis, action)
        timers.add(timer)
        return { timer.cancelled = true }
    }
    fun advance(millis: Long) {
        now += millis
        timers.filter { !it.cancelled && it.at <= now }.forEach {
            it.cancelled = true
            it.action()
        }
    }
}

val DOOR_AREAS = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT")

/** One exact single-area entry per name, as the SDK adapter would report it. */
fun areaCapability(
    vararg areas: String, access: String = "READ", minimum: Double? = 0.0, maximum: Double? = 1.0,
) = PropertyCapability(access, areas.joinToString(", "),
    areas.associateWith { AreaSupport(1, minimum, maximum) })

fun energyCapability(access: String = "READ") = PropertyCapability(access, null, emptyMap())
