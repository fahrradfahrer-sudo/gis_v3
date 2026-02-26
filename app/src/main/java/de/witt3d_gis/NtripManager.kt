package de.witt3d_gis

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors

class NtripManager {
    private val TAG = "NtripManager"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isRunning = false

    interface NtripListener {
        fun onRtcmData(data: ByteArray)
        fun onError(message: String)
        fun onConnected()
        fun onDisconnected()
    }

    var listener: NtripListener? = null

    fun connect(host: String, port: Int, mountpoint: String, user: String, pass: String) {
        if (isRunning) return
        isRunning = true

        executor.submit {
            try {
                Log.d(TAG, "Connecting to NTRIP caster $host:$port...")
                socket = Socket(host, port)
                socket?.soTimeout = 15000 // 15s timeout

                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()

                val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                val request = "GET /$mountpoint HTTP/1.1\r\n" +
                              "Host: $host\r\n" +
                              "User-Agent: NTRIP Witt3D_GIS\r\n" +
                              "Authorization: Basic $auth\r\n" +
                              "Connection: close\r\n" +
                              "Ntrip-Version: Ntrip/2.0\r\n" +
                              "\r\n"

                Log.d(TAG, "Sending NTRIP request: GET /$mountpoint")
                outputStream?.write(request.toByteArray())
                outputStream?.flush()

                // Read HTTP response header
                val buffer = ByteArray(1024)
                val bytesRead = inputStream?.read(buffer) ?: 0
                if (bytesRead <= 0) {
                    throw Exception("No response from caster")
                }

                val response = String(buffer, 0, bytesRead)
                Log.d(TAG, "NTRIP Response: ${response.split("\r\n")[0]}")

                if (response.contains("ICY 200 OK") || response.contains("HTTP/1.0 200 OK") || response.contains("HTTP/1.1 200 OK")) {
                    Log.d(TAG, "NTRIP Connected successfully")
                    mainHandler.post { listener?.onConnected() }

                    // Start reading RTCM stream
                    val rtcmBuffer = ByteArray(4096)
                    while (isRunning) {
                        val read = inputStream?.read(rtcmBuffer) ?: -1
                        if (read == -1) {
                            Log.d(TAG, "NTRIP Stream closed by server")
                            break
                        }
                        if (read > 0) {
                            val data = rtcmBuffer.copyOfRange(0, read)
                            mainHandler.post { listener?.onRtcmData(data) }
                        }
                    }
                } else {
                    val errorMsg = if (response.isNotEmpty()) response.split("\r\n")[0] else "No response"
                    Log.e(TAG, "NTRIP Connection failed: $errorMsg")
                    mainHandler.post { listener?.onError("NTRIP Error: $errorMsg") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "NTRIP Socket error: ${e.message}")
                mainHandler.post { listener?.onError("NTRIP Socket Error: ${e.message}") }
            } finally {
                disconnectInternal()
            }
        }
    }

    fun sendGga(gga: String) {
        executor.submit {
            try {
                if (isRunning && socket?.isConnected == true && outputStream != null) {
                    val message = if (gga.endsWith("\r\n")) gga else "$gga\r\n"
                    outputStream?.write(message.toByteArray())
                    outputStream?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending GGA to NTRIP: ${e.message}")
            }
        }
    }

    fun disconnect() {
        isRunning = false
        executor.submit { disconnectInternal() }
    }

    private fun disconnectInternal() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing NTRIP connection: ${e.message}")
        }
        socket = null
        inputStream = null
        outputStream = null
        isRunning = false
        mainHandler.post { listener?.onDisconnected() }
    }

    fun release() {
        disconnect()
        executor.shutdownNow()
    }
}
