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

    private val sentenceQueue = LinkedBlockingQueue<Any>() // Can be String (NMEA) or ByteArray (UBX)
    private var byteBuffer = ByteArray(4096)
    private var byteBufferPos = 0
    private var isProcessing = false
    private var lastUbxTime = 0L
    private var lastHardwareGgaTime = 0L
    private var lastRtkAge: Double? = null

    interface LocationListener {
        fun onLocationUpdate(location: SerialLocation)
        fun onGgaReceived(sentence: String)
        fun onSatellitesUpdate(sats: List<SatInfo>)
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
                    val item = sentenceQueue.poll(500, TimeUnit.MILLISECONDS)
                    when (item) {
                        is String -> parseNmea(item)
                        is ByteArray -> parseUbx(item)
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
        synchronized(byteBuffer) {
            if (byteBufferPos + data.size > byteBuffer.size) {
                if (byteBuffer.size < 65536) {
                    byteBuffer = byteBuffer.copyOf(byteBuffer.size * 2)
                } else {
                    byteBufferPos = 0
                }
            }
            System.arraycopy(data, 0, byteBuffer, byteBufferPos, data.size)
            byteBufferPos += data.size

            var i = 0
            while (i < byteBufferPos) {
                val b = byteBuffer[i].toInt() and 0xFF
                if (b == 0x24) { // '$'
                    val end = findNewline(i)
                    if (end != -1) {
                        val sentence = String(byteBuffer, i, end - i, Charsets.US_ASCII).trim()
                        if (sentence.isNotEmpty()) sentenceQueue.offer(sentence)
                        i = end + 1
                        continue
                    } else break
                } else if (b == 0xB5) { // UBX Sync 1
                    if (i + 1 < byteBufferPos && (byteBuffer[i+1].toInt() and 0xFF) == 0x62) {
                        if (i + 6 <= byteBufferPos) {
                            val len = (byteBuffer[i+4].toInt() and 0xFF) or ((byteBuffer[i+5].toInt() and 0xFF) shl 8)
                            val totalLen = len + 8
                            if (totalLen > 2048) { i++; continue } // Protection
                            if (i + totalLen <= byteBufferPos) {
                                val msg = byteBuffer.copyOfRange(i, i + totalLen)
                                if (verifyUbxChecksum(msg)) {
                                    lastUbxTime = System.currentTimeMillis() // Priority timer
                                    sentenceQueue.offer(msg)
                                    i += totalLen
                                    continue
                                }
                            } else break
                        } else break
                    }
                }
                i++
            }

            if (i > 0) {
                if (i < byteBufferPos) {
                    System.arraycopy(byteBuffer, i, byteBuffer, 0, byteBufferPos - i)
                    byteBufferPos -= i
                } else { byteBufferPos = 0 }
            }
        }
    }

    private fun findNewline(start: Int): Int {
        for (i in start until byteBufferPos) {
            if (byteBuffer[i] == '\n'.toByte()) return i
        }
        return -1
    }

    override fun onRunError(e: Exception) {
        sendError("Serial Error: ${e.message}")
        disconnect()
    }

    private fun verifyUbxChecksum(data: ByteArray): Boolean {
        if (data.size < 8) return false
        var a = 0
        var b = 0
        for (i in 2 until data.size - 2) {
            a = (a + (data[i].toInt() and 0xFF)) and 0xFF
            b = (b + a) and 0xFF
        }
        return (data[data.size - 2].toInt() and 0xFF) == a && (data[data.size - 1].toInt() and 0xFF) == b
    }

    private fun parseUbx(data: ByteArray) {
        if (data.size < 8) return
        val cls = data[2].toInt() and 0xFF
        val id = data[3].toInt() and 0xFF

        if (cls == 0x01 && id == 0x07) { // NAV-PVT
            val payload = data.sliceArray(6 until data.size - 2)
            if (payload.size < 84) return

            val lon = readInt32(payload, 24) / 1e7
            val lat = readInt32(payload, 28) / 1e7
            val hMSL = readInt32(payload, 36) / 1000.0

            // NAV-PVT fields
            val fixTypeRaw = payload[20].toInt() and 0xFF
            val flags = payload[21].toInt() and 0xFF
            val flags2 = payload[22].toInt() and 0xFF
            val numSV = payload[23].toInt() and 0xFF
            val gSpeed = readInt32(payload, 60) / 1000.0 * 3.6 // mm/s to km/h
            val acc = readUInt32(payload, 40) / 1000.0f // hAcc in m

            val gnssFixOk = (flags and 0x01) != 0
            val diffSoln = (flags and 0x02) != 0
            val carrSolnFromFlags = (flags shr 6) and 0x03 // official NAV-PVT carrSoln field
            val carrSolnFromFlags2 = (flags2 shr 6) and 0x03 // fallback for receiver fw variants
            val carrSoln = if (carrSolnFromFlags != 0) carrSolnFromFlags else carrSolnFromFlags2

            val finalFix = when {
                !gnssFixOk || fixTypeRaw <= 1 || fixTypeRaw == 5 -> "No Fix"
                carrSoln == 2 -> "RTK"
                carrSoln == 1 -> "FRTK"
                diffSoln -> "DGPS"
                fixTypeRaw == 4 -> "GNSS+DR"
                fixTypeRaw in 2..3 -> "Single"
                else -> "No Fix"
            }

            val ggaQuality = when (finalFix) {
                "RTK" -> 4
                "FRTK" -> 5
                "DGPS" -> 2
                "Single", "GNSS+DR" -> 1
                else -> 0
            }

            // Forward for NTRIP if hardware only sends UBX (or if we want to ensure NMEA is sent)
            if (System.currentTimeMillis() - lastHardwareGgaTime > 1500) {
                val time = java.text.SimpleDateFormat("HHmmss.SS", java.util.Locale.US).format(java.util.Date())
                val latAbs = Math.abs(lat)
                val lonAbs = Math.abs(lon)
                val gga = "GPGGA,$time,%02d%07.4f,%s,%03d%07.4f,%s,%d,%02d,0.9,%.2f,M,0.0,M,,".format(
                    latAbs.toInt(), (latAbs - latAbs.toInt()) * 60.0, if (lat >= 0) "N" else "S",
                    lonAbs.toInt(), (lonAbs - lonAbs.toInt()) * 60.0, if (lon >= 0) "E" else "W",
                    ggaQuality, numSV, hMSL
                )
                var checksum = 0
                gga.forEach { checksum = checksum xor it.code }
                mainHandler.post { listener?.onGgaReceived("\$$gga*%02X".format(checksum)) }
            }

            if (finalFix != "RTK" && finalFix != "FRTK") {
                lastRtkAge = null
            }

            lastUbxTime = System.currentTimeMillis()
            val location = SerialLocation(lat, lon, hMSL, accuracy = acc, satellites = numSV, fixType = finalFix, speed = gSpeed, rtkAge = lastRtkAge)
            mainHandler.post { listener?.onLocationUpdate(location) }
        } else if (cls == 0x01 && id == 0x35) { // NAV-SAT
            val payload = data.sliceArray(6 until data.size - 2)
            if (payload.size < 8) return
            val numSvs = payload[5].toInt() and 0xFF // Corrected offset for numSvs in NAV-SAT
            val sats = mutableListOf<SatInfo>()
            for (i in 0 until numSvs) {
                val off = 8 + (i * 12)
                if (off + 12 > payload.size) break
                val gnssId = payload[off].toInt() and 0xFF
                val svId = payload[off + 1].toInt() and 0xFF
                val cno = payload[off + 2].toInt() and 0xFF
                val elev = payload[off + 3].toInt()
                val azim = (payload[off + 4].toInt() and 0xFF) or ((payload[off + 5].toInt() and 0xFF) shl 8)
                sats.add(SatInfo(svId, elev, azim, cno, gnssId))
            }
            mainHandler.post { listener?.onSatellitesUpdate(sats) }
        }
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
               ((data[offset + 1].toInt() and 0xFF) shl 8) or
               ((data[offset + 2].toInt() and 0xFF) shl 16) or
               ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF)) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun parseNmea(sentence: String) {
        try {
            if (sentence.length < 6) return
            val parts = sentence.split(",")
            if (parts.isEmpty()) return

            val type = parts[0]
            if (type == "\$PUBX" && parts.size >= 3) {
                val subtype = parts[1]
                if (subtype == "00" && parts.size >= 20) { // PUBX 00
                    val latRaw = parts[3]
                    val latHem = parts[4]
                    val lonRaw = parts[5]
                    val lonHem = parts[6]
                    val alt = parts[7].toDoubleOrNull() ?: 0.0
                    val navStat = parts[8]
                    val hAcc = parts[9].toDoubleOrNull()
                    val speed = parts[11].toDoubleOrNull()
                    val diffAge = parts[14].toDoubleOrNull()
                    val numSvs = parts[18].toIntOrNull() ?: 0

                    val lat = parseLatitude(latRaw, latHem)
                    val lon = parseLongitude(lonRaw, lonHem)

                    // Forward for NTRIP if no standard GGA is present
                    if (lat != null && lon != null && System.currentTimeMillis() - lastHardwareGgaTime > 1500 && System.currentTimeMillis() - lastUbxTime > 1500) {
                        val time = java.text.SimpleDateFormat("HHmmss.SS", java.util.Locale.US).format(java.util.Date())
                        val latAbs = Math.abs(lat)
                        val lonAbs = Math.abs(lon)
                        val ggaQuality = when (navStat.trim().uppercase()) {
                            "RK" -> 4
                            "FR" -> 5
                            "D2", "D3" -> 2
                            "G2", "G3" -> 1
                            else -> 1
                        }
                        val gga = "GPGGA,$time,%02d%07.4f,%s,%03d%07.4f,%s,%d,%02d,0.9,%.2f,M,0.0,M,,".format(
                            latAbs.toInt(), (latAbs - latAbs.toInt()) * 60.0, if (lat >= 0) "N" else "S",
                            lonAbs.toInt(), (lonAbs - lonAbs.toInt()) * 60.0, if (lon >= 0) "E" else "W",
                            ggaQuality, numSvs, alt
                        )
                        var checksum = 0
                        gga.forEach { checksum = checksum xor it.code }
                        mainHandler.post { listener?.onGgaReceived("\$$gga*%02X".format(checksum)) }
                    }

                    // but location update is suppressed if high-precision binary is active
                    if (System.currentTimeMillis() - lastUbxTime > 5000) {
                        val navStatTrimmed = navStat.trim().uppercase()
                        val fixType = when (navStatTrimmed) {
                            "G3" -> "Single"
                            "G2" -> "Single"
                            "D3" -> "DGPS"
                            "D2" -> "DGPS"
                            "NF" -> "No Fix"
                            "DR" -> "DR"
                            "RK" -> "RTK"
                            "FR" -> "FRTK"
                        "RTK" -> "RTK"
                        "FLOAT" -> "FRTK"
                            else -> navStatTrimmed
                        }

                        if (diffAge != null) lastRtkAge = diffAge

                        if (lat != null && lon != null) {
                            val location = SerialLocation(lat, lon, alt, satellites = numSvs, fixType = fixType, accuracy = hAcc?.toFloat(), speed = speed, rtkAge = lastRtkAge)
                            mainHandler.post { listener?.onLocationUpdate(location) }
                        }
                    }
                } else if (subtype == "03" && parts.size >= 3) { // PUBX 03
                    val numSvs = parts[2].toIntOrNull() ?: 0
                    val sats = mutableListOf<SatInfo>()
                    for (i in 0 until numSvs) {
                        val off = 3 + (i * 6)
                        if (off + 6 > parts.size) break
                        val svId = parts[off].toIntOrNull() ?: 0
                        val stat = parts[off+1] // U: used, e: enabled...
                        val azim = parts[off+2].toIntOrNull() ?: 0
                        val elev = parts[off+3].toIntOrNull() ?: 0
                        val cno = parts[off+4].toIntOrNull() ?: 0

                        // For PUBX 03 we don't have gnssId directly, guess by svId
                        val gnssId = when {
                            svId in 1..32 -> 0 // GPS
                            svId in 120..158 -> 1 // SBAS
                            svId in 193..197 -> 5 // QZSS
                            svId in 211..246 -> 2 // Galileo
                            else -> 0
                        }
                        sats.add(SatInfo(svId, elev, azim, cno, gnssId))
                    }
                    mainHandler.post { listener?.onSatellitesUpdate(sats) }
                }
            } else if (type.endsWith("GGA") && parts.size >= 10) {
                // Always forward GGA for NTRIP support
                lastHardwareGgaTime = System.currentTimeMillis()
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

                if (age != null) lastRtkAge = age

                val fixType = when(quality) {
                    1 -> "Single"
                    2 -> "DGPS"
                    4 -> "RTK"
                    5 -> "FRTK"
                    else -> "No Fix"
                }

                if (lat != null && lon != null) {
                    if (System.currentTimeMillis() - lastUbxTime > 5000) { // Only use NMEA if no UBX for 5s
                        val location = SerialLocation(lat, lon, alt, satellites = sats, fixType = fixType, rtkAge = lastRtkAge)
                        mainHandler.post { listener?.onLocationUpdate(location) }
                    }
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
