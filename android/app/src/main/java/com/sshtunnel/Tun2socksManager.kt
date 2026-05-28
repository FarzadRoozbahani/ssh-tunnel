package com.sshtunnel

import android.content.Context
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

private const val TAG = "Tun2socksManager"

/**
 * Manages the tun2socks process.
 * The binary is bundled in assets/arm64-v8a/tun2socks or assets/armeabi-v7a/tun2socks
 * and copied to app's private files directory on first run.
 */
object Tun2socksManager {

    private var process: Process? = null

    fun start(
        ctx: Context,
        vpnService: VpnService,
        tunFd: ParcelFileDescriptor,
        socksPort: Int
    ): Boolean {
        stop()
        val binary = extractBinary(ctx) ?: run {
            Log.e(TAG, "Failed to extract tun2socks binary")
            return false
        }

        return try {
            // tun2socks uses fd://N to read from TUN interface
            val fd = tunFd.fd
            val proxy = "socks5://127.0.0.1:$socksPort"

            val cmd = arrayOf(
                binary.absolutePath,
                "--device", "fd://$fd",
                "--proxy",  proxy,
                "--loglevel", "warning"
            )

            Log.i(TAG, "Starting: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(*cmd)
                .redirectErrorStream(true)

            // Pass the TUN fd to the child process
            // Android < 12 needs VpnService.protect() — we protect via VpnService builder
            process = pb.start()

            // Log output in background
            Thread({
                process?.inputStream?.bufferedReader()?.forEachLine { line ->
                    Log.d(TAG, "t2s: $line")
                }
            }, "tun2socks-log").apply { isDaemon = true }.start()

            Log.i(TAG, "tun2socks started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start tun2socks: ${e.message}")
            false
        }
    }

    fun stop() {
        process?.let {
            Log.i(TAG, "Stopping tun2socks")
            it.destroy()
            try { it.waitFor() } catch (_: Exception) {}
        }
        process = null
    }

    val isRunning: Boolean get() = process?.isAlive == true

    private fun extractBinary(ctx: Context): File? {
        val abi = getAbi()
        val assetPath = "$abi/tun2socks"
        val outFile = File(ctx.filesDir, "tun2socks")

        return try {
            ctx.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            outFile.setExecutable(true)
            Log.i(TAG, "Extracted tun2socks binary to ${outFile.absolutePath}")
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed for $assetPath: ${e.message}")
            null
        }
    }

    private fun getAbi(): String {
        val supported = android.os.Build.SUPPORTED_ABIS
        return when {
            supported.contains("arm64-v8a")  -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            supported.contains("x86_64")      -> "x86_64"
            else -> "arm64-v8a"
        }
    }
}
