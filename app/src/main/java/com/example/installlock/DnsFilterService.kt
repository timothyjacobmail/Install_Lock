package com.example.installlock

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

class DnsFilterService : VpnService() {

    companion object {
        const val TARGET_PKG = "com.google.android.googlequicksearchbox"
        private const val CLIENT_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"
        private val DNS_BYTES = byteArrayOf(10, 111, 222.toByte(), 2)
    }

    private var tun: ParcelFileDescriptor? = null
    private val pool = Executors.newFixedThreadPool(8)
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tun == null) startFilter()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun startFilter() {
        try {
            val fd = Builder()
                .setSession("Install Lock DNS filter")
                .setMtu(1500)
                .addAddress(CLIENT_ADDR, 32)
                .addDnsServer(DNS_ADDR)
                .addRoute(DNS_ADDR, 32)               // only DNS goes through us
                .addAllowedApplication(TARGET_PKG)    // and only from the Google app
                .setBlocking(true)
                .establish() ?: return
            tun = fd
            running = true
            Thread { loop(fd) }.start()
        } catch (_: Exception) { }
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n < 28) continue
                if ((buf[0].toInt() shr 4) != 4) continue          // IPv4 only
                if (buf[9].toInt() != 17) continue                  // UDP only
                val ihl = (buf[0].toInt() and 0x0F) * 4
                if (n < ihl + 8 + 12) continue
                if (u16(buf, ihl + 2) != 53) continue               // DNS only
                val end = minOf(n, ihl + u16(buf, ihl + 4))
                val clientIp = buf.copyOfRange(12, 16)
                val clientPort = u16(buf, ihl)
                val query = buf.copyOfRange(ihl + 8, end)
                pool.execute { handle(output, query, clientIp, clientPort) }
            }
        } catch (_: Exception) { }
    }

    private fun handle(out: FileOutputStream, query: ByteArray, clientIp: ByteArray, clientPort: Int) {
        try {
            val parsed = parseQuestion(query)
            if (parsed != null && Store.isBlocked(this, parsed.first)) {
                DnsLog.add(parsed.first, true)
                reply(out, clientIp, clientPort, nxdomain(query, parsed.second))
                return
            }
            if (parsed != null) DnsLog.add(parsed.first, false)
            val answer = forward(query) ?: return
            reply(out, clientIp, clientPort, answer)
        } catch (_: Exception) { }
    }

    /** Returns (lowercase name, offset just past the question) or null if malformed. */
    private fun parseQuestion(q: ByteArray): Pair<String, Int>? {
        var i = 12
        val sb = StringBuilder()
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) {
                val end = i + 1 + 4
                return if (end <= q.size) sb.toString().lowercase() to end else null
            }
            if (len and 0xC0 != 0 || i + 1 + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, i + 1, len, Charsets.US_ASCII))
            i += len + 1
        }
        return null
    }

    /** "That name doesn't exist." */
    private fun nxdomain(query: ByteArray, qEnd: Int): ByteArray {
        val r = query.copyOfRange(0, qEnd)
        r[2] = 0x81.toByte()                 // response, recursion desired
        r[3] = 0x83.toByte()                 // recursion available, NXDOMAIN
        for (i in 6..11) r[i] = 0            // no answer/authority/additional records
        return r
    }

    /** Ask the network's own DNS server, bypassing our VPN. */
    private fun forward(query: ByteArray): ByteArray? {
        val server = upstream() ?: return null
        DatagramSocket().use { s ->
            protect(s)
            s.soTimeout = Store.timingConfig(this).milliseconds(TimingOption.DNS_TIMEOUT).toInt()
            s.connect(InetSocketAddress(server, 53))
            s.send(DatagramPacket(query, query.size))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            return buf.copyOf(p.length)
        }
    }

    private fun upstream(): InetAddress? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val servers = cm.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
            .filter { it.hostAddress != DNS_ADDR }
        return servers.firstOrNull { it is Inet4Address } ?: servers.firstOrNull()
    }

    /** Wrap a DNS answer in an IPv4+UDP packet and write it back to the app. */
    private fun reply(out: FileOutputStream, clientIp: ByteArray, clientPort: Int, dns: ByteArray) {
        val total = 20 + 8 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45                                   // IPv4, 20-byte header
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40                                   // don't fragment
        p[8] = 64                                     // TTL
        p[9] = 17                                     // UDP
        DNS_BYTES.copyInto(p, 12)                     // from: our fake DNS server
        clientIp.copyInto(p, 16)                      // to: the app
        val ck = ipChecksum(p, 20)
        p[10] = (ck shr 8).toByte(); p[11] = ck.toByte()
        p[20] = 0; p[21] = 53                         // UDP source port 53
        p[22] = (clientPort shr 8).toByte(); p[23] = clientPort.toByte()
        val udpLen = 8 + dns.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        dns.copyInto(p, 28)                           // UDP checksum left 0 (allowed on IPv4)
        synchronized(out) { out.write(p) }
    }

    private fun u16(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun ipChecksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
