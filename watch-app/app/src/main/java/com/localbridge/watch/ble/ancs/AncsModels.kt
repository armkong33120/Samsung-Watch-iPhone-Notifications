package com.localbridge.watch.ble.ancs

enum class AncsEventId(val rawValue: Int, val logName: String) {
    Added(AncsProfile.EVENT_ADDED, "added"),
    Modified(AncsProfile.EVENT_MODIFIED, "modified"),
    Removed(AncsProfile.EVENT_REMOVED, "removed"),
    Unknown(-1, "unknown");

    companion object {
        fun from(rawValue: Int): AncsEventId = entries.firstOrNull { it.rawValue == rawValue } ?: Unknown
    }
}

enum class AncsCategoryId(val rawValue: Int, val displayName: String) {
    Other(0, "Other"),
    IncomingCall(1, "Incoming call"),
    MissedCall(2, "Missed call"),
    Voicemail(3, "Voicemail"),
    Social(4, "Social"),
    Schedule(5, "Schedule"),
    Email(6, "Email"),
    News(7, "News"),
    HealthAndFitness(8, "Health and fitness"),
    BusinessAndFinance(9, "Business and finance"),
    Location(10, "Location"),
    Entertainment(11, "Entertainment"),
    Unknown(-1, "Unknown");

    companion object {
        fun from(rawValue: Int): AncsCategoryId = entries.firstOrNull { it.rawValue == rawValue } ?: Unknown
    }
}

enum class AncsStatus {
    Idle,
    WaitingForBond,
    ConnectingGatt,
    DiscoveringServices,
    SubscribingNotificationSource,
    SubscribingDataSource,
    Ready,
    Failed,
    Closed
}

enum class AncsFailureReason {
    PermissionMissing,
    BondFailed,
    ConnectFailed,
    ServiceUnavailable,
    CharacteristicMissing,
    OperationTimeout,
    AuthFailed,
    ParseError,
    Closed
}

data class AncsNotificationEvent(
    val eventId: AncsEventId,
    val eventFlags: Int,
    val categoryId: AncsCategoryId,
    val categoryCount: Int,
    val uid: UInt
)

data class AncsAttributes(
    val uid: UInt,
    val appIdentifier: String = "",
    val title: String = "",
    val subtitle: String = "",
    val message: String = ""
)

data class ParsedAncsAttributes(
    val attributes: AncsAttributes? = null,
    val error: String? = null
)
