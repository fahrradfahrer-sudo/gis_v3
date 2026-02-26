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
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

class MainActivity : AppCompatActivity(), SerialLocationManager.LocationListener {
    private val TAG = "MainActivity"

    // --- IMPORTANT: ENTER YOUR MAPTILER API KEY HERE ---
    private val apiKey = "YOUR_MAPTILER_API_KEY"

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var statusText: TextView
    private lateinit var connectSerialButton: Button
    private lateinit var followButton: Button
    private lateinit var ntripButton: Button

    private lateinit var serialLocationManager: SerialLocationManager
    private lateinit var serialLocationEngine: SerialLocationEngine
    private lateinit var ntripManager: NtripManager

    private var isSerialConnected = false
    private var isNtripConnected = false
    private var pendingBaudRate: Int = 9600
    private var isFirstFix = true

    private val ACTION_USB_PERMISSION = "de.witt3d_gis.USB_PERMISSION"
    private val PERMISSION_REQUEST_LOCATION = 1001

    private val mapStyles = listOf(
        "MapLibre Demo" to "https://demotiles.maplibre.org/style.json",
        "MapTiler Basic" to "https://api.maptiler.com/maps/basic/style.json?key=$apiKey",
        "OSM Bright" to "https://api.maptiler.com/maps/bright/style.json?key=$apiKey",
        "Toner" to "https://api.maptiler.com/maps/toner/style.json?key=$apiKey"
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    device?.let { connectToDevice(it, pendingBaudRate) }
                } else {
                    updateStatus("USB Permission denied")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MapLibre BEFORE anything else
        MapLibre.getInstance(this)

        setContentView(R.layout.activity_main)

        // Find views - ENSURE NO downloadButton REFERENCE HERE
        statusText = findViewById(R.id.statusText)
        mapView = findViewById(R.id.mapView)
        connectSerialButton = findViewById(R.id.connectSerialButton)
        followButton = findViewById(R.id.followButton)
        ntripButton = findViewById(R.id.ntripButton)

        // Initialize managers
        serialLocationManager = SerialLocationManager(this)
        serialLocationManager.listener = this
        serialLocationEngine = SerialLocationEngine()

        ntripManager = NtripManager()
        ntripManager.listener = object : NtripManager.NtripListener {
            override fun onRtcmData(data: ByteArray) {
                if (isSerialConnected) serialLocationManager.write(data)
            }
            override fun onError(message: String) {
                updateStatus("NTRIP Error: $message")
            }
            override fun onConnected() {
                isNtripConnected = true
                runOnUiThread { ntripButton.text = "NTRIP Stop" }
                updateStatus("NTRIP Connected")
            }
            override fun onDisconnected() {
                isNtripConnected = false
                runOnUiThread { ntripButton.text = "NTRIP" }
                updateStatus("NTRIP Disconnected")
            }
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapObj ->
            this.map = mapObj

            mapView.addOnDidFailLoadingMapListener { error ->
                val masked = if (apiKey.length > 6) "${apiKey.take(3)}...${apiKey.takeLast(3)}" else apiKey
                Log.e(TAG, "Map Error (Key=$masked): $error")
                updateStatus("Map Error: $error (Key: $masked)")
            }

            // Always try to load the first style (Demo) to verify engine
            val initialUrl = mapStyles[0].second
            loadStyle(initialUrl)
        }

        connectSerialButton.setOnClickListener {
            if (isSerialConnected) serialLocationManager.disconnect() else showDeviceSelectionDialog()
        }

        followButton.setOnClickListener {
            if (::map.isInitialized && map.locationComponent.isLocationComponentActivated) {
                map.locationComponent.cameraMode = CameraMode.TRACKING
                Toast.makeText(this, "Following location", Toast.LENGTH_SHORT).show()
            }
        }

        ntripButton.setOnClickListener {
            if (isNtripConnected) ntripManager.disconnect() else showNtripSettingsDialog()
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        val styleSpinner = findViewById<Spinner>(R.id.styleSpinner)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mapStyles.map { it.first })
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
        updateStatus("Loading map...")
        map.setStyle(url) { style ->
            updateStatus("Map Ready")
            enableLocationComponent(style)
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText.text = "Status: $msg" }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), PERMISSION_REQUEST_LOCATION)
        }
    }

    private fun enableLocationComponent(style: Style) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (!map.locationComponent.isLocationComponentActivated) {
                    val options = LocationComponentActivationOptions.builder(this@MainActivity, style)
                        .locationEngine(serialLocationEngine)
                        .useDefaultLocationEngine(false)
                        .build()
                    map.locationComponent.activateLocationComponent(options)
                }
                map.locationComponent.isLocationComponentEnabled = true
                map.locationComponent.cameraMode = CameraMode.TRACKING
                map.locationComponent.renderMode = RenderMode.COMPASS
            } catch (e: Exception) {
                Log.e(TAG, "Location Component error", e)
            }
        }
    }

    private fun showNtripSettingsDialog() {
        val prefs = getSharedPreferences("ntrip_prefs", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val hostInput = EditText(this).apply { hint = "Host"; setText(prefs.getString("host", "")) }
        val portInput = EditText(this).apply { hint = "Port"; setText(prefs.getString("port", "2101")) }
        val mountInput = EditText(this).apply { hint = "Mountpoint"; setText(prefs.getString("mount", "")) }
        val userInput = EditText(this).apply { hint = "Username"; setText(prefs.getString("user", "")) }
        val passInput = EditText(this).apply { hint = "Password"; setText(prefs.getString("pass", "")) }

        layout.addView(hostInput); layout.addView(portInput); layout.addView(mountInput); layout.addView(userInput); layout.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("NTRIP Settings")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val host = hostInput.text.toString()
                val port = portInput.text.toString().toIntOrNull() ?: 2101
                val mount = mountInput.text.toString()
                val user = userInput.text.toString()
                val pass = passInput.text.toString()

                prefs.edit().putString("host", host).putString("port", port.toString()).putString("mount", mount).putString("user", user).putString("pass", pass).apply()
                ntripManager.connect(host, port, mount, user, pass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_LOCATION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (::map.isInitialized && map.style != null) enableLocationComponent(map.style!!)
        }
    }

    private fun showDeviceSelectionDialog() {
        val devices = serialLocationManager.getAvailableDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "No USB serial devices found", Toast.LENGTH_SHORT).show()
            return
        }
        val deviceNames = devices.map { "${it.manufacturerName ?: "Unknown"} ${it.productName ?: "Serial Device"}" }.toTypedArray()
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
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                    val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0))
                    usbManager.requestPermission(device, permissionIntent)
                }
            }
            .show()
    }

    private fun connectToDevice(device: UsbDevice, baudRate: Int) {
        updateStatus("Connecting...")
        Thread {
            try {
                serialLocationManager.connect(device, baudRate)
            } catch (e: Exception) {
                Log.e(TAG, "Thread Connect Error", e)
            }
        }.start()
    }

    // --- SerialLocationManager.LocationListener implementation ---

    override fun onGgaReceived(sentence: String) {
        if (isNtripConnected) ntripManager.sendGga(sentence)
    }

    override fun onLocationUpdate(location: SerialLocation) {
        updateStatus("Fix: ${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)}")
        val androidLocation = Location("gps").apply {
            latitude = location.latitude
            longitude = location.longitude
            location.altitude?.let { altitude = it }
            time = location.time
            accuracy = location.accuracy ?: 2.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        runOnUiThread {
            serialLocationEngine.updateLocation(androidLocation)
            if (isFirstFix && ::map.isInitialized) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 15.0))
                isFirstFix = false
            }
        }
    }

    override fun onError(message: String) {
        updateStatus("Serial Error")
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    override fun onConnected() {
        isSerialConnected = true
        runOnUiThread { connectSerialButton.text = "Disconnect" }
        updateStatus("Serial Connected")
    }

    override fun onDisconnected() {
        isSerialConnected = false
        runOnUiThread { connectSerialButton.text = "Connect" }
        updateStatus("Serial Disconnected")
    }

    // --- Lifecycle methods ---

    override fun onStart() { super.onStart(); if (::mapView.isInitialized) mapView.onStart() }
    override fun onResume() { super.onResume(); if (::mapView.isInitialized) mapView.onResume() }
    override fun onPause() { super.onPause(); if (::mapView.isInitialized) mapView.onPause() }
    override fun onStop() { super.onStop(); if (::mapView.isInitialized) mapView.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); if (::mapView.isInitialized) mapView.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); if (::mapView.isInitialized) mapView.onLowMemory() }
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        serialLocationManager.release()
        ntripManager.release()
        if (::mapView.isInitialized) mapView.onDestroy()
    }
}
