package com.localbridge.watch.ble.ancs

import java.util.UUID

object AncsProfile {
    val SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val GENERIC_ATTRIBUTE_SERVICE_UUID: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    val SERVICE_CHANGED_UUID: UUID = UUID.fromString("00002A05-0000-1000-8000-00805f9b34fb")

    const val COMMAND_GET_NOTIFICATION_ATTRIBUTES: Int = 0x00

    const val ATTR_APP_IDENTIFIER: Int = 0
    const val ATTR_TITLE: Int = 1
    const val ATTR_SUBTITLE: Int = 2
    const val ATTR_MESSAGE: Int = 3

    const val EVENT_ADDED: Int = 0
    const val EVENT_MODIFIED: Int = 1
    const val EVENT_REMOVED: Int = 2

    const val MAX_TITLE_LENGTH: Int = 255
    const val MAX_MESSAGE_LENGTH: Int = 255

    const val CONNECT_TIMEOUT_MS: Long = 15_000L
    const val BOND_TIMEOUT_MS: Long = 30_000L
    const val DISCOVERY_TIMEOUT_MS: Long = 10_000L
    const val CCCD_WRITE_TIMEOUT_MS: Long = 5_000L
    const val CONTROL_POINT_WRITE_TIMEOUT_MS: Long = 5_000L

    val DISCOVERY_RETRY_DELAYS_MS: LongArray = longArrayOf(2_000L, 5_000L)
}
