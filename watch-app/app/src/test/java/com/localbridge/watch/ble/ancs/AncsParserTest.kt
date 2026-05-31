package com.localbridge.watch.ble.ancs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AncsParserTest {
    private val parser = AncsParser()

    @Test
    fun parsesNotificationSourceWithLittleEndianUid() {
        val event = parser.parseNotificationSource(
            byteArrayOf(
                0x00,
                0x01,
                0x06,
                0x02,
                0x78,
                0x56,
                0x34,
                0x12
            )
        )

        assertNotNull(event)
        assertEquals(AncsEventId.Added, event?.eventId)
        assertEquals(AncsCategoryId.Email, event?.categoryId)
        assertEquals(0x12345678u, event?.uid)
    }

    @Test
    fun buildsGetNotificationAttributesRequestWithExactLayout() {
        val request = parser.buildGetNotificationAttributesRequest(0x12345678u)

        assertArrayEquals(
            byteArrayOf(
                0x00,
                0x78,
                0x56,
                0x34,
                0x12,
                0x00,
                0x01,
                0xFF.toByte(),
                0x00,
                0x03,
                0xFF.toByte(),
                0x00
            ),
            request
        )
    }

    @Test
    fun parsesFullDataSourceResponse() {
        val result = parser.parseDataSourceIncremental(
            response(
                uid = 0x01020304u,
                0 to "com.apple.MobileSMS",
                1 to "Alice",
                3 to "Hello"
            )
        )

        assertEquals(null, result?.error)
        assertEquals(0x01020304u, result?.attributes?.uid)
        assertEquals("com.apple.MobileSMS", result?.attributes?.appIdentifier)
        assertEquals("Alice", result?.attributes?.title)
        assertEquals("Hello", result?.attributes?.message)
    }

    @Test
    fun parsesDataSourceFragmentedAcrossChunks() {
        val full = response(
            uid = 0x0A0B0C0Du,
            0 to "com.example",
            1 to "Title",
            3 to "Message"
        )

        assertNull(parser.parseDataSourceIncremental(full.copyOfRange(0, 8)))
        assertNull(parser.parseDataSourceIncremental(full.copyOfRange(8, 17)))
        val result = parser.parseDataSourceIncremental(full.copyOfRange(17, full.size))

        assertEquals("Title", result?.attributes?.title)
        assertEquals("Message", result?.attributes?.message)
    }

    @Test
    fun skipsUnknownAttributes() {
        val result = parser.parseDataSourceIncremental(
            response(
                uid = 0x00000001u,
                9 to "ignored",
                1 to "Known title"
            )
        )

        assertEquals("Known title", result?.attributes?.title)
        assertEquals("", result?.attributes?.message)
    }

    @Test
    fun incompleteAttributeDoesNotCrash() {
        val partial = byteArrayOf(
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            0x05,
            0x00,
            'H'.code.toByte()
        )

        assertNull(parser.parseDataSourceIncremental(partial))
    }

    @Test
    fun malformedResponseResetsSafely() {
        val malformed = parser.parseDataSourceIncremental(byteArrayOf(0x7F, 0, 0, 0, 0))

        assertEquals("unexpected command id=127", malformed?.error)

        val valid = parser.parseDataSourceIncremental(response(uid = 1u, 1 to "Recovered"))
        assertEquals("Recovered", valid?.attributes?.title)
    }

    private fun response(uid: UInt, vararg attributes: Pair<Int, String>): ByteArray {
        val bytes = ArrayList<Byte>()
        val uidLong = uid.toLong()
        bytes.add(0x00)
        bytes.add((uidLong and 0xFF).toByte())
        bytes.add(((uidLong shr 8) and 0xFF).toByte())
        bytes.add(((uidLong shr 16) and 0xFF).toByte())
        bytes.add(((uidLong shr 24) and 0xFF).toByte())
        attributes.forEach { (id, value) ->
            val data = value.toByteArray(Charsets.UTF_8)
            bytes.add(id.toByte())
            bytes.add((data.size and 0xFF).toByte())
            bytes.add(((data.size shr 8) and 0xFF).toByte())
            data.forEach(bytes::add)
        }
        return bytes.toByteArray()
    }
}
