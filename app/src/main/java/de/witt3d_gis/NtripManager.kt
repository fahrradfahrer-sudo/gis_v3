package de.witt3d_gis

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class NtripManager {
    private val TAG = "NtripManager"
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var isRunning = false

    private var lastHost = ""
    private var lastPort = 2101
    private var lastMount = ""
    private var lastUser = ""
    private var lastPass = ""

    interface NtripListener {
        fun onRtcmData(data: ByteArray)
        fun onError(message: String)
        fun onConnected()
        fun onDisconnected()
    }

    var listener: NtripListener? = null

    @Synchronized
    fun connect(host: String, port: Int, mountpoint: String, user: String, pass: String) {
        if (isRunning) {
            Log.d(TAG, "NTRIP: Active, disconnecting first...")
            disconnect()
            Thread.sleep(300)
        }

        lastHost = host.trim(); lastPort = port; lastMount = mountpoint.trim(); lastUser = user.trim(); lastPass = pass.trim()
        isRunning = true
        Log.i(TAG, "NTRIP: Starting client for $host:$port")

        executor.submit {
            runConnectionLoop()
        }
    }

    private fun runConnectionLoop() {
        while (isRunning) {
            var currentSocket: Socket? = null
            try {
                Log.i(TAG, "NTRIP: Connecting to $lastHost:$lastPort...")
                currentSocket = Socket()
                currentSocket.connect(InetSocketAddress(lastHost, lastPort), 10000)
                currentSocket.soTimeout = 20000
                socket = currentSocket

                val outputStream = currentSocket.getOutputStream()
                val inputStream = currentSocket.getInputStream()

                val auth = Base64.encodeToString("$lastUser:$lastPass".toByteArray(), Base64.NO_WRAP)
                // Use pure NTRIP 1.0 format for best compatibility with older casters
                val request = "GET /$lastMount HTTP/1.0\r\n" +
                              "User-Agent: NTRIP Witt3D_GIS\r\n" +
                              "Authorization: Basic $auth\r\n" +
                              "Connection: close\r\n" +
                              "\r\n"

                outputStream.write(request.toByteArray())
                outputStream.flush()

                val buffer = ByteArray(4096)
                var bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead)
                    Log.i(TAG, "NTRIP: Response line: ${response.split("\r\n")[0]}")

                    if (response.contains("200 OK") || response.contains("ICY 200 OK")) {
                        mainHandler.post { listener?.onConnected() }

                        // Handle data already in buffer
                        val headerEnd = response.indexOf("\r\n\r\n")
                        if (headerEnd != -1) {
                            val binaryStart = headerEnd + 4
                            if (binaryStart < bytesRead) {
                                mainHandler.post { listener?.onRtcmData(buffer.copyOfRange(binaryStart, bytesRead)) }
                            }
                        }

                        // Read RTCM stream
                        while (isRunning) {
                            bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) break
                            if (bytesRead > 0) {
                                val data = buffer.copyOfRange(0, bytesRead)
                                mainHandler.post { listener?.onRtcmData(data) }
                            }
                        }
                    } else {
                        val err = response.split("\r\n")[0]
                        Log.e(TAG, "NTRIP Auth failed: $err")
                        mainHandler.post { listener?.onError("Auth: $err") }
                        if (response.contains("401") || response.contains("403")) isRunning = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "NTRIP loop error: ${e.message}")
                if (isRunning) {
                    mainHandler.post { listener?.onError("NTRIP: ${e.message}") }
                    Thread.sleep(5000)
                }
            } finally {
                try { currentSocket?.close() } catch (e: Exception) {}
                if (socket == currentSocket) socket = null
            }

            if (isRunning) {
                Log.i(TAG, "NTRIP: Retrying in 2s...")
                mainHandler.post { listener?.onDisconnected() }
                Thread.sleep(2000)
            }
        }
    }

    fun sendGga(gga: String) {
        executor.submit {
            try {
                val currentSocket = socket
                if (isRunning && currentSocket?.isConnected == true) {
                    val out = currentSocket.getOutputStream()
                    val msg = if (gga.endsWith("\r\n")) gga else "$gga\r\n"
                    synchronized(currentSocket) {
                        out.write(msg.toByteArray())
                        out.flush()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    @Synchronized
    fun disconnect() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }

    fun release() {
        disconnect()
        executor.shutdownNow()
    }
}
