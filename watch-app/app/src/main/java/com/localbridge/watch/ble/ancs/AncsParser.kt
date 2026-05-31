package com.localbridge.watch.ble.ancs

import java.nio.charset.Charset

class AncsParser {
    private val dataSourceBuffer = ArrayList<Byte>()

    fun parseNotificationSource(bytes: ByteArray): AncsNotificationEvent? {
        if (bytes.size < 8) return null
        val uid = readUInt32Le(bytes, 4) ?: return null
        return AncsNotificationEvent(
            eventId = AncsEventId.from(bytes[0].toInt() and 0xFF),
            eventFlags = bytes[1].toInt() and 0xFF,
            categoryId = AncsCategoryId.from(bytes[2].toInt() and 0xFF),
            categoryCount = bytes[3].toInt() and 0xFF,
            uid = uid
        )
    }

    fun buildGetNotificationAttributesRequest(uid: UInt): ByteArray {
        val uidLong = uid.toLong()
        return byteArrayOf(
            AncsProfile.COMMAND_GET_NOTIFICATION_ATTRIBUTES.toByte(),
            (uidLong and 0xFF).toByte(),
            ((uidLong shr 8) and 0xFF).toByte(),
            ((uidLong shr 16) and 0xFF).toByte(),
            ((uidLong shr 24) and 0xFF).toByte(),
            AncsProfile.ATTR_APP_IDENTIFIER.toByte(),
            AncsProfile.ATTR_TITLE.toByte(),
            (AncsProfile.MAX_TITLE_LENGTH and 0xFF).toByte(),
            ((AncsProfile.MAX_TITLE_LENGTH shr 8) and 0xFF).toByte(),
            AncsProfile.ATTR_MESSAGE.toByte(),
            (AncsProfile.MAX_MESSAGE_LENGTH and 0xFF).toByte(),
            ((AncsProfile.MAX_MESSAGE_LENGTH shr 8) and 0xFF).toByte()
        )
    }

    fun parseDataSourceIncremental(bytes: ByteArray): ParsedAncsAttributes? {
        bytes.forEach(dataSourceBuffer::add)
        if (dataSourceBuffer.size < 5) return null

        val snapshot = dataSourceBuffer.toByteArray()
        if ((snapshot[0].toInt() and 0xFF) != AncsProfile.COMMAND_GET_NOTIFICATION_ATTRIBUTES) {
            dataSourceBuffer.clear()
            return ParsedAncsAttributes(error = "unexpected command id=${snapshot[0].toInt() and 0xFF}")
        }

        val uid = readUInt32Le(snapshot, 1) ?: return null
        var offset = 5
        var parsedAny = false
        var appIdentifier = ""
        var title = ""
        var subtitle = ""
        var message = ""

        while (offset < snapshot.size) {
            if (snapshot.size - offset < 3) return null
            val attributeId = snapshot[offset].toInt() and 0xFF
            val length = (snapshot[offset + 1].toInt() and 0xFF) or
                ((snapshot[offset + 2].toInt() and 0xFF) shl 8)
            offset += 3

            if (length < 0 || length > 4096) {
                dataSourceBuffer.clear()
                return ParsedAncsAttributes(error = "invalid attribute length=$length")
            }
            if (snapshot.size - offset < length) return null

            val value = safeUtf8(snapshot.copyOfRange(offset, offset + length))
            when (attributeId) {
                AncsProfile.ATTR_APP_IDENTIFIER -> appIdentifier = value
                AncsProfile.ATTR_TITLE -> title = value
                AncsProfile.ATTR_SUBTITLE -> subtitle = value
                AncsProfile.ATTR_MESSAGE -> message = value
            }
            parsedAny = true
            offset += length
        }

        if (!parsedAny) return null
        dataSourceBuffer.clear()
        return ParsedAncsAttributes(
            attributes = AncsAttributes(
                uid = uid,
                appIdentifier = appIdentifier,
                title = title,
                subtitle = subtitle,
                message = message
            )
        )
    }

    fun resetDataSourceBuffer() {
        dataSourceBuffer.clear()
    }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): UInt? {
        if (bytes.size - offset < 4) return null
        val value = (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
        return value.toUInt()
    }

    private fun safeUtf8(bytes: ByteArray): String =
        runCatching { bytes.toString(Charset.forName("UTF-8")) }.getOrDefault("")
}
