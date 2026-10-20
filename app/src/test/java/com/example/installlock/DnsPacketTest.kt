package com.example.installlock

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class DnsPacketTest {
    private fun put16(p: ByteArray, offset: Int, value: Int) {
        p[offset] = (value shr 8).toByte(); p[offset + 1] = value.toByte()
    }
    private fun packet(header: Int = 20): ByteArray = ByteArray(header + 8 + 20).also {
        it[0] = (0x40 + header / 4).toByte(); it[9] = 17
        put16(it, 2, it.size); put16(it, 6, 0x4000)
        byteArrayOf(10, 1, 2, 3).copyInto(it, 12)
        put16(it, header, 54321); put16(it, header + 2, 53); put16(it, header + 4, 28)
        for (i in 0..19) it[header + 8 + i] = i.toByte()
    }

    @Test fun extractsDnsAndClientAddressWithAndWithoutIpv4Options() {
        for (header in listOf(20, 24, 60)) {
            val p = packet(header)
            val request = DnsPacket.request(p, p.size)!!
            assertEquals(54321, request.clientPort)
            assertArrayEquals(byteArrayOf(10, 1, 2, 3), request.clientIp)
            assertArrayEquals(ByteArray(20) { it.toByte() }, request.query)
        }
    }

    @Test fun packetPaddingAndReusedReadBufferCannotLeakIntoTheDnsQuery() {
        val p = packet()
        val buffer = p.copyOf(32767)
        buffer.fill(123, p.size)
        assertEquals(20, DnsPacket.request(buffer, p.size)!!.query.size)
        assertEquals(20, DnsPacket.request(buffer, p.size + 10)!!.query.size)
    }

    @Test fun invalidLengthsFragmentsAndNonDnsTrafficAreRejected() {
        val p = packet()
        for (n in listOf(-1, 0, 19, 39, p.size - 1, p.size + 1)) assertNull(DnsPacket.request(p, n))
        val broken = listOf<(ByteArray) -> Unit>(
            { it[0] = 0x41 }, { it[0] = 0x65 }, { it[9] = 6 },
            { put16(it, 2, 39) }, { put16(it, 2, 65535) },
            { put16(it, 6, 0x2000) }, { put16(it, 6, 1) },
            { put16(it, 22, 443) }, { put16(it, 24, 0) },
            { put16(it, 24, 19) }, { put16(it, 24, 65535) })
        broken.forEach { change -> val copy = p.copyOf(); change(copy); assertNull(DnsPacket.request(copy, copy.size)) }
    }

    @Test fun arbitraryMalformedPacketsNeverThrowAndStopTheReader() {
        val random = Random(144)
        repeat(10_000) {
            val p = random.nextBytes(random.nextInt(0, 200))
            DnsPacket.request(p, random.nextInt(-1, p.size + 2))
        }
    }
}
