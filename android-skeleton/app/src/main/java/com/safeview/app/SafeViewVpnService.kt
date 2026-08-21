package com.safeview.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * DNS-only background protection. It blocks configured adult domains without
 * decrypting HTTPS or inspecting image pixels from other applications.
 */
class SafeViewVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            startForeground(NOTIFICATION_ID, notification())
            establish()
        }
        return START_STICKY
    }

    private fun establish() {
        val builder = Builder()
            .setSession("SafeView background protection")
            .setMtu(1500)
            .addAddress("10.77.0.2", 32)
            .addDnsServer(DNS_VPN)
            .addRoute(DNS_VPN, 32)
        vpnInterface = builder.establish() ?: return
        running = true
        executor.execute { loop() }
    }

    private fun loop() {
        val fd = vpnInterface ?: return
        FileInputStream(fd.fileDescriptor).use { input ->
            FileOutputStream(fd.fileDescriptor).use { output ->
                val packet = ByteArray(32767)
                while (running) {
                    val length = input.read(packet)
                    if (length > 0) handlePacket(packet, length, output)
                }
            }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 28) return
        val version = (packet[0].toInt() ushr 4) and 0x0f
        if (version != 4) return
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || length < ihl + 8) return
        val protocol = packet[9].toInt() and 0xff
        if (protocol != 17) return // UDP only
        val sourcePort = u16(packet, ihl)
        val destinationPort = u16(packet, ihl + 2)
        if (destinationPort != 53) return
        val dnsOffset = ihl + 8
        val dnsLength = length - dnsOffset
        if (dnsLength <= 12 || dnsLength > MAX_DNS_PACKET) return
        val dns = packet.copyOfRange(dnsOffset, length)
        val domain = parseQuestionName(dns) ?: return
        val responseDns = if (isBlocked(domain)) blockedResponse(dns) else forwardDns(dns)
        if (responseDns == null) return
        val response = buildIpv4UdpResponse(packet, ihl, sourcePort, destinationPort, responseDns)
        output.write(response)
    }

    private fun forwardDns(query: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 2500
                val server = InetAddress.getByName(UPSTREAM_DNS)
                socket.send(DatagramPacket(query, query.size, server, 53))
                val buffer = ByteArray(MAX_DNS_PACKET)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                buffer.copyOf(response.length)
            }
        } catch (_: Exception) { null }
    }

    private fun parseQuestionName(dns: ByteArray): String? {
        if (dns.size < 17) return null
        var index = 12
        val labels = ArrayList<String>()
        while (index < dns.size) {
            val count = dns[index].toInt() and 0xff
            index++
            if (count == 0) break
            if (count > 63 || index + count > dns.size) return null
            labels += String(dns, index, count, Charsets.US_ASCII).lowercase()
            index += count
        }
        return labels.joinToString(".").takeIf { it.isNotEmpty() }
    }

    private fun isBlocked(domain: String): Boolean {
        val normalized = domain.trimEnd('.').lowercase()
        val blocked = SettingsPrefs(this).blockedDomains
        return blocked.any { normalized == it || normalized.endsWith(".$it") }
    }

    private fun blockedResponse(query: ByteArray): ByteArray {
        val response = query.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte() // response
        response[3] = ((response[3].toInt() or 0x03) and 0xff).toByte() // NXDOMAIN
        response[4] = 0
        response[5] = 0
        response[6] = 0
        response[7] = 0
        return response
    }

    private fun buildIpv4UdpResponse(
        request: ByteArray, ihl: Int, sourcePort: Int, destinationPort: Int, dns: ByteArray
    ): ByteArray {
        val total = ihl + 8 + dns.size
        val response = ByteArray(total)
        request.copyInto(response, 0, 0, ihl)
        request.copyInto(response, 12, 16, 20) // source becomes destination
        request.copyInto(response, 16, 12, 16) // destination becomes source
        response[2] = (total ushr 8).toByte()
        response[3] = total.toByte()
        response[8] = 64
        response[10] = 0
        response[11] = 0
        val ipChecksum = checksum(response, 0, ihl)
        response[10] = (ipChecksum ushr 8).toByte()
        response[11] = ipChecksum.toByte()
        val udp = ihl
        response[udp] = (destinationPort ushr 8).toByte()
        response[udp + 1] = destinationPort.toByte()
        response[udp + 2] = (sourcePort ushr 8).toByte()
        response[udp + 3] = sourcePort.toByte()
        val udpLength = 8 + dns.size
        response[udp + 4] = (udpLength ushr 8).toByte()
        response[udp + 5] = udpLength.toByte()
        response[udp + 6] = 0
        response[udp + 7] = 0
        dns.copyInto(response, udp + 8)
        val udpChecksum = udpChecksum(response, ihl, udpLength)
        response[udp + 6] = (udpChecksum ushr 8).toByte()
        response[udp + 7] = udpChecksum.toByte()
        return response
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += u16(bytes, i)
            i += 2
        }
        if ((length and 1) != 0) sum += (bytes[offset + length - 1].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun udpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        for (i in 12 until 20 step 2) sum += u16(packet, i)
        sum += 17
        sum += udpLength
        var i = udpOffset
        while (i < udpOffset + udpLength - 1) {
            sum += u16(packet, i)
            i += 2
        }
        if ((udpLength and 1) != 0) sum += (packet[udpOffset + udpLength - 1].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val result = sum.inv().toInt() and 0xffff
        return if (result == 0) 0xffff else result
    }

    private fun u16(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        running = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channelId = "safeview_protection"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "SafeView protection", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("SafeView protection active")
                .setContentText("Adult domains are being blocked")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("SafeView protection active")
                .setContentText("Adult domains are being blocked")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        private const val DNS_VPN = "10.77.0.1"
        private const val UPSTREAM_DNS = "1.1.1.1"
        private const val MAX_DNS_PACKET = 4096
        private const val NOTIFICATION_ID = 7101
    }
}
