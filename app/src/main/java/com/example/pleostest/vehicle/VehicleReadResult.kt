package com.example.pleostest.vehicle

/** The four fixed read requests. Nothing about area, API or timeout comes from the caller. */
enum class VehicleQuery(val wire: String, val keys: List<VehicleItemKey>) {
    DOOR_STATUS("door_status",
        TARGET_AREAS.map { VehicleItemKey(VehicleProperty.DOOR_POSITION, it) } +
            TARGET_AREAS.map { VehicleItemKey(VehicleProperty.DOOR_LOCK, it) }),
    WINDOW_STATUS("window_status", TARGET_AREAS.map { VehicleItemKey(VehicleProperty.WINDOW_POSITION, it) }),
    ENERGY_LEVEL("energy_level", listOf(
        VehicleItemKey(VehicleProperty.FUEL_PERCENT, null), VehicleItemKey(VehicleProperty.EV_BATTERY_PERCENT, null))),
    FRONT_LEFT_DOOR_POSITION("front_left_door_position", listOf(VehicleItemKey(VehicleProperty.DOOR_POSITION, "ROW_1_LEFT")));

    companion object {
        fun fromWire(value: String): VehicleQuery? = entries.firstOrNull { it.wire == value }
    }
}

/** Single-area targets shared by door and window items; grouped areas are never decomposed. */
internal val TARGET_AREAS = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT")

enum class VehicleProperty { DOOR_POSITION, DOOR_LOCK, WINDOW_POSITION, FUEL_PERCENT, EV_BATTERY_PERCENT }

/** [area] is null for fuel and EV: their SDK getters take no area. */
data class VehicleItemKey(val property: VehicleProperty, val area: String?)

/** What this item's capability check showed. Bounds exist only for position properties. */
data class ItemCapability(
    val access: String,
    val matchingAreas: Int?,
    val reportedAreas: String?,
    val minimum: Double?,
    val maximum: Double?,
)

enum class ItemStatus {
    /** A value was received and its meaning/unit is confirmed (positions keep raw and bounds only). */
    OK,
    NO_VALUE, UNKNOWN_VALUE,
    CAPABILITY_FAILED, READ_UNAVAILABLE, AREA_UNSUPPORTED, SUPPORT_UNCONFIRMED, READ_FAILED,
}

data class VehicleItem(
    val key: VehicleItemKey,
    val status: ItemStatus,
    val capability: ItemCapability? = null,
    /** Int for positions and percents, Boolean for door lock; null when nothing was received. */
    val rawValue: Any? = null,
    val meaning: String? = null,
    val unit: String? = null,
    // Callback receipt time, not a vehicle measurement timestamp or a snapshot shared by items.
    val receivedAtMillis: Long? = null,
    val errorType: String? = null,
)

enum class VehicleOutcome { COMPLETE, PARTIAL, FAILED, TIMEOUT, CANCELLED }

/** TIMEOUT and CANCELLED never carry items: nothing observed before them is exported as usable. */
data class VehicleReadResult(
    val query: VehicleQuery,
    val outcome: VehicleOutcome,
    val items: List<VehicleItem> = emptyList(),
) {
    init {
        require(outcome != VehicleOutcome.TIMEOUT && outcome != VehicleOutcome.CANCELLED || items.isEmpty()) {
            "an unfinished request has no items"
        }
    }
}
