package de.witt3d_gis

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SerialLocationManager.LocationListener {
    private val TAG = "MainActivity"

    // --- IMPORTANT: ENTER YOUR MAPTILER API KEY HERE ---
    private val MAPTILER_API_KEY = "YOUR_MAPTILER_API_KEY"
    // ----------------------------------------------------

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var offlineManager: OfflineManager
    private var offlineRegion: OfflineRegion? = null
    private var isDownloading = false
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private lateinit var serialLocationManager: SerialLocationManager
    private lateinit var serialLocationEngine: SerialLocationEngine
    private var isSerialConnected = false
    private var firstLocationReceived = false
    private lateinit var connectSerialButton: Button
    private var pendingBaudRate: Int = 9600

    private val ACTION_USB_PERMISSION = "de.witt3d_gis.USB_PERMISSION"
    private val PERMISSION_REQUEST_LOCATION = 1001

    private val mapStyles by lazy {
        listOf(
            "MapTiler Basic" to "https://api.maptiler.com/maps/basic/style.json?key=$MAPTILER_API_KEY",
            "OSM Bright" to "https://api.maptiler.com/maps/bright/style.json?key=$MAPTILER_API_KEY",
            "Toner" to "https://api.maptiler.com/maps/toner/style.json?key=$MAPTILER_API_KEY"
        )
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        log("USB Permission granted for ${device?.deviceName}")
                        device?.let { connectToDevice(it, pendingBaudRate) }
                    } else {
                        log("USB Permission denied for ${device?.deviceName}")
                        updateStatus("USB Permission denied")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Basic UI Setup
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        mapView = findViewById(R.id.mapView)
        connectSerialButton = findViewById(R.id.connectSerialButton)

        log("App Starting...")
        updateStatus("Initializing...")

        // 2. Initialize Managers
        serialLocationManager = SerialLocationManager(this)
        serialLocationManager.listener = this
        serialLocationEngine = SerialLocationEngine()

        // 3. Setup UI Listeners
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        downloadButton.setOnClickListener {
            if (::map.isInitialized && !isDownloading) {
                downloadRegion()
            }
        }

        connectSerialButton.setOnClickListener {
            if (isSerialConnected) {
                serialLocationManager.disconnect()
            } else {
                showDeviceSelectionDialog()
            }
        }

        val styleSpinner = findViewById<Spinner>(R.id.styleSpinner)
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            mapStyles.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        styleSpinner.adapter = adapter
        styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (::map.isInitialized) {
                    loadStyle(mapStyles[position].second)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 4. MapLibre Initialization (Delayed to ensure UI is drawn)
        Handler(Looper.getMainLooper()).postDelayed({
            initializeMap(savedInstanceState)
        }, 500)

        // 5. Register USB Receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        checkLocationPermission()
    }

    private fun initializeMap(savedInstanceState: Bundle?) {
        try {
            log("Initializing Map SDK...")
            MapLibre.getInstance(this)
            log("SDK Initialized")

            mapView.onCreate(savedInstanceState)
            mapView.getMapAsync { mapObj ->
                this.map = mapObj
                log("Map ready")

                offlineManager = OfflineManager.getInstance(this)

                if (MAPTILER_API_KEY == "YOUR_MAPTILER_API_KEY" || MAPTILER_API_KEY.isEmpty()) {
                    log("WARNING: API Key not set")
                    updateStatus("Warning: No API Key")
                }

                loadStyle(mapStyles[0].second)
            }
        } catch (e: Exception) {
            log("ERROR initializing map: ${e.message}")
            updateStatus("Map Init Error")
        }
    }

    private fun loadStyle(url: String) {
        log("Loading style...")
        updateStatus("Loading Map Style...")
        try {
            map.setStyle(Style.Builder().fromUri(url)) { style ->
                log("Style loaded")
                updateStatus("Map Ready")
                enableLocationComponent(style)
            }
        } catch (e: Exception) {
            log("Error loading style: ${e.message}")
            updateStatus("Style Error")
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = "Status: $message"
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timestamp] $message\n"
        Log.d(TAG, message)
        runOnUiThread {
            if (::logText.isInitialized) {
                logText.append(formattedMessage)
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            log("Requesting Location permissions...")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERMISSION_REQUEST_LOCATION
            )
        }
    }

    private fun enableLocationComponent(style: Style) {
        log("Enabling Location...")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            try {
                map.locationComponent.apply {
                    val options = LocationComponentActivationOptions.builder(this@MainActivity, style)
                        .locationEngine(serialLocationEngine)
                        .useDefaultLocationEngine(false)
                        .build()
                    activateLocationComponent(options)
                    isLocationComponentEnabled = true
                }
                log("Location component ready")
            } catch (e: Exception) {
                log("Location component error: ${e.message}")
            }
        } else {
            log("Location permission not granted")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("Location permission granted")
                if (::map.isInitialized && map.style != null) {
                    enableLocationComponent(map.style!!)
                }
            } else {
                log("Location permission denied")
            }
        }
    }

    private fun showDeviceSelectionDialog() {
        log("Scanning USB...")
        val devices = serialLocationManager.getAvailableDevices()
        if (devices.isEmpty()) {
            log("No devices found")
            Toast.makeText(this, "No USB serial devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = devices.map { "${it.manufacturerName} ${it.productName}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Device")
            .setItems(deviceNames) { _, which -> showBaudRateSelectionDialog(devices[which]) }
            .show()
    }

    private fun showBaudRateSelectionDialog(device: UsbDevice) {
        val baudRates = arrayOf("4800", "9600", "19200", "38400", "57600", "115200")
        AlertDialog.Builder(this)
            .setTitle("Select Baud Rate")
            .setItems(baudRates) { _, which ->
                pendingBaudRate = baudRates[which].toInt()
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (usbManager.hasPermission(device)) {
                    connectToDevice(device, pendingBaudRate)
                } else {
                    log("Requesting USB permission...")
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                    val permissionIntent = PendingIntent.getBroadcast(
                        this, 0, intent,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                    )
                    usbManager.requestPermission(device, permissionIntent)
                }
            }
            .show()
    }

    private fun connectToDevice(device: UsbDevice, baudRate: Int) {
        log("Connecting in background...")
        updateStatus("Connecting...")
        Thread {
            try {
                serialLocationManager.connect(device, baudRate)
            } catch (e: Exception) {
                log("Connection thread error: ${e.message}")
            }
        }.start()
    }

    override fun onLocationUpdate(location: SerialLocation) {
        if (!firstLocationReceived) {
            firstLocationReceived = true
            log("Fix Received!")
        }
        updateStatus("Fix: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}")

        val androidLocation = Location("gps").apply {
            latitude = location.latitude
            longitude = location.longitude
            location.altitude?.let { altitude = it }
            time = location.time
            accuracy = location.accuracy ?: 2.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
        }
        serialLocationEngine.updateLocation(androidLocation)
    }

    override fun onError(message: String) {
        log("SERIAL ERROR: $message")
        updateStatus("Serial Error")
    }

    override fun onConnected() {
        isSerialConnected = true
        runOnUiThread { connectSerialButton.text = "Disconnect" }
        log("Serial connected")
        updateStatus("Serial Connected")
    }

    override fun onDisconnected() {
        isSerialConnected = false
        firstLocationReceived = false
        runOnUiThread { connectSerialButton.text = "Connect Serial" }
        log("Serial disconnected")
        updateStatus("Serial Disconnected")
    }

    private fun downloadRegion() {
        // Implementation omitted for brevity in this simplified fix, can be added back once stable
        log("Download not implemented in this version")
    }

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { super.onPause(); if (::mapView.isInitialized) mapView.onPause() }
    override fun onStop() { super.onStop(); if (::mapView.isInitialized) mapView.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); if (::mapView.isInitialized) mapView.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        if (::serialLocationManager.isInitialized) serialLocationManager.release()
        if (::mapView.isInitialized) mapView.onDestroy()
    }
}
