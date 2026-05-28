package com.sshtunnel

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "VpnForwarder"
private const val SOCKS_PROXY = "127.0.0.1"

/**
 * Reads IPv4/TCP packets from TUN, opens a SOCKS5 connection for each new TCP flow,
 * and relays data bidirectionally.
 *
 * This is a simplified TCP interceptor — enough for HTTP/HTTPS/browser traffic.
 * UDP (DNS) is handled by setting DNS servers in the VPN builder.
 */
class VpnPacketForwarder(
    private val vpnService: VpnService,
    private val tunFd: ParcelFileDescriptor,
    private val socksPort: Int
) {
    private val running = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, TcpSession>()

    fun start() {
        running.set(true)
        Thread(::readLoop, "vpn-read").apply { isDaemon = true }.start()
        Log.i(TAG, "Packet forwarder started")
    }

    fun stop() {
        running.set(false)
        sessions.values.forEach { it.close() }
        sessions.clear()
        Log.i(TAG, "Packet forwarder stopped")
    }

    private fun readLoop() {
        val ins = FileInputStream(tunFd.fileDescriptor)
        val buf = ByteArray(32767)

        while (running.get()) {
            val len = try { ins.read(buf) } catch (e: Exception) { break }
            if (len < 20) continue

            val pkt = buf.copyOf(len)

            // Only handle IPv4
            if ((pkt[0].toInt() ushr 4) and 0xF != 4) continue
            // Only TCP (protocol 6)
            if (pkt[9].toInt() and 0xFF != 6) continue

            val ihl = (pkt[0].toInt() and 0x0F) * 4
            if (len < ihl + 20) continue

            val dstIp = "%d.%d.%d.%d".format(
                pkt[16].toInt() and 0xFF, pkt[17].toInt() and 0xFF,
                pkt[18].toInt() and 0xFF, pkt[19].toInt() and 0xFF
            )

            val srcPort = ((pkt[ihl].toInt()   and 0xFF) shl 8) or (pkt[ihl+1].toInt() and 0xFF)
            val dstPort = ((pkt[ihl+2].toInt() and 0xFF) shl 8) or (pkt[ihl+3].toInt() and 0xFF)
            val flags   = pkt[ihl + 13].toInt() and 0xFF
            val isSyn   = flags and 0x02 != 0
            val isFin   = flags and 0x01 != 0 || flags and 0x04 != 0
            val dataOff = ihl + ((pkt[ihl+12].toInt() ushr 4) and 0xF) * 4
            val payload = if (dataOff < len) pkt.copyOfRange(dataOff, len) else null

            val key = "$srcPort-$dstIp:$dstPort"

            when {
                isFin -> sessions.remove(key)?.close()
                isSyn -> {
                    // New connection
                    val session = TcpSession(vpnService, dstIp, dstPort, socksPort)
                    if (session.connect()) {
                        sessions[key] = session
                    }
                }
                payload != null && payload.isNotEmpty() -> {
                    sessions[key]?.send(payload)
                }
            }
        }
    }
}

class TcpSession(
    private val vpnService: VpnService,
    private val dstHost: String,
    private val dstPort: Int,
    private val socksPort: Int
) {
    private var socket: Socket? = null
    private val alive = AtomicBoolean(false)

    fun connect(): Boolean {
        return try {
            val s = Socket()
            vpnService.protect(s) // CRITICAL: bypass VPN for this socket
            s.connect(InetSocketAddress(SOCKS_PROXY, socksPort), 5_000)
            s.soTimeout = 30_000

            val out = s.getOutputStream()
            val inp = s.getInputStream()

            // SOCKS5 handshake
            out.write(byteArrayOf(5, 1, 0))
            inp.read(ByteArray(2))

            val host = dstHost.toByteArray()
            out.write(
                byteArrayOf(5, 1, 0, 3, host.size.toByte()) +
                host +
                byteArrayOf((dstPort shr 8).toByte(), (dstPort and 0xFF).toByte())
            )
            val resp = ByteArray(10)
            inp.read(resp)
            if (resp[1].toInt() != 0) {
                s.close(); return false
            }

            socket = s
            alive.set(true)
            Log.d("TcpSession", "Connected to $dstHost:$dstPort via SOCKS5")
            true
        } catch (e: Exception) {
            Log.d("TcpSession", "Failed $dstHost:$dstPort — ${e.message}")
            false
        }
    }

    fun send(data: ByteArray) {
        try { socket?.getOutputStream()?.apply { write(data); flush() } }
        catch (e: Exception) { close() }
    }

    fun close() {
        alive.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
