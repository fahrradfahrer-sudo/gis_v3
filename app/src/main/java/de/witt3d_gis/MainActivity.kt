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
    private lateinit var progressBar: ProgressBar
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
                        device?.let {
                            serialLocationManager.connect(it, pendingBaudRate)
                        }
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

        // Custom Crash Handler to show info on screen
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e(TAG, "Uncaught exception in thread ${thread.name}\n$stackTrace")
            Handler(Looper.getMainLooper()).post {
                AlertDialog.Builder(this)
                    .setTitle("App Crashed")
                    .setMessage("Error: ${throwable.message}\n\nCheck Log Console for details.")
                    .setPositiveButton("OK", null)
                    .show()
                log("CRASH: ${throwable.message}\n$stackTrace")
            }
        }

        log("App Starting...")

        try {
            MapLibre.getInstance(this)
            log("MapLibre initialized")
        } catch (e: Exception) {
            log("MapLibre init error: ${e.message}")
        }

        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        mapView = findViewById(R.id.mapView)

        mapView.onCreate(savedInstanceState)

        updateStatus("Checking API Key...")
        if (MAPTILER_API_KEY == "YOUR_MAPTILER_API_KEY" || MAPTILER_API_KEY.isEmpty()) {
            log("ERROR: MapTiler API Key is not set!")
            AlertDialog.Builder(this)
                .setTitle("API Key Missing")
                .setMessage("Please edit MainActivity.kt and replace 'YOUR_MAPTILER_API_KEY' with your valid MapTiler API key.")
                .setCancelable(false)
                .setPositiveButton("OK", null)
                .show()
            updateStatus("Error: API Key Missing")
        } else {
            log("API Key found")
        }

        mapView.getMapAsync { map ->
            this.map = map
            log("Map object ready")
            loadStyle(mapStyles[0].second)
        }

        offlineManager = OfflineManager.getInstance(this)
        serialLocationManager = SerialLocationManager(this)
        serialLocationManager.listener = this
        serialLocationEngine = SerialLocationEngine()

        val downloadButton = findViewById<Button>(R.id.downloadButton)
        downloadButton.setOnClickListener {
            if (::map.isInitialized && !isDownloading) {
                downloadRegion()
            }
        }

        connectSerialButton = findViewById(R.id.connectSerialButton)
        connectSerialButton.setOnClickListener {
            if (isSerialConnected) {
                serialLocationManager.disconnect()
            } else {
                showDeviceSelectionDialog()
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
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

        checkLocationPermission()
    }

    private fun loadStyle(url: String) {
        log("Loading map style...")
        updateStatus("Loading Map Style...")
        try {
            map.setStyle(Style.Builder().fromUri(url)) { style ->
                log("Map style loaded successfully")
                updateStatus("Map Ready")
                enableLocationComponent(style)
            }
        } catch (e: Exception) {
            log("Error setting style: ${e.message}")
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
            logText.append(formattedMessage)
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
        } else {
            log("Location permissions already granted")
        }
    }

    private fun enableLocationComponent(style: Style) {
        log("Enabling Location Component...")
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
                log("LocationComponent activated with SerialLocationEngine")
            } catch (e: Exception) {
                log("Location error: ${e.message}")
                updateStatus("Location Component Error")
            }
        } else {
            log("WARNING: Permission not granted for LocationComponent")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                log("Location permission granted by user")
                if (::map.isInitialized && map.style != null) {
                    enableLocationComponent(map.style!!)
                }
            } else {
                log("Location permission denied by user")
                Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDeviceSelectionDialog() {
        log("Finding USB devices...")
        val devices = serialLocationManager.getAvailableDevices()
        if (devices.isEmpty()) {
            log("No USB serial devices found")
            Toast.makeText(this, "No USB serial devices found", Toast.LENGTH_SHORT).show()
            return
        }

        log("Found ${devices.size} USB devices")
        val deviceNames = devices.map { "${it.manufacturerName} ${it.productName} (${it.deviceName})" }.toTypedArray()
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select USB Device")
        builder.setItems(deviceNames) { _, which ->
            showBaudRateSelectionDialog(devices[which])
        }
        builder.show()
    }

    private fun showBaudRateSelectionDialog(device: UsbDevice) {
        val baudRates = arrayOf("4800", "9600", "19200", "38400", "57600", "115200")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select Baud Rate")
        builder.setItems(baudRates) { _, which ->
            try {
                pendingBaudRate = baudRates[which].toInt()
                log("Selected baud rate: $pendingBaudRate")
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (usbManager.hasPermission(device)) {
                    log("USB permission already granted, connecting...")
                    serialLocationManager.connect(device, pendingBaudRate)
                } else {
                    log("Requesting USB permission for ${device.deviceName}")
                    val intent = Intent(ACTION_USB_PERMISSION)
                    intent.setPackage(packageName)
                    val permissionIntent = PendingIntent.getBroadcast(
                        this, 0, intent,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                    )
                    usbManager.requestPermission(device, permissionIntent)
                }
            } catch (e: Exception) {
                log("Baud selection error: ${e.message}")
            }
        }
        builder.show()
    }

    override fun onLocationUpdate(location: SerialLocation) {
        if (!firstLocationReceived) {
            firstLocationReceived = true
            log("First location fix received!")
            updateStatus("Fix: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}")
        }
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
        connectSerialButton.text = "Disconnect"
        log("Serial connected successfully")
        updateStatus("Serial Connected")
    }

    override fun onDisconnected() {
        isSerialConnected = false
        firstLocationReceived = false
        connectSerialButton.text = "Connect Serial"
        log("Serial disconnected")
        updateStatus("Serial Disconnected")
    }

    private fun downloadRegion() {
        val styleUrl = map.style?.uri ?: return
        val bounds = map.projection.visibleRegion.latLngBounds
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl, bounds, map.cameraPosition.zoom, map.cameraPosition.zoom, resources.displayMetrics.density
        )
        val metadata: ByteArray = try {
            "Witt3D_GIS Offline Map".toByteArray(charset("UTF-8"))
        } catch (e: Exception) {
            log("Metadata error: ${e.message}")
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Downloading Offline Map")
        val view = layoutInflater.inflate(R.layout.progress_dialog, null)
        builder.setView(view)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
        builder.setCancelable(false)
        val dialog = builder.create()
        dialog.show()

        isDownloading = true
        offlineManager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                this@MainActivity.offlineRegion = offlineRegion
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val percentage = if (status.isRequiredResourceCountPrecise) {
                            (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                        } else 0
                        progressBar.progress = percentage
                        if (status.isComplete) {
                            dialog.dismiss()
                            isDownloading = false
                            log("Offline map downloaded")
                        }
                    }
                    override fun onError(error: OfflineRegionError) {
                        dialog.dismiss()
                        isDownloading = false
                        log("Offline error: ${error.reason}")
                    }
                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        dialog.dismiss()
                        isDownloading = false
                        log("Tile limit exceeded: $limit")
                    }
                })
            }
            override fun onError(error: String) {
                dialog.dismiss()
                isDownloading = false
                log("Region creation error: $error")
            }
        })
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        serialLocationManager.release()
        offlineRegion?.setObserver(null)
        mapView.onDestroy()
    }
}
