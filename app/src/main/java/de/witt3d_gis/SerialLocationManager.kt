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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class SerialLocation(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
    val accuracy: Float? = null,
    val satellites: Int? = null,
    val fixType: String? = null,
    val rtkAge: Double? = null,
    val speed: Double? = null, // in km/h
    val time: Long = System.currentTimeMillis()
)

class SerialLocationManager(private val context: Context) : SerialInputOutputManager.Listener {
    private val TAG = "SerialLocationManager"
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var usbIoManager: SerialInputOutputManager? = null
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sentenceQueue = LinkedBlockingQueue<String>()
    private var buffer = StringBuilder()
    private var isProcessing = false

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
        executor.submit {
            try {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: throw Exception("Driver not found")
                if (driver.ports.isEmpty()) throw Exception("No ports available")

                val connection = usbManager.openDevice(driver.device) ?: throw Exception("Could not open device")
                val port = driver.ports[0]

                port.open(connection)
                port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

                usbSerialPort = port
                usbIoManager = SerialInputOutputManager(usbSerialPort, this)
                executor.submit(usbIoManager)

                isProcessing = true
                startProcessingWorker()

                mainHandler.post { listener?.onConnected() }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                sendError("Connection error: ${e.message}")
            }
        }
    }

    private fun startProcessingWorker() {
        executor.submit {
            while (isProcessing) {
                try {
                    val sentence = sentenceQueue.poll(500, TimeUnit.MILLISECONDS)
                    if (sentence != null) {
                        parseNmea(sentence)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Worker error", e)
                }
            }
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
        isProcessing = false
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
        try {
            val str = String(data, Charsets.US_ASCII)
            synchronized(buffer) {
                if (buffer.length > 16384) buffer.setLength(0)
                buffer.append(str)

                var newlineIndex = buffer.indexOf("\n")
                while (newlineIndex != -1) {
                    try {
                        if (newlineIndex < buffer.length) {
                            val sentence = buffer.substring(0, newlineIndex).trim()
                            buffer.delete(0, newlineIndex + 1)
                            if (sentence.startsWith("$")) {
                                sentenceQueue.offer(sentence)
                            }
                        } else {
                            buffer.setLength(0)
                            break
                        }
                    } catch (e: Exception) {
                        buffer.setLength(0)
                        break
                    }
                    newlineIndex = buffer.indexOf("\n")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NMEA Buffer Error", e)
        }
    }

    override fun onRunError(e: Exception) {
        sendError("Serial Error: ${e.message}")
        disconnect()
    }

    private fun parseNmea(sentence: String) {
        try {
            if (sentence.length < 6) return
            val parts = sentence.split(",")
            if (parts.isEmpty()) return

            val type = parts[0]
            if (type.endsWith("GGA") && parts.size >= 10) {
                mainHandler.post { listener?.onGgaReceived(sentence) }

                val latStr = parts.getOrNull(2) ?: ""
                val latHem = parts.getOrNull(3) ?: ""
                val lonStr = parts.getOrNull(4) ?: ""
                val lonHem = parts.getOrNull(5) ?: ""
                val qualStr = parts.getOrNull(6) ?: "0"
                val satStr = parts.getOrNull(7) ?: "0"
                val altStr = parts.getOrNull(9) ?: ""
                val ageStr = parts.getOrNull(13) ?: ""

                val lat = parseLatitude(latStr, latHem)
                val lon = parseLongitude(lonStr, lonHem)
                val quality = qualStr.toIntOrNull() ?: 0
                val sats = satStr.toIntOrNull() ?: 0
                val alt = altStr.toDoubleOrNull()
                val age = ageStr.toDoubleOrNull()

                val fixType = when(quality) {
                    1 -> "Single"
                    2 -> "DGPS"
                    4 -> "RTK"
                    5 -> "FRTK"
                    else -> "No Fix"
                }

                if (lat != null && lon != null) {
                    val location = SerialLocation(lat, lon, alt, satellites = sats, fixType = fixType, rtkAge = age)
                    mainHandler.post { listener?.onLocationUpdate(location) }
                }
            } else if (type.endsWith("RMC") && parts.size >= 9) {
                if (parts.getOrNull(2) == "A") {
                    val lat = parseLatitude(parts.getOrNull(3) ?: "", parts.getOrNull(4) ?: "")
                    val lon = parseLongitude(parts.getOrNull(5) ?: "", parts.getOrNull(6) ?: "")
                    val knots = parts.getOrNull(7)?.toDoubleOrNull() ?: 0.0
                    val speedKmH = knots * 1.852

                    if (lat != null && lon != null) {
                        val location = SerialLocation(lat, lon, speed = speedKmH)
                        mainHandler.post { listener?.onLocationUpdate(location) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parsing error for sentence: $sentence", e)
        }
    }

    private fun parseLatitude(latStr: String, hemisphere: String): Double? {
        val clean = latStr.trim()
        if (clean.length < 4) return null
        return try {
            val deg = clean.substring(0, 2).toDoubleOrNull() ?: return null
            val min = clean.substring(2).toDoubleOrNull() ?: return null
            var dec = deg + (min / 60.0)
            if (hemisphere == "S") dec = -dec
            dec
        } catch (e: Exception) { null }
    }

    private fun parseLongitude(lonStr: String, hemisphere: String): Double? {
        val clean = lonStr.trim()
        if (clean.length < 5) return null
        return try {
            val deg = clean.substring(0, 3).toDoubleOrNull() ?: return null
            val min = clean.substring(3).toDoubleOrNull() ?: return null
            var dec = deg + (min / 60.0)
            if (hemisphere == "W") dec = -dec
            dec
        } catch (e: Exception) { null }
    }
}
