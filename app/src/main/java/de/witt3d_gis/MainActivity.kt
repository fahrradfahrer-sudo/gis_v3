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
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.net.URL
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), SerialLocationManager.LocationListener {
    private val TAG = "MainActivity"
    private val apiKey = "YOUR_MAPTILER_API_KEY"

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var statusText: TextView
    private lateinit var fixStatusText: TextView
    private lateinit var satCountText: TextView
    private lateinit var connectSerialButton: Button
    private lateinit var followButton: Button
    private lateinit var settingsButton: Button
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: Button
    private lateinit var styleRadioGroup: RadioGroup
    private lateinit var wmsCheckbox: CheckBox

    private lateinit var serialLocationManager: SerialLocationManager
    private lateinit var serialLocationEngine: SerialLocationEngine
    private lateinit var ntripManager: NtripManager

    private var isSerialConnected = false
    private var isNtripActive = false
    private var isWmsEnabled = false
    private var pendingDevice: UsbDevice? = null
    private var isFirstFix = true

    private val ACTION_USB_PERMISSION = "de.witt3d_gis.USB_PERMISSION"
    private val PERMISSION_REQUEST_LOCATION = 1001

    private val mapStyles = listOf(
        "MapTiler Basic" to "https://api.maptiler.com/maps/basic/style.json?key=$apiKey",
        "OSM Bright" to "https://api.maptiler.com/maps/bright/style.json?key=$apiKey",
        "Toner" to "https://api.maptiler.com/maps/toner/style.json?key=$apiKey",
        "MapLibre Demo" to "https://demotiles.maplibre.org/style.json",
        "No Base Map" to "{\"version\": 8, \"sources\": {}, \"layers\": [{\"id\": \"background\", \"type\": \"background\", \"paint\": {\"background-color\": \"#FFFFFF\"}}]}"
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    pendingDevice?.let { connectToSerial(it) }
                } else {
                    updateStatus("USB Permission denied")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        fixStatusText = findViewById(R.id.fixStatusText)
        satCountText = findViewById(R.id.satCountText)
        mapView = findViewById(R.id.mapView)
        connectSerialButton = findViewById(R.id.connectSerialButton)
        followButton = findViewById(R.id.followButton)
        settingsButton = findViewById(R.id.settingsButton)
        drawerLayout = findViewById(R.id.drawerLayout)
        menuButton = findViewById(R.id.menuButton)

        val navView = findViewById<NavigationView>(R.id.navigationView)
        styleRadioGroup = navView.findViewById(R.id.styleRadioGroup)
        wmsCheckbox = navView.findViewById(R.id.wmsCheckbox)

        serialLocationManager = SerialLocationManager(this)
        serialLocationManager.listener = this
        serialLocationEngine = SerialLocationEngine()

        ntripManager = NtripManager()
        ntripManager.listener = object : NtripManager.NtripListener {
            override fun onRtcmData(data: ByteArray) {
                if (isSerialConnected) serialLocationManager.write(data)
            }
            override fun onError(message: String) { updateStatus("NTRIP: $message") }
            override fun onConnected() {
                updateStatus("NTRIP: Connected")
                isNtripActive = true
            }
            override fun onDisconnected() {
                updateStatus("NTRIP: Disconnected")
                isNtripActive = false
            }
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapObj ->
            this.map = mapObj
            mapView.addOnDidFailLoadingMapListener { error -> updateStatus("Map Error: $error") }
            mapObj.setMaxZoomPreference(25.5)
            findViewById<ScaleBarView>(R.id.scaleBar).setMap(mapObj)
            loadStyle(mapStyles[0].second)
        }

        connectSerialButton.setOnClickListener {
            if (isSerialConnected) {
                serialLocationManager.disconnect()
                ntripManager.disconnect()
            } else {
                showDeviceSelectionDialog()
            }
        }

        followButton.setOnClickListener {
            if (::map.isInitialized && map.locationComponent.isLocationComponentActivated) {
                map.locationComponent.cameraMode = CameraMode.TRACKING
                Toast.makeText(this, "Following location", Toast.LENGTH_SHORT).show()
            }
        }

        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        wmsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            isWmsEnabled = isChecked
            refreshWmsLayer()
        }

        findViewById<Button>(R.id.zoomInButton).setOnClickListener {
            if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomIn())
        }
        findViewById<Button>(R.id.zoomOutButton).setOnClickListener {
            if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomOut())
        }

        settingsButton.setOnClickListener { showSettingsDialog() }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupLayerMenu()

        checkLocationPermission()
    }

    private fun setupLayerMenu() {
        mapStyles.forEachIndexed { index, pair ->
            val radioButton = RadioButton(this).apply {
                id = View.generateViewId()
                text = pair.first
                if (index == 0) isChecked = true
            }
            styleRadioGroup.addView(radioButton)
        }

        styleRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            val radioButton = group.findViewById<RadioButton>(checkedId)
            val index = group.indexOfChild(radioButton)
            if (index >= 0 && index < mapStyles.size) {
                loadStyle(mapStyles[index].second)
            }
        }
    }

    private fun loadStyle(url: String) {
        updateStatus("Loading style...")
        if (url.startsWith("{")) {
            map.setStyle(Style.Builder().fromJson(url)) { style ->
                updateStatus("Empty Style Ready")
                enableLocationComponent(style)
                if (isWmsEnabled) refreshWmsLayer()
            }
        } else {
            map.setStyle(url) { style ->
                updateStatus("Map Ready")
                enableLocationComponent(style)
                if (isWmsEnabled) refreshWmsLayer()
            }
        }
    }

    private fun refreshWmsLayer() {
        val style = map.style ?: return
        style.removeLayer("wms-layer")
        style.removeSource("wms-source")

        if (!isWmsEnabled) return

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val wmsUrl = prefs.getString("wms_url", "") ?: ""

        if (wmsUrl.isEmpty()) {
            Toast.makeText(this, "Please set WMS URL in Settings", Toast.LENGTH_SHORT).show()
            isWmsEnabled = false
            wmsCheckbox.isChecked = false
            return
        }

        try {
            var finalWmsUrl = wmsUrl.trim()
            if (finalWmsUrl.contains("SERVICE=WMS", ignoreCase = true)) {
                if (finalWmsUrl.contains("REQUEST=GetCapabilities", ignoreCase = true)) {
                    finalWmsUrl = finalWmsUrl.replace("REQUEST=GetCapabilities", "REQUEST=GetMap", ignoreCase = true)
                }
                if (!finalWmsUrl.contains("BBOX", ignoreCase = true)) {
                    val separator = if (finalWmsUrl.contains("?")) "&" else "?"
                    // We use WMS 1.1.1 parameters by default as they are most standard for Tile overlays
                    // Using 512 width/height for better detail at high zoom
                    var params = "FORMAT=image/png&TRANSPARENT=TRUE&VERSION=1.1.1&SRS=EPSG:3857&WIDTH=512&HEIGHT=512&BBOX={bbox-epsg-3857}"

                    if (!finalWmsUrl.contains("REQUEST=", ignoreCase = true)) {
                        params += "&REQUEST=GetMap"
                    }
                    if (!finalWmsUrl.contains("STYLES=", ignoreCase = true)) {
                        params += "&STYLES="
                    }
                    if (!finalWmsUrl.contains("LAYERS=", ignoreCase = true)) {
                        val userLayers = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("wms_layers", "") ?: ""
                        if (userLayers.isNotEmpty()) {
                            params += "&LAYERS=$userLayers"
                        } else {
                            updateStatus("WMS Warning: No Layers set")
                        }
                    }

                    finalWmsUrl += separator + params
                }
            } else if (!finalWmsUrl.contains("{x}") && !finalWmsUrl.contains("{bbox-epsg-3857}")) {
                updateStatus("WMS Warning: URL missing {x} or BBOX")
            }

            Log.i(TAG, "WMS Final URL: $finalWmsUrl")
            updateStatus("Adding WMS...")

            // MapLibre expect tile URL with {x} {y} {z} or similar.
            val tileSet = TileSet("2.2.0", finalWmsUrl)
            val source = RasterSource("wms-source", tileSet, 512)
            style.addSource(source)

            val wmsLayer = RasterLayer("wms-layer", "wms-source")

            // Try to find a good place for the layer - ideally above the background but below labels
            val layers = style.layers
            var belowLayerId: String? = null
            for (layer in layers) {
                if (layer.id.contains("label", ignoreCase = true) || layer.id.contains("symbol", ignoreCase = true)) {
                    belowLayerId = layer.id
                    break
                }
            }

            if (belowLayerId != null) {
                style.addLayerBelow(wmsLayer, belowLayerId)
            } else {
                // If no labels found, add it to the top so it's definitely visible
                style.addLayer(wmsLayer)
            }
            updateStatus("WMS Layer Active")
        } catch (e: Exception) {
            Log.e(TAG, "WMS error: ${e.message}")
            updateStatus("WMS Error: ${e.message}")
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
                    val locationComponentOptions = LocationComponentOptions.builder(this)
                        .gpsDrawable(R.drawable.ic_crosshair)
                        .bearingDrawable(R.drawable.ic_crosshair)
                        .accuracyAlpha(0.0f) // Hide accuracy circle
                        .build()

                    val options = LocationComponentActivationOptions.builder(this@MainActivity, style)
                        .locationEngine(serialLocationEngine)
                        .useDefaultLocationEngine(false)
                        .locationComponentOptions(locationComponentOptions)
                        .build()
                    map.locationComponent.activateLocationComponent(options)
                }
                map.locationComponent.isLocationComponentEnabled = true
                map.locationComponent.cameraMode = CameraMode.TRACKING
                map.locationComponent.renderMode = RenderMode.COMPASS
            } catch (e: Exception) { Log.e(TAG, "LocComp error: ${e.message}") }
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val baudInput = EditText(this).apply { hint = "Baudrate"; setText(prefs.getString("baud", "115200")) }
        val hostInput = EditText(this).apply { hint = "NTRIP Host"; setText(prefs.getString("host", "")) }
        val portInput = EditText(this).apply { hint = "NTRIP Port"; setText(prefs.getString("port", "2101")) }
        val mountInput = EditText(this).apply { hint = "NTRIP Mount"; setText(prefs.getString("mount", "")) }
        val userInput = EditText(this).apply { hint = "NTRIP User"; setText(prefs.getString("user", "")) }
        val passInput = EditText(this).apply { hint = "NTRIP Pass"; setText(prefs.getString("pass", "")) }
        val wmsInput = EditText(this).apply { hint = "WMS URL"; setText(prefs.getString("wms_url", "")) }

        val wmsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wmsLayersInput = EditText(this).apply {
            hint = "WMS Layers (e.g. layer1,layer2)"
            setText(prefs.getString("wms_layers", ""))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val discoverButton = Button(this).apply {
            text = "Search"
            textSize = 10f
            setOnClickListener { discoverWmsLayers(wmsInput.text.toString(), wmsLayersInput) }
        }
        val clearWmsButton = Button(this).apply {
            text = "Clear"
            textSize = 10f
            setOnClickListener { wmsLayersInput.setText("") }
        }
        wmsRow.addView(wmsLayersInput)
        wmsRow.addView(discoverButton)
        wmsRow.addView(clearWmsButton)

        val importButton = Button(this).apply {
            text = "Import Coordinates (Name,Lat,Lon)"
            setOnClickListener { showImportDialog() }
        }

        layout.addView(baudInput); layout.addView(hostInput); layout.addView(portInput); layout.addView(mountInput); layout.addView(userInput); layout.addView(passInput); layout.addView(wmsInput); layout.addView(wmsRow); layout.addView(importButton)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save & Start NTRIP") { _, _ ->
                prefs.edit()
                    .putString("baud", baudInput.text.toString().trim())
                    .putString("host", hostInput.text.toString().trim())
                    .putString("port", portInput.text.toString().trim())
                    .putString("mount", mountInput.text.toString().trim())
                    .putString("user", userInput.text.toString().trim())
                    .putString("pass", passInput.text.toString().trim())
                    .putString("wms_url", wmsInput.text.toString().trim())
                    .putString("wms_layers", wmsLayersInput.text.toString().trim())
                    .apply()

                if (isSerialConnected) startNtripFromPrefs()
                if (isWmsEnabled) refreshWmsLayer()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Stop NTRIP") { _, _ -> ntripManager.disconnect() }
            .show()
    }

    private fun showDeviceSelectionDialog() {
        val devices = serialLocationManager.getAvailableDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "No USB devices found", Toast.LENGTH_SHORT).show()
            return
        }
        val names = devices.map { "${it.manufacturerName ?: ""} ${it.productName ?: "Serial"}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Device")
            .setItems(names) { _, i ->
                val device = devices[i]
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (usbManager.hasPermission(device)) {
                    connectToSerial(device)
                } else {
                    pendingDevice = device
                    val intent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                    val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
                    usbManager.requestPermission(device, pi)
                }
            }
            .show()
    }

    private fun discoverWmsLayers(baseUrl: String, targetInput: EditText) {
        if (baseUrl.isEmpty()) {
            Toast.makeText(this, "Enter WMS URL first", Toast.LENGTH_SHORT).show()
            return
        }
        updateStatus("Searching WMS Layers...")
        Thread {
            try {
                var url = baseUrl.trim()
                if (!url.contains("REQUEST=", ignoreCase = true)) {
                    val sep = if (url.contains("?")) "&" else "?"
                    url += "${sep}SERVICE=WMS&REQUEST=GetCapabilities"
                }

                val connection = URL(url).openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                val xml = connection.getInputStream().bufferedReader().use { it.readText() }

                // Very simple regex-based parser for <Layer> elements
                // Looking for <Name> and optionally <Title>
                val layerRegex = Regex("<Layer[^>]*>([\\s\\S]*?)</Layer>")
                val nameRegex = Regex("<Name>([^<]+)</Name>")
                val titleRegex = Regex("<Title>([^<]+)</Title>")

                val foundLayers = mutableListOf<Pair<String, String>>()
                layerRegex.findAll(xml).forEach { match ->
                    val content = match.groupValues[1]
                    val name = nameRegex.find(content)?.groupValues?.get(1)
                    val title = titleRegex.find(content)?.groupValues?.get(1) ?: name ?: ""
            // Filter out layers that don't have a Name (like group layers)
            if (name != null && name.isNotBlank()) {
                foundLayers.add(name.trim() to title.trim())
                    }
                }

                runOnUiThread {
                    if (foundLayers.isEmpty()) {
                        updateStatus("No Layers found")
                        Toast.makeText(this, "No layers found in XML", Toast.LENGTH_SHORT).show()
                    } else {
                        updateStatus("Found ${foundLayers.size} layers")
                        val names = foundLayers.map { "${it.second} (${it.first})" }.toTypedArray()
                        val selected = BooleanArray(foundLayers.size)

                        AlertDialog.Builder(this)
                            .setTitle("Select Layers")
                            .setMultiChoiceItems(names, selected) { _, which, isChecked ->
                                selected[which] = isChecked
                            }
                            .setPositiveButton("OK") { _, _ ->
                                val picked = foundLayers.filterIndexed { i, _ -> selected[i] }.joinToString(",") { it.first }
                                targetInput.setText(picked)
                            }
                            .show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error: ${e.message}")
                runOnUiThread { updateStatus("Search Error: ${e.message}") }
            }
        }.start()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "Point1, 50.123, 8.456\nPoint2, 50.456, 8.789"
            minLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle("Import Coordinates")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                importCoordinates(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importCoordinates(csv: String) {
        val features = mutableListOf<Feature>()
        csv.lines().forEach { line ->
            val parts = line.split(",").map { it.trim() }
            if (parts.size >= 3) {
                try {
                    val name = parts[0]
                    val lat = parts[1].toDouble()
                    val lon = parts[2].toDouble()
                    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
                    feature.addStringProperty("name", name)
                    features.add(feature)
                } catch (e: Exception) { Log.e(TAG, "CSV error: ${e.message}") }
            }
        }

        if (features.isNotEmpty()) {
            map.style?.let { style ->
                style.removeLayer("import-layer")
                style.removeSource("import-source")

                val source = GeoJsonSource("import-source", FeatureCollection.fromFeatures(features))
                style.addSource(source)

                val layer = SymbolLayer("import-layer", "import-source")
                layer.setProperties(
                    PropertyFactory.textField("{name}"),
                    PropertyFactory.textSize(14f),
                    PropertyFactory.textOffset(arrayOf(0f, 1f)),
                    PropertyFactory.textColor(Color.RED),
                    PropertyFactory.textHaloColor(Color.WHITE),
                    PropertyFactory.textHaloWidth(1f)
                )
                style.addLayer(layer)
                updateStatus("Imported ${features.size} points")
            }
        } else {
            Toast.makeText(this, "No valid coordinates found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectToSerial(device: UsbDevice) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val baud = prefs.getString("baud", "115200")?.toIntOrNull() ?: 115200
        updateStatus("Connecting...")
        Thread {
            try {
                serialLocationManager.connect(device, baud)
            } catch (e: Exception) { Log.e(TAG, "Connect error: ${e.message}") }
        }.start()
    }

    override fun onGgaReceived(sentence: String) {
        if (isNtripActive) ntripManager.sendGga(sentence)
    }

    override fun onLocationUpdate(location: SerialLocation) {
        runOnUiThread {
            fixStatusText.text = location.fixType ?: "No Fix"
            satCountText.text = "Sats: ${location.satellites ?: 0}"

            val androidLocation = Location("gps").apply {
                latitude = location.latitude
                longitude = location.longitude
                location.altitude?.let { altitude = it }
                time = location.time
                accuracy = location.accuracy ?: 2.0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
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
        startNtripFromPrefs()
    }

    private fun startNtripFromPrefs() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        if (host.isNotEmpty()) {
            val port = prefs.getString("port", "2101")?.toIntOrNull() ?: 2101
            val mount = prefs.getString("mount", "") ?: ""
            val user = prefs.getString("user", "") ?: ""
            val pass = prefs.getString("pass", "") ?: ""
            runOnUiThread {
                updateStatus("NTRIP: Connecting...")
                ntripManager.connect(host, port, mount, user, pass)
            }
        }
    }

    override fun onDisconnected() {
        isSerialConnected = false
        isNtripActive = false
        runOnUiThread { connectSerialButton.text = "Connect" }
        updateStatus("Disconnected")
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
        serialLocationManager.release()
        ntripManager.release()
        if (::mapView.isInitialized) mapView.onDestroy()
    }
}
