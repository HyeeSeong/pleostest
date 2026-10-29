package com.example.pleostest.vehicle

import com.example.pleostest.vehicle.VehicleProperty.*

/** One exact single-area match count and its bounds, as a domain adapter reports them. */
data class AreaSupport(val matchingAreas: Int, val minimum: Double?, val maximum: Double?)

/**
 * Result of one property capability check. [areas] holds the fixed single-area targets; it is empty
 * for properties without an area (fuel, EV).
 */
data class PropertyCapability(val access: String, val reportedAreas: String?, val areas: Map<String, AreaSupport>)

/**
 * Only the SDK boundary is replaced by unit tests. No polling or subscription.
 * [active] may be checked from any thread; queued work skips the SDK call once it is false.
 */
interface VehicleSdk {
    fun checkCapability(property: VehicleProperty, active: () -> Boolean,
                        complete: (PropertyCapability) -> Unit, failed: (Exception) -> Unit)
    fun read(key: VehicleItemKey, active: () -> Boolean,
             complete: (Any?, Long) -> Unit, failed: (Exception) -> Unit)
    fun close()
}

/**
 * The one logical read slot shared by the screen and every bridge request. read/cancel/close and
 * scheduled state transitions run on the same serial dispatcher. Each property is checked once, then
 * its supported items are read one at a time; the whole request has a single deadline.
 */
class VehicleStatusReader(private val sdk: VehicleSdk, private val scheduler: DoorScheduler) {
    private inner class Request(
        val query: VehicleQuery, val deadline: Long, val complete: (VehicleReadResult) -> Unit,
    ) {
        val ticket = DoorRequestTicket()
        val properties = ArrayDeque(query.keys.map { it.property }.distinct())
        val reads = ArrayDeque<VehicleItemKey>()
        val capabilities = mutableMapOf<VehicleItemKey, ItemCapability>()
        val items = mutableMapOf<VehicleItemKey, VehicleItem>()
        /** Identity of the one SDK callback still awaited; consumed by its first delivery. */
        var stage: Any? = null
        var cancelTimer: (() -> Unit)? = null
        @Volatile var terminal = false
        val wanted: () -> Boolean = { !terminal && scheduler.nowMillis() < deadline }
    }

    private var pending: Request? = null
    private var closed = false

    /** [budgetMillis] is what remains of the caller's total deadline; the UI uses the full 10 s. */
    fun read(query: VehicleQuery, budgetMillis: Long = TIMEOUT_MILLIS,
             complete: (VehicleReadResult) -> Unit): DoorAdmission {
        require(budgetMillis in 1..TIMEOUT_MILLIS) { "budget out of range" }
        if (closed) return DoorAdmission.Unavailable
        if (pending != null) return DoorAdmission.Busy
        val request = Request(query, scheduler.nowMillis() + budgetMillis, complete)
        pending = request
        request.cancelTimer = scheduler.after(budgetMillis) { finish(request, VehicleOutcome.TIMEOUT) }
        next(request)
        return DoorAdmission.Accepted(request.ticket)
    }

    /** Ends only the request this ticket admitted; stale tickets are ignored. */
    fun cancel(ticket: DoorRequestTicket): Boolean {
        val request = pending?.takeIf { it.ticket === ticket } ?: return false
        finish(request, VehicleOutcome.CANCELLED)
        return true
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            pending?.let { finish(it, VehicleOutcome.CANCELLED) }
        } finally {
            sdk.close()
        }
    }

    private fun active(request: Request): Boolean {
        if (pending !== request) return false
        if (scheduler.nowMillis() >= request.deadline) {
            finish(request, VehicleOutcome.TIMEOUT)
            return false
        }
        return true
    }

    /** True only for the first delivery of the awaited callback while the request is still live. */
    private fun consume(request: Request, token: Any): Boolean {
        if (request.stage !== token) return false
        request.stage = null
        return active(request)
    }

    private fun next(request: Request) {
        if (!active(request)) return
        val key = request.reads.removeFirstOrNull()
        if (key != null) {
            readItem(request, key)
            return
        }
        val property = request.properties.removeFirstOrNull()
        if (property == null) {
            val items = request.query.keys.map { request.items.getValue(it) }
            val successes = items.count { it.status == ItemStatus.OK }
            finish(request, when (successes) {
                items.size -> VehicleOutcome.COMPLETE
                0 -> VehicleOutcome.FAILED
                else -> VehicleOutcome.PARTIAL
            }, items)
            return
        }
        checkCapability(request, property)
    }

    private fun checkCapability(request: Request, property: VehicleProperty) {
        val token = Any()
        request.stage = token
        fun failed(error: Exception) {
            for (key in request.query.keys.filter { it.property == property }) {
                request.items[key] = VehicleItem(key, ItemStatus.CAPABILITY_FAILED, errorType = error.javaClass.simpleName)
            }
            next(request)
        }
        try {
            sdk.checkCapability(property, request.wanted,
                { capability -> scheduler.execute { if (consume(request, token)) onCapability(request, property, capability) } },
                { error -> scheduler.execute { if (consume(request, token)) failed(error) } })
        } catch (error: Exception) {
            if (consume(request, token)) failed(error)
        }
    }

    private fun onCapability(request: Request, property: VehicleProperty, capability: PropertyCapability) {
        for (key in request.query.keys.filter { it.property == property }) {
            val support = key.area?.let { capability.areas[it] ?: AreaSupport(0, null, null) }
            val itemCapability = ItemCapability(capability.access, support?.matchingAreas,
                capability.reportedAreas, support?.minimum, support?.maximum)
            val status = when {
                capability.access !in READABLE -> ItemStatus.READ_UNAVAILABLE
                support == null -> null
                support.matchingAreas == 0 -> ItemStatus.AREA_UNSUPPORTED
                support.matchingAreas != 1 -> ItemStatus.SUPPORT_UNCONFIRMED
                property == DOOR_LOCK -> null
                !hasValidBounds(support) -> ItemStatus.SUPPORT_UNCONFIRMED
                else -> null
            }
            if (status != null) {
                request.items[key] = VehicleItem(key, status, itemCapability)
            } else {
                request.capabilities[key] = itemCapability
                request.reads.add(key)
            }
        }
        next(request)
    }

    private fun hasValidBounds(support: AreaSupport): Boolean {
        val min = support.minimum
        val max = support.maximum
        return min != null && max != null && min.isFinite() && max.isFinite() && min <= max
    }

    private fun readItem(request: Request, key: VehicleItemKey) {
        val token = Any()
        request.stage = token
        val capability = request.capabilities.getValue(key)
        fun failed(error: Exception) {
            request.items[key] = VehicleItem(key, ItemStatus.READ_FAILED, capability, errorType = error.javaClass.simpleName)
            next(request)
        }
        try {
            sdk.read(key, request.wanted,
                { value, receivedAt -> scheduler.execute {
                    if (consume(request, token)) {
                        request.items[key] = interpret(key, capability, value, receivedAt)
                        next(request)
                    }
                } },
                { error -> scheduler.execute { if (consume(request, token)) failed(error) } })
        } catch (error: Exception) {
            if (consume(request, token)) failed(error)
        }
    }

    private fun interpret(key: VehicleItemKey, capability: ItemCapability, value: Any?, receivedAt: Long): VehicleItem {
        fun item(status: ItemStatus, meaning: String? = null, unit: String? = null) =
            VehicleItem(key, status, capability, value, meaning, unit, receivedAt)
        val unknown = item(ItemStatus.UNKNOWN_VALUE)
        return when {
            value == null -> item(ItemStatus.NO_VALUE)
            // Open/closed is only confirmed for the documented 0..1 range; window raws never become that.
            key.property == DOOR_POSITION -> when {
                capability.minimum != 0.0 || capability.maximum != 1.0 -> unknown
                value == 0 -> item(ItemStatus.OK, "closed")
                value == 1 -> item(ItemStatus.OK, "open")
                else -> unknown
            }
            key.property == DOOR_LOCK -> if (value is Boolean) item(ItemStatus.OK, if (value) "locked" else "unlocked") else unknown
            key.property == WINDOW_POSITION -> if (value is Int) item(ItemStatus.OK) else unknown
            else -> if (value is Int && value in 0..100) item(ItemStatus.OK, unit = "%") else unknown
        }
    }

    private fun finish(request: Request, outcome: VehicleOutcome, items: List<VehicleItem> = emptyList()) {
        if (pending !== request) return
        pending = null
        request.terminal = true
        request.cancelTimer?.invoke()
        request.complete(VehicleReadResult(request.query, outcome, items))
    }

    companion object {
        const val TIMEOUT_MILLIS = 10_000L
        private val READABLE = setOf("READ", "READ_WRITE")
    }
}
