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
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
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

    fun connect(host: String, port: Int, mountpoint: String, user: String, pass: String) {
        if (isRunning) {
            Log.d(TAG, "NTRIP: Already running, disconnecting first...")
            disconnect()
            Thread.sleep(500)
        }

        lastHost = host; lastPort = port; lastMount = mountpoint; lastUser = user; lastPass = pass
        isRunning = true
        Log.i(TAG, "NTRIP: Starting client for $host:$port")

        executor.submit {
            runConnectionLoop()
        }
    }

    private fun runConnectionLoop() {
        while (isRunning) {
            try {
                Log.i(TAG, "NTRIP: Connecting to $lastHost:$lastPort...")
                socket = Socket()
                socket?.connect(InetSocketAddress(lastHost, lastPort), 10000)
                socket?.soTimeout = 15000

                outputStream = socket?.getOutputStream()
                inputStream = socket?.getInputStream()

                val auth = Base64.encodeToString("$lastUser:$lastPass".toByteArray(), Base64.NO_WRAP)
                // Use HTTP/1.1 with explicit Host and NTRIP headers for VRS casters
                val request = "GET /$lastMount HTTP/1.1\r\n" +
                              "Host: $lastHost\r\n" +
                              "Ntrip-Version: Ntrip/2.0\r\n" +
                              "User-Agent: NTRIP Witt3D_GIS\r\n" +
                              "Authorization: Basic $auth\r\n" +
                              "Connection: close\r\n" +
                              "\r\n"

                outputStream?.write(request.toByteArray())
                outputStream?.flush()

                val buffer = ByteArray(2048)
                val bytesRead = inputStream?.read(buffer) ?: 0
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead)
                    Log.i(TAG, "NTRIP Server Response: ${response.split("\r\n")[0]}")

                    if (response.contains("200 OK") || response.contains("ICY 200 OK")) {
                        Log.i(TAG, "NTRIP: Auth successful, receiving RTCM...")
                        mainHandler.post { listener?.onConnected() }

                        val rtcmBuffer = ByteArray(4096)
                        while (isRunning) {
                            val read = inputStream?.read(rtcmBuffer) ?: -1
                            if (read == -1) break
                            if (read > 0) {
                                val data = rtcmBuffer.copyOfRange(0, read)
                                mainHandler.post { listener?.onRtcmData(data) }
                            }
                        }
                    } else {
                        Log.e(TAG, "NTRIP Auth failed: $response")
                        mainHandler.post { listener?.onError("Auth failed: ${response.take(30)}") }
                        if (response.contains("401") || response.contains("403") || response.contains("404")) {
                            isRunning = false // Stop loop on fatal auth errors
                        }
                    }
                } else {
                    throw Exception("Empty response from caster")
                }
            } catch (e: Exception) {
                Log.e(TAG, "NTRIP error: ${e.message}")
                if (isRunning) mainHandler.post { listener?.onError("NTRIP: ${e.message}") }
                if (isRunning) Thread.sleep(5000)
            } finally {
                closeInternal()
            }

            if (isRunning) {
                Log.i(TAG, "NTRIP: Retrying connection...")
                mainHandler.post { listener?.onDisconnected() }
                Thread.sleep(2000)
            }
        }
        Log.i(TAG, "NTRIP: Connection loop ended")
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
                    Log.v(TAG, "NTRIP: Sent GGA")
                }
            } catch (e: Exception) {
                Log.e(TAG, "NTRIP: Send GGA failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        Log.i(TAG, "NTRIP: Disconnecting...")
        isRunning = false
        executor.submit { closeInternal() }
    }

    private fun closeInternal() {
        try { inputStream?.close() } catch (e: Exception) {}
        try { outputStream?.close() } catch (e: Exception) {}
        try { socket?.close() } catch (e: Exception) {}
        inputStream = null; outputStream = null; socket = null
    }

    fun release() {
        disconnect()
        executor.shutdownNow()
    }
}
