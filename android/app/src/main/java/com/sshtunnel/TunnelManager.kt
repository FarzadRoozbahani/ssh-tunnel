package com.sshtunnel

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TunnelManager"

sealed class ConnectResult {
    data class Success(val message: String) : ConnectResult()
    data class Failure(val error: String)   : ConnectResult()
}

object TunnelManager {

    private var session: Session? = null
    private var socksServer: SocksServer? = null
    val isConnected: Boolean get() = session?.isConnected == true

    // ── Connect ──────────────────────────────────────────────
    suspend fun connect(cfg: TunnelConfig): ConnectResult = withContext(Dispatchers.IO) {
        try {
            disconnect()

            val jsch = JSch()
            if (cfg.useKey && cfg.privateKey.isNotBlank()) {
                jsch.addIdentity("key", cfg.privateKey.toByteArray(), null, null)
            }

            val s = jsch.getSession(cfg.username, cfg.host, cfg.port)
            if (!cfg.useKey) s.setPassword(cfg.password)

            val props = Properties()
            props["StrictHostKeyChecking"] = "no"  // TOFU — user can verify fingerprint manually
            props["ServerAliveInterval"]   = "30"
            props["ServerAliveCountMax"]   = "3"
            s.setConfig(props)
            s.timeout = 15_000
            s.connect()

            // Verify direct-tcpip forwarding works
            try {
                val ch = s.openChannel("direct-tcpip")
                    .also { (it as com.jcraft.jsch.ChannelDirectTCPIP).apply {
                        setHost("1.1.1.1"); setPort(80)
                        setOrgIPAddress("127.0.0.1"); setOrgPort(0)
                    }}
                ch.connect(5_000)
                ch.disconnect()
            } catch (e: Exception) {
                s.disconnect()
                return@withContext ConnectResult.Failure(
                    "TCP forwarding blocked by server.\nCheck AllowTcpForwarding in sshd_config."
                )
            }

            session = s

            // Start local SOCKS5 server
            val socks = SocksServer(s, cfg.socksPort)
            socks.start()
            socksServer = socks

            Log.i(TAG, "Connected to ${cfg.host}:${cfg.port}, SOCKS5 on :${cfg.socksPort}")
            ConnectResult.Success("Connected — ${cfg.host} | SOCKS5 :${cfg.socksPort}")

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth", true) == true -> "Authentication failed"
                e.message?.contains("timeout", true) == true -> "Connection timed out"
                e.message?.contains("refused", true) == true -> "Connection refused"
                else -> "SSH error: ${e.message}"
            }
            ConnectResult.Failure(msg)
        } catch (e: Exception) {
            ConnectResult.Failure("Error: ${e.message}")
        }
    }

    // ── Disconnect ───────────────────────────────────────────
    fun disconnect() {
        socksServer?.stop()
        socksServer = null
        session?.disconnect()
        session = null
        Log.i(TAG, "Disconnected")
    }

    fun isAlive(): Boolean = try {
        session?.isConnected == true && session?.serverVersion != null
    } catch (e: Exception) { false }

    fun getSession(): Session? = session
}

// ── SOCKS5 Server ────────────────────────────────────────────
class SocksServer(private val session: Session, private val port: Int) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start() {
        serverSocket = ServerSocket(port).also { it.reuseAddress = true }
        running.set(true)
        Thread(::acceptLoop, "socks-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "SOCKS5 listening on 127.0.0.1:$port")
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val client = serverSocket?.accept() ?: break
                client.soTimeout = 30_000
                Thread({ handleClient(client) }, "socks-${client.port}")
                    .apply { isDaemon = true }.start()
            } catch (_: Exception) { if (!running.get()) break }
        }
    }

    private fun handleClient(client: Socket) {
        var channel: com.jcraft.jsch.Channel? = null
        try {
            val ins  = client.getInputStream()
            val outs = client.getOutputStream()

            // ── Greeting ────────────────────────────────────
            val ver = ins.read()
            if (ver != 5) return
            val nMethods = ins.read()
            ins.readNBytes(nMethods)
            outs.write(byteArrayOf(5, 0))  // NO AUTH

            // ── Request ─────────────────────────────────────
            val req = ins.readNBytes(4)
            if (req[1].toInt() != 1) return  // only CONNECT

            val destHost = when (req[3].toInt()) {
                1 -> {  // IPv4
                    val b = ins.readNBytes(4)
                    "${b[0].toUByte()}.${b[1].toUByte()}.${b[2].toUByte()}.${b[3].toUByte()}"
                }
                3 -> {  // Domain
                    val len = ins.read()
                    String(ins.readNBytes(len))
                }
                4 -> {  // IPv6 — read 16 bytes, format as string
                    val b = ins.readNBytes(16)
                    b.joinToString(":") { "%02x".format(it) }
                }
                else -> return
            }
            val destPort = (ins.read() shl 8) or ins.read()

            // ── Open SSH channel ─────────────────────────────
            val ch = session.openChannel("direct-tcpip") as com.jcraft.jsch.ChannelDirectTCPIP
            ch.setHost(destHost)
            ch.setPort(destPort)
            ch.setOrgIPAddress("127.0.0.1")
            ch.setOrgPort(0)
            ch.connect(10_000)
            channel = ch

            // ── Success reply ────────────────────────────────
            outs.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            outs.flush()

            // ── Relay ────────────────────────────────────────
            relay(client.getInputStream(), client.getOutputStream(),
                  ch.inputStream, ch.outputStream)

        } catch (e: Exception) {
            Log.d(TAG, "socks client: ${e.message}")
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
            try { client.close() }        catch (_: Exception) {}
        }
    }

    private fun relay(
        ci: InputStream, co: OutputStream,
        si: InputStream, so: OutputStream
    ) {
        val done = AtomicBoolean(false)
        fun pump(src: InputStream, dst: OutputStream) = Thread({
            try {
                val buf = ByteArray(32768)
                while (!done.get()) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    dst.flush()
                }
            } catch (_: Exception) {}
            finally { done.set(true) }
        }).apply { isDaemon = true }.also { it.start() }

        val t1 = pump(ci, so)
        val t2 = pump(si, co)
        t1.join(60_000)
        t2.join(1_000)
    }
}
