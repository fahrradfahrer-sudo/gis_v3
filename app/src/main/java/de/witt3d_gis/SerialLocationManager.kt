package de.witt3d_gis

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

data class SerialLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val satellites: Int? = null,
    val fixType: String? = null,
    val time: Long = System.currentTimeMillis()
)

class SerialLocationManager(private val context: Context) : SerialInputOutputManager.Listener {
    private val TAG = "SerialLocationManager"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var buffer = StringBuilder()

    interface LocationListener {
        fun onLocationUpdate(location: SerialLocation)
        fun onGgaReceived(sentence: String)
        fun onError(message: String)
        fun onConnected()
        fun onDisconnected()
    }

    var listener: LocationListener? = null

    fun getAvailableDevices(): List<UsbDevice> {
        return try {
            val prober = UsbSerialProber.getDefaultProber()
            prober.findAllDrivers(usbManager).map { it.device }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding devices: ${e.message}")
            emptyList()
        }
    }

    fun connect(device: UsbDevice, baudRate: Int = 115200) {
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
            val connection = usbManager.openDevice(driver.device) ?: return
            val port = driver.ports[0]

            port.open(connection)
            port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            usbSerialPort = port
            usbIoManager = SerialInputOutputManager(usbSerialPort, this)
            executor.submit(usbIoManager)

            mainHandler.post { listener?.onConnected() }
        } catch (e: Exception) {
            sendError("Connection error: ${e.message}")
        }
    }

    private fun sendError(message: String) {
        mainHandler.post { listener?.onError(message) }
    }

    fun write(data: ByteArray) {
        executor.submit {
            try {
                val port = usbSerialPort
                if (port != null && port.isOpen) {
                    synchronized(port) {
                        port.write(data, 1000)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write error: ${e.message}")
            }
        }
    }

    fun disconnect() {
        usbIoManager?.stop()
        usbIoManager = null
        try { usbSerialPort?.close() } catch (e: Exception) {}
        usbSerialPort = null
        mainHandler.post { listener?.onDisconnected() }
    }

    fun release() {
        disconnect()
        executor.shutdownNow()
    }

    override fun onNewData(data: ByteArray) {
        // Run parsing on the I/O thread to avoid delaying the main thread
        try {
            val str = String(data, Charsets.US_ASCII)
            synchronized(buffer) {
                buffer.append(str)
                var newlineIndex = buffer.indexOf("\n")
                while (newlineIndex != -1) {
                    val sentence = buffer.substring(0, newlineIndex).trim()
                    buffer.delete(0, newlineIndex + 1)
                    if (sentence.startsWith("$")) parseNmea(sentence)
                    newlineIndex = buffer.indexOf("\n")
                }
                if (buffer.length > 8192) buffer.setLength(0)
            }
        } catch (e: Exception) {}
    }

    override fun onRunError(e: Exception) {
        sendError("Serial Error: ${e.message}")
        disconnect()
    }

    private fun parseNmea(sentence: String) {
        try {
            val parts = sentence.split(",")
            if (parts.isEmpty()) return

            val type = parts[0]
            if (type.endsWith("GGA") && parts.size >= 10) {
                mainHandler.post { listener?.onGgaReceived(sentence) }
                val lat = parseLatitude(parts[2], parts[3])
                val lon = parseLongitude(parts[4], parts[5])
                val quality = parts[6].toIntOrNull() ?: 0
                val sats = parts[7].toIntOrNull() ?: 0
                val alt = parts[9].toDoubleOrNull()

                val fixType = when(quality) {
                    1 -> "Single"
                    2 -> "DGPS"
                    4 -> "RTK"
                    5 -> "FRTK"
                    else -> "No Fix"
                }

                if (lat != null && lon != null) {
                    val location = SerialLocation(lat, lon, alt, satellites = sats, fixType = fixType)
                    mainHandler.post { listener?.onLocationUpdate(location) }
                }
            } else if (type.endsWith("RMC") && parts.size >= 7) {
                if (parts[2] == "A") {
                    val lat = parseLatitude(parts[3], parts[4])
                    val lon = parseLongitude(parts[5], parts[6])
                    if (lat != null && lon != null) {
                        val location = SerialLocation(lat, lon)
                        mainHandler.post { listener?.onLocationUpdate(location) }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun parseLatitude(latStr: String, hemisphere: String): Double? {
        if (latStr.length < 4) return null
        return try {
            val deg = latStr.substring(0, 2).toDouble()
            val min = latStr.substring(2).toDouble()
            var dec = deg + (min / 60.0)
            if (hemisphere == "S") dec = -dec
            dec
        } catch (e: Exception) { null }
    }

    private fun parseLongitude(lonStr: String, hemisphere: String): Double? {
        if (lonStr.length < 5) return null
        return try {
            val deg = lonStr.substring(0, 3).toDouble()
            val min = lonStr.substring(3).toDouble()
            var dec = deg + (min / 60.0)
            if (hemisphere == "W") dec = -dec
            dec
        } catch (e: Exception) { null }
    }
}
