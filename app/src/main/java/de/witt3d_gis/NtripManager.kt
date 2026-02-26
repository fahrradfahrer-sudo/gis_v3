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

        lastHost = host; lastPort = port; lastMount = mountpoint; lastUser = user; lastPass = pass
        isRunning = true

        executor.submit {
            runConnectionLoop()
        }
    }

    private fun runConnectionLoop() {
        while (isRunning) {
            var currentSocket: Socket? = null
            try {
                Log.i(TAG, "NTRIP: Attempting connection to $lastHost:$lastPort")
                currentSocket = Socket()
                currentSocket.connect(InetSocketAddress(lastHost, lastPort), 10000)
                currentSocket.soTimeout = 15000
                socket = currentSocket

                val outputStream = currentSocket.getOutputStream()
                val inputStream = currentSocket.getInputStream()

                val auth = Base64.encodeToString("$lastUser:$lastPass".toByteArray(), Base64.NO_WRAP)
                // Use standard NTRIP 1.0 request for widest compatibility
                val request = "GET /$lastMount HTTP/1.0\r\n" +
                              "User-Agent: NTRIP Witt3D_GIS\r\n" +
                              "Authorization: Basic $auth\r\n" +
                              "Connection: close\r\n" +
                              "\r\n"

                outputStream.write(request.toByteArray())
                outputStream.flush()

                val buffer = ByteArray(2048)
                val bytesRead = inputStream.read(buffer) ?: 0
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead)
                    Log.i(TAG, "NTRIP Server: ${response.split("\r\n")[0]}")

                    if (response.contains("200 OK") || response.contains("ICY 200 OK")) {
                        mainHandler.post { listener?.onConnected() }

                        val rtcmBuffer = ByteArray(4096)
                        while (isRunning) {
                            val read = inputStream.read(rtcmBuffer) ?: -1
                            if (read == -1) break
                            if (read > 0) {
                                val data = rtcmBuffer.copyOfRange(0, read)
                                mainHandler.post { listener?.onRtcmData(data) }
                            }
                        }
                    } else {
                        val msg = response.split("\r\n")[0]
                        mainHandler.post { listener?.onError("Caster: $msg") }
                        if (response.contains("401") || response.contains("403") || response.contains("404")) {
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
        Log.i(TAG, "NTRIP: Loop terminated")
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
