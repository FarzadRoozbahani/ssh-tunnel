package com.sshtunnel

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
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

    private var session: Session?     = null
    private var socksServer: SocksServer? = null
    val isConnected get() = session?.isConnected == true

    suspend fun connect(cfg: TunnelConfig): ConnectResult = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val jsch = JSch()
            if (cfg.useKey && cfg.privateKey.isNotBlank())
                jsch.addIdentity("key", cfg.privateKey.toByteArray(), null, null)

            val s = jsch.getSession(cfg.username, cfg.host, cfg.port)
            if (!cfg.useKey) s.setPassword(cfg.password)
            val props = Properties()
            props["StrictHostKeyChecking"] = "no"
            props["ServerAliveInterval"]   = "30"
            props["ServerAliveCountMax"]   = "3"
            s.setConfig(props)
            s.timeout = 15_000
            s.connect()

            // quick channel test
            try {
                val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
                ch.setHost("1.1.1.1"); ch.setPort(80)
                ch.setOrgIPAddress("127.0.0.1"); ch.setOrgPort(0)
                ch.connect(5_000); ch.disconnect()
            } catch (e: Exception) {
                s.disconnect()
                return@withContext ConnectResult.Failure(
                    "TCP forwarding blocked.\nCheck AllowTcpForwarding in sshd_config."
                )
            }

            session = s
            val socks = SocksServer(s, "127.0.0.1", cfg.socksPort)
            socks.start()
            socksServer = socks

            Log.i(TAG, "Connected ${cfg.host} SOCKS5 :${cfg.socksPort}")
            ConnectResult.Success("Connected — ${cfg.host} | SOCKS5 :${cfg.socksPort}")

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth",    true) == true -> "Authentication failed"
                e.message?.contains("timeout", true) == true -> "Connection timed out"
                e.message?.contains("refused", true) == true -> "Connection refused"
                else -> "SSH error: ${e.message}"
            }
            ConnectResult.Failure(msg)
        } catch (e: Exception) {
            ConnectResult.Failure("Error: ${e.message}")
        }
    }

    fun disconnect() {
        try { socksServer?.stop() } catch (_: Exception) {}
        socksServer = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        Log.i(TAG, "Disconnected")
    }

    fun isAlive() = try {
        session?.isConnected == true
    } catch (_: Exception) { false }
}

// ── SOCKS5 ───────────────────────────────────────────────────
class SocksServer(private val ssh: Session,
                  private val host: String,
                  private val port: Int) {

    private val running = AtomicBoolean(false)
    private var srv: ServerSocket? = null

    fun start() {
        srv = ServerSocket(port).also { it.reuseAddress = true }
        running.set(true)
        thread("socks-accept") { acceptLoop() }
        Log.i("SocksServer", "Listening on $host:$port")
    }

    fun stop() {
        running.set(false)
        try { srv?.close() } catch (_: Exception) {}
        srv = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val c = srv?.accept() ?: break
                c.soTimeout = 30_000
                thread("socks-${c.port}") { handle(c) }
            } catch (_: Exception) { if (!running.get()) break }
        }
    }

    private fun handle(client: Socket) {
        var ch: com.jcraft.jsch.Channel? = null
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val ver = inp.read(); if (ver != 5) return
            repeat(inp.read()) { inp.read() }
            out.write(byteArrayOf(5, 0))

            val req = inp.readNBytes(4)
            if (req[1].toInt() != 1) return

            val destHost = when (req[3].toInt()) {
                1 -> inp.readNBytes(4).let { b ->
                    "${b[0].toUByte()}.${b[1].toUByte()}.${b[2].toUByte()}.${b[3].toUByte()}" }
                3 -> String(inp.readNBytes(inp.read()))
                4 -> inp.readNBytes(16).joinToString(":") { "%02x".format(it) }
                else -> return
            }
            val destPort = (inp.read() shl 8) or inp.read()

            val c = ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP
            c.setHost(destHost); c.setPort(destPort)
            c.setOrgIPAddress("127.0.0.1"); c.setOrgPort(0)
            c.connect(10_000)
            ch = c

            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()

            relay(inp, out, c.inputStream, c.outputStream)
        } catch (e: Exception) {
            Log.d("SocksServer", "client: ${e.message}")
        } finally {
            try { ch?.disconnect() } catch (_: Exception) {}
            try { client.close()   } catch (_: Exception) {}
        }
    }

    private fun relay(ci: InputStream, co: OutputStream,
                      si: InputStream, so: OutputStream) {
        val done = AtomicBoolean(false)
        fun pump(src: InputStream, dst: OutputStream) = thread("pump") {
            try {
                val buf = ByteArray(32768)
                while (!done.get()) {
                    val n = src.read(buf); if (n < 0) break
                    dst.write(buf, 0, n); dst.flush()
                }
            } catch (_: Exception) {}
            finally { done.set(true) }
        }
        val t1 = pump(ci, so); val t2 = pump(si, co)
        t1.join(120_000); done.set(true); t2.join(2_000)
    }
}

private fun thread(name: String, block: () -> Unit) =
    Thread(block, name).also { it.isDaemon = true; it.start() }
