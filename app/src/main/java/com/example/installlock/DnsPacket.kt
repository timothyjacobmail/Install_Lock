package com.example.installlock

/** Validate packet lengths before copying; malformed input must not tear down the VPN reader. */
internal object DnsPacket {
    data class Request(val query: ByteArray, val clientIp: ByteArray, val clientPort: Int)

    fun request(packet: ByteArray, length: Int): Request? {
        if (length !in 40..packet.size || (packet[0].toInt() and 0xf0) != 0x40 || packet[9].toInt() != 17) return null
        val header = (packet[0].toInt() and 0x0f) * 4
        val total = u16(packet, 2)
        // Fragment reassembly is not supported by this DNS-only tunnel.
        if (header < 20 || total > length || total < header + 20 || u16(packet, 6) and 0x3fff != 0) return null
        if (u16(packet, header + 2) != 53) return null
        val udpLength = u16(packet, header + 4)
        if (udpLength < 20 || udpLength > total - header) return null
        return Request(packet.copyOfRange(header + 8, header + udpLength), packet.copyOfRange(12, 16), u16(packet, header))
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
}
