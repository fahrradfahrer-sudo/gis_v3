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
            Log.d(TAG, "NTRIP: Re-connecting...")
            disconnect()
            Thread.sleep(500)
        }

        lastHost = host.trim(); lastPort = port; lastMount = mountpoint.trim(); lastUser = user.trim(); lastPass = pass.trim()
        isRunning = true

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
                currentSocket.soTimeout = 15000
                socket = currentSocket

                val outputStream = currentSocket.getOutputStream()
                val inputStream = currentSocket.getInputStream()

                val auth = Base64.encodeToString("$lastUser:$lastPass".toByteArray(), Base64.NO_WRAP)
                // Use a hybrid request format: HTTP/1.0 but with Host header for modern proxy support
                val request = "GET /$lastMount HTTP/1.0\r\n" +
                              "Host: $lastHost\r\n" +
                              "Ntrip-Version: Ntrip/2.0\r\n" +
                              "User-Agent: NTRIP Witt3D_GIS\r\n" +
                              "Authorization: Basic $auth\r\n" +
                              "Connection: close\r\n" +
                              "\r\n"

                outputStream.write(request.toByteArray())
                outputStream.flush()

                // Read response carefully
                val buffer = ByteArray(4096)
                var bytesRead = inputStream.read(buffer)
                if (bytesRead > 0) {
                    val responseHeader = String(buffer, 0, bytesRead)
                    Log.i(TAG, "NTRIP Server Header: ${responseHeader.split("\r\n")[0]}")

                    if (responseHeader.contains("200 OK") || responseHeader.contains("ICY 200 OK")) {
                        Log.i(TAG, "NTRIP: Authentication successful")
                        mainHandler.post { listener?.onConnected() }

                        // Handle cases where some binary data was already read into the buffer
                        val headerEnd = responseHeader.indexOf("\r\n\r\n")
                        if (headerEnd != -1) {
                            val binaryStart = headerEnd + 4
                            if (binaryStart < bytesRead) {
                                val initialData = buffer.copyOfRange(binaryStart, bytesRead)
                                mainHandler.post { listener?.onRtcmData(initialData) }
                            }
                        }

                        // Continuous read loop
                        val rtcmBuffer = ByteArray(4096)
                        while (isRunning) {
                            bytesRead = inputStream.read(rtcmBuffer)
                            if (bytesRead == -1) break
                            if (bytesRead > 0) {
                                val data = rtcmBuffer.copyOfRange(0, bytesRead)
                                mainHandler.post { listener?.onRtcmData(data) }
                            }
                        }
                    } else {
                        val errorLine = responseHeader.split("\r\n")[0]
                        Log.e(TAG, "NTRIP Error response: $responseHeader")
                        mainHandler.post { listener?.onError("Caster: $errorLine") }

                        // Fatal auth errors - don't retry immediately
                        if (responseHeader.contains("401") || responseHeader.contains("403")) {
                            isRunning = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "NTRIP Exception: ${e.message}")
                if (isRunning) {
                    mainHandler.post { listener?.onError("NTRIP: ${e.message}") }
                    Thread.sleep(5000)
                }
            } finally {
                try { currentSocket?.close() } catch (e: Exception) {}
                if (socket == currentSocket) socket = null
            }

            if (isRunning) {
                Log.i(TAG, "NTRIP: Socket closed, retrying in 2s...")
                mainHandler.post { listener?.onDisconnected() }
                Thread.sleep(2000)
            }
        }
        Log.i(TAG, "NTRIP: Connection loop ended")
    }

    fun sendGga(gga: String) {
        executor.submit {
            try {
                val currentSocket = socket
                if (isRunning && currentSocket?.isConnected == true) {
                    val out = currentSocket.getOutputStream()
                    val message = if (gga.endsWith("\r\n")) gga else "$gga\r\n"
                    synchronized(currentSocket) {
                        out.write(message.toByteArray())
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "NTRIP: GGA send failed: ${e.message}")
            }
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
