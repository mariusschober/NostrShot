package com.nostrpost.app.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOSTRSHOT-28: published notes must produce standards-compliant note/nevent links. */
class Nip19Test {

    private val eventId = "a1".repeat(32)
    private val author = "b2".repeat(32)
    private val relays = listOf("wss://relay.example.com", "wss://nos.lol")

    @Test
    fun noteEncodingRoundTrips() {
        val note = Nip19.note(eventId)
        assertTrue(note.startsWith("note1"))
        val (hrp, data) = Bech32.decode(note)
        assertEquals("note", hrp)
        assertArrayEquals(Hex.decode(eventId), data)
    }

    @Test
    fun neventContainsIdRelaysAuthorAndKind() {
        val nevent = Nip19.nevent(eventId, relays, author, 1)
        val (hrp, data) = Bech32.decode(nevent)
        assertEquals("nevent", hrp)

        val entries = readTlv(data)
        assertArrayEquals(Hex.decode(eventId), entries.single { it.first == 0 }.second)
        assertEquals(
            relays,
            entries.filter { it.first == 1 }.map { String(it.second, Charsets.UTF_8) }
        )
        assertArrayEquals(Hex.decode(author), entries.single { it.first == 2 }.second)
        val kind = entries.single { it.first == 3 }.second
        assertEquals(1, ((kind[0].toInt() and 0xFF) shl 24) or ((kind[1].toInt() and 0xFF) shl 16) or ((kind[2].toInt() and 0xFF) shl 8) or (kind[3].toInt() and 0xFF))
    }

    @Test
    fun njumpUrlUsesNevent() {
        val nevent = Nip19.nevent(eventId)
        assertEquals("https://njump.me/$nevent", Nip19.njumpUrl(nevent))
    }

    @Test
    fun neventWithoutOptionalFieldsIsStillValid() {
        val nevent = Nip19.nevent(eventId)
        val (hrp, data) = Bech32.decode(nevent)
        assertEquals("nevent", hrp)
        assertEquals(1, readTlv(data).size)
    }

    private fun readTlv(data: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 3 <= data.size) {
            val type = data[i].toInt() and 0xFF
            val len = ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            i += 3
            require(i + len <= data.size)
            out += Pair(type, data.copyOfRange(i, i + len))
            i += len
        }
        return out
    }
}
