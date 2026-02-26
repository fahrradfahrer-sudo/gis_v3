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
        fun onGgaReceived(sentence: String)
        fun onError(message: String)
        fun onConnected()
        fun onDisconnected()
    }

    var listener: LocationListener? = null

    fun getAvailableDevices(): List<UsbDevice> {
        return try {
            Log.d(TAG, "Scanning for USB devices...")
            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            Log.d(TAG, "Found ${availableDrivers.size} drivers")
            availableDrivers.map { it.device }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding devices: ${e.message}", e)
            emptyList()
        }
    }

    fun connect(device: UsbDevice, baudRate: Int = 9600) {
        Log.d(TAG, "Connecting to ${device.deviceName} at $baudRate baud")
        try {
            val prober = UsbSerialProber.getDefaultProber()
            val driver = prober.probeDevice(device)
            if (driver == null) {
                sendError("No serial driver found for this USB device")
                return
            }

            if (driver.ports.isEmpty()) {
                sendError("No serial ports available on this device")
                return
            }

            Log.d(TAG, "Opening USB connection...")
            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                val hasPerm = usbManager.hasPermission(device)
                sendError("Could not open USB connection (Permission=$hasPerm)")
                return
            }

            val port = driver.ports[0]
            Log.d(TAG, "Opening port 0...")
            try {
                port.open(connection)
                port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open port: ${e.message}", e)
                sendError("Error opening port: ${e.message}")
                return
            }

            usbSerialPort = port

            Log.d(TAG, "Starting IO manager...")
            try {
                val ioManager = SerialInputOutputManager(usbSerialPort, this)
                usbIoManager = ioManager
                executor.submit(ioManager)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start IO manager: ${e.message}", e)
                sendError("IO Manager error: ${e.message}")
                port.close()
                usbSerialPort = null
                return
            }

            mainHandler.post { listener?.onConnected() }
            Log.d(TAG, "Connected and listening")
        } catch (e: Exception) {
            Log.e(TAG, "Critical connection error: ${e.message}", e)
            sendError("Critical connection error: ${e.message}")
            disconnect()
        }
    }

    private fun sendError(message: String) {
        Log.e(TAG, message)
        mainHandler.post { listener?.onError(message) }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting serial...")
        try {
            usbIoManager?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping IO manager: ${e.message}")
        }
        usbIoManager = null
        try {
            usbSerialPort?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing port: ${e.message}")
        }
        usbSerialPort = null
        mainHandler.post { listener?.onDisconnected() }
    }

    fun release() {
        disconnect()
        try {
            executor.shutdownNow()
            Log.d(TAG, "Executor shutdown")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down executor: ${e.message}")
        }
    }

    fun write(data: ByteArray) {
        executor.submit {
            try {
                usbSerialPort?.write(data, 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to serial: ${e.message}")
            }
        }
    }

    override fun onNewData(data: ByteArray) {
        try {
            val str = String(data, Charsets.US_ASCII)
            Log.v(TAG, "Received: $str")
            buffer.append(str)

            var newlineIndex = buffer.indexOf("\n")
            while (newlineIndex != -1) {
                val sentence = buffer.substring(0, newlineIndex).trim()
                buffer.delete(0, newlineIndex + 1)
                if (sentence.isNotEmpty() && sentence.startsWith("$")) {
                    parseNmea(sentence)
                }
                newlineIndex = buffer.indexOf("\n")
            }

            // Prevent buffer from growing infinitely if no newlines are found
            if (buffer.length > 4096) {
                Log.w(TAG, "Buffer overflow, clearing...")
                buffer.setLength(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onNewData: ${e.message}")
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "Serial runtime error: ${e.message}", e)
        sendError("Serial runtime error: ${e.message}")
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
                val quality = try { parts[6].toInt() } catch (e: Exception) { 0 }
                val alt = parts[9].toDoubleOrNull()

                if (lat != null && lon != null && quality > 0) {
                    val location = SerialLocation(lat, lon, alt)
                    mainHandler.post { listener?.onLocationUpdate(location) }
                }
            } else if (type.endsWith("RMC") && parts.size >= 7) {
                val status = parts[2]
                if (status == "A") { // Valid
                    val lat = parseLatitude(parts[3], parts[4])
                    val lon = parseLongitude(parts[5], parts[6])

                    if (lat != null && lon != null) {
                        val location = SerialLocation(lat, lon)
                        mainHandler.post { listener?.onLocationUpdate(location) }
                    }
                }
            }
        } catch (e: Exception) {
            // Silently ignore parse errors for individual sentences unless debugging
            // Log.v(TAG, "Parse error for: $sentence")
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
