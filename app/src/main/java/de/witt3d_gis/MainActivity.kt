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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
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

class MainActivity : AppCompatActivity(), SerialLocationManager.LocationListener {
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var offlineManager: OfflineManager
    private var offlineRegion: OfflineRegion? = null
    private var isDownloading = false
    private lateinit var progressBar: ProgressBar

    private lateinit var serialLocationManager: SerialLocationManager
    private lateinit var serialLocationEngine: SerialLocationEngine
    private var isSerialConnected = false
    private var firstLocationReceived = false
    private lateinit var connectSerialButton: Button

    private val ACTION_USB_PERMISSION = "de.witt3d_gis.USB_PERMISSION"
    private val PERMISSION_REQUEST_LOCATION = 1001

    // Replace with your own MapTiler API key
    private val apiKey = "YOUR_MAPTILER_API_KEY"

    private val mapStyles = listOf(
        "MapTiler Basic" to "https://api.maptiler.com/maps/basic/style.json?key=$apiKey",
        "OSM Bright" to "https://api.maptiler.com/maps/bright/style.json?key=$apiKey",
        "Toner" to "https://api.maptiler.com/maps/toner/style.json?key=$apiKey"
    )

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
                        val baudRate = intent.getIntExtra("baudRate", 9600)
                        device?.let {
                            serialLocationManager.connect(it, baudRate)
                        }
                    } else {
                        Toast.makeText(context, "Permission denied for device $device", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            this.map = map
            map.setStyle(mapStyles[0].second) { style ->
                enableLocationComponent(style)
            }
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
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (::map.isInitialized) {
                    map.setStyle(mapStyles[position].second) { style ->
                        enableLocationComponent(style)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (apiKey == "YOUR_MAPTILER_API_KEY") {
            Toast.makeText(
                this,
                "Please replace 'YOUR_MAPTILER_API_KEY' with your own API key.",
                Toast.LENGTH_LONG
            ).show()
        }

        checkLocationPermission()
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_LOCATION
            )
        }
    }

    private fun enableLocationComponent(style: org.maplibre.android.maps.Style) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            map.locationComponent.apply {
                val options = LocationComponentActivationOptions.builder(this@MainActivity, style)
                    .locationEngine(serialLocationEngine)
                    .build()
                activateLocationComponent(options)
                isLocationComponentEnabled = true
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (::map.isInitialized && map.style != null) {
                    enableLocationComponent(map.style!!)
                }
            } else {
                Toast.makeText(this, "Location permission is required for map positioning", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDeviceSelectionDialog() {
        val devices = serialLocationManager.getAvailableDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "No USB serial devices found", Toast.LENGTH_SHORT).show()
            return
        }

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
            val baudRate = baudRates[which].toInt()
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            if (usbManager.hasPermission(device)) {
                serialLocationManager.connect(device, baudRate)
            } else {
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    putExtra("baudRate", baudRate)
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    intent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                usbManager.requestPermission(device, permissionIntent)
            }
        }
        builder.show()
    }

    override fun onLocationUpdate(location: SerialLocation) {
        if (!firstLocationReceived) {
            firstLocationReceived = true
            Toast.makeText(this, "Receiving data: ${location.latitude}, ${location.longitude}", Toast.LENGTH_SHORT).show()
        }
        val androidLocation = Location("gps").apply {
            latitude = location.latitude
            longitude = location.longitude
            location.altitude?.let { altitude = it }
            time = location.time
            accuracy = location.accuracy ?: 1.0f
        }
        serialLocationEngine.updateLocation(androidLocation)
    }

    override fun onError(message: String) {
        Toast.makeText(this, "Error: $message", Toast.LENGTH_SHORT).show()
    }

    override fun onConnected() {
        isSerialConnected = true
        connectSerialButton.text = "Disconnect"
        Toast.makeText(this, "Serial Connected", Toast.LENGTH_SHORT).show()
    }

    override fun onDisconnected() {
        isSerialConnected = false
        firstLocationReceived = false
        connectSerialButton.text = "Connect Serial"
        Toast.makeText(this, "Serial Disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun downloadRegion() {
        val styleUrl = map.style?.uri ?: return

        val bounds = map.projection.visibleRegion.latLngBounds
        val definition = OfflineTilePyramidRegionDefinition(
            styleUrl,
            bounds,
            map.cameraPosition.zoom,
            map.cameraPosition.zoom,
            resources.displayMetrics.density
        )

        val metadata: ByteArray
        try {
            metadata = "Witt3D_GIS Offline Map".toByteArray(charset("UTF-8"))
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Downloading Offline Map")
        val view = layoutInflater.inflate(R.layout.progress_dialog, null)
        builder.setView(view)
        progressBar = view.findViewById(R.id.progressBar)
        builder.setCancelable(false)
        val dialog = builder.create()
        dialog.show()

        isDownloading = true

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    this@MainActivity.offlineRegion = offlineRegion
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: OfflineRegionStatus) {
                            val percentage = if (status.isRequiredResourceCountPrecise) {
                                (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                            } else {
                                0
                            }
                            progressBar.progress = percentage
                            if (status.isComplete) {
                                dialog.dismiss()
                                isDownloading = false
                                Toast.makeText(
                                    this@MainActivity,
                                    "Offline map downloaded successfully",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            dialog.dismiss()
                            isDownloading = false
                            Toast.makeText(
                                this@MainActivity,
                                "Error downloading offline map: ${error.reason}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            dialog.dismiss()
                            isDownloading = false
                            Toast.makeText(
                                this@MainActivity,
                                "Mapbox tile count limit exceeded: $limit",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                }

                override fun onError(error: String) {
                    dialog.dismiss()
                    isDownloading = false
                    Toast.makeText(
                        this@MainActivity,
                        "Error creating offline region: $error",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        serialLocationManager.disconnect()
        offlineRegion?.setObserver(null)
        mapView.onDestroy()
    }
}
