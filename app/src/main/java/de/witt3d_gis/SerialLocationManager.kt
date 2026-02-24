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
    val time: Long = System.currentTimeMillis()
)

class SerialLocationManager(private val context: Context) : SerialInputOutputManager.Listener {
    private val TAG = "SerialLocationManager"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var buffer = StringBuilder()

    interface LocationListener {
        fun onLocationUpdate(location: SerialLocation)
        fun onError(message: String)
        fun onConnected()
        fun onDisconnected()
    }

    var listener: LocationListener? = null

    fun getAvailableDevices(): List<UsbDevice> {
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        return availableDrivers.map { it.device }
    }

    fun connect(device: UsbDevice, baudRate: Int = 9600) {
        Log.d(TAG, "Attempting to connect to ${device.deviceName} at $baudRate baud")
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            if (driver == null) {
                Log.e(TAG, "No driver found for device")
                listener?.onError("No driver found for device")
                return
            }

            if (driver.ports.isEmpty()) {
                Log.e(TAG, "No ports available on device")
                listener?.onError("No ports available on device")
                return
            }

            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                Log.e(TAG, "Could not open device connection")
                listener?.onError("Could not open device connection")
                return
            }

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1)
            usbSerialPort = port

            val ioManager = SerialInputOutputManager(usbSerialPort, this)
            usbIoManager = ioManager
            executor.submit(ioManager)

            mainHandler.post { listener?.onConnected() }
            Log.d(TAG, "Connected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during connection", e)
            mainHandler.post { listener?.onError("Connection error: ${e.message}") }
            disconnect()
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        usbIoManager?.stop()
        usbIoManager = null
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing port", e)
        }
        usbSerialPort = null
        mainHandler.post { listener?.onDisconnected() }
    }

    override fun onNewData(data: ByteArray) {
        try {
            val str = String(data, Charsets.US_ASCII)
            buffer.append(str)

            var newlineIndex = buffer.indexOf("\n")
            while (newlineIndex != -1) {
                val sentence = buffer.substring(0, newlineIndex).trim()
                buffer.delete(0, newlineIndex + 1)
                if (sentence.isNotEmpty()) {
                    parseNmea(sentence)
                }
                newlineIndex = buffer.indexOf("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling new data", e)
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial run error", e)
        mainHandler.post {
            listener?.onError("Serial error: ${e.message}")
            disconnect()
        }
    }

    private fun parseNmea(sentence: String) {
        if (!sentence.startsWith("$")) return
        Log.v(TAG, "NMEA: $sentence")

        val parts = sentence.split(",")
        if (parts.isEmpty()) return

        val type = parts[0]
        try {
            if (type.endsWith("GGA") && parts.size >= 10) {
                val lat = parseLatitude(parts[2], parts[3])
                val lon = parseLongitude(parts[4], parts[5])
                val quality = try { parts[6].toInt() } catch (e: Exception) { 0 }
                val alt = parts[9].toDoubleOrNull()

                if (lat != null && lon != null && quality > 0) {
                    val location = SerialLocation(lat, lon, alt)
                    Log.d(TAG, "Parsed GGA: $lat, $lon, alt=$alt")
                    mainHandler.post { listener?.onLocationUpdate(location) }
                }
            } else if (type.endsWith("RMC") && parts.size >= 7) {
                val status = parts[2]
                if (status == "A") { // Valid
                    val lat = parseLatitude(parts[3], parts[4])
                    val lon = parseLongitude(parts[5], parts[6])

                    if (lat != null && lon != null) {
                        val location = SerialLocation(lat, lon)
                        Log.d(TAG, "Parsed RMC: $lat, $lon")
                        mainHandler.post { listener?.onLocationUpdate(location) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NMEA: $sentence", e)
        }
    }

    private fun parseLatitude(latStr: String, hemisphere: String): Double? {
        if (latStr.length < 4 || hemisphere.isEmpty()) return null
        return try {
            val degrees = latStr.substring(0, 2).toDouble()
            val minutes = latStr.substring(2).toDouble()
            var decimal = degrees + (minutes / 60.0)
            if (hemisphere == "S") decimal = -decimal
            decimal
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLongitude(lonStr: String, hemisphere: String): Double? {
        if (lonStr.length < 5 || hemisphere.isEmpty()) return null
        return try {
            val degrees = lonStr.substring(0, 3).toDouble()
            val minutes = lonStr.substring(3).toDouble()
            var decimal = degrees + (minutes / 60.0)
            if (hemisphere == "W") decimal = -decimal
            decimal
        } catch (e: Exception) {
            null
        }
    }
}
