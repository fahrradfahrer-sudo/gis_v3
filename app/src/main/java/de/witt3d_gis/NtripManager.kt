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
    // Use cached thread pool to allow concurrent reading and writing
    private val executor = Executors.newCachedThreadPool()
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
                Log.i(TAG, "Connecting to NTRIP caster $host:$port...")
                socket = Socket(host, port)
                socket?.soTimeout = 15000

                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()

                val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                // Use HTTP/1.0 for better compatibility
                val request = "GET /$mountpoint HTTP/1.0\r\n" +
                              "User-Agent: NTRIP Witt3D_GIS\r\n" +
                              "Authorization: Basic $auth\r\n" +
                              "Connection: close\r\n" +
                              "\r\n"

                outputStream?.write(request.toByteArray())
                outputStream?.flush()

                val buffer = ByteArray(1024)
                val bytesRead = inputStream?.read(buffer) ?: 0
                if (bytesRead <= 0) throw Exception("No response from caster")

                val response = String(buffer, 0, bytesRead)
                Log.i(TAG, "NTRIP Response: ${response.split("\r\n")[0]}")

                if (response.contains("ICY 200 OK") || response.contains("200 OK")) {
                    mainHandler.post { listener?.onConnected() }

                    val rtcmBuffer = ByteArray(4096)
                    var totalReceived = 0L
                    while (isRunning) {
                        val read = inputStream?.read(rtcmBuffer) ?: -1
                        if (read == -1) break
                        if (read > 0) {
                            totalReceived += read
                            val data = rtcmBuffer.copyOfRange(0, read)
                            mainHandler.post { listener?.onRtcmData(data) }
                            if (totalReceived % 10240 == 0L) Log.d(TAG, "Received ${totalReceived/1024} KB RTCM")
                        }
                    }
                } else {
                    mainHandler.post { listener?.onError("Caster error: ${response.split("\r\n")[0]}") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "NTRIP error: ${e.message}")
                mainHandler.post { listener?.onError("NTRIP Socket Error: ${e.message}") }
            } finally {
                disconnectInternal()
            }
        }
    }

    fun sendGga(gga: String) {
        executor.submit {
            try {
                val out = outputStream
                if (isRunning && socket?.isConnected == true && out != null) {
                    val message = if (gga.endsWith("\r\n")) gga else "$gga\r\n"
                    synchronized(out) {
                        out.write(message.toByteArray())
                        out.flush()
                    }
                    Log.v(TAG, "NTRIP: Sent GGA (${message.length} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending GGA: ${e.message}")
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
        } catch (e: Exception) {}
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
