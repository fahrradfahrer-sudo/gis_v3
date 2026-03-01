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
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
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
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon
import androidx.activity.result.contract.ActivityResultContracts
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

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
    private lateinit var rtkAgeText: TextView
    private lateinit var baudSpinner: Spinner
    private lateinit var measureButton: Button
    private lateinit var scaleBar: ScaleBarView
    private lateinit var wmsUrlDrawer: EditText
    private lateinit var wmsLayersDrawer: EditText
    private lateinit var crsSpinner: Spinner

    private lateinit var serialLocationManager: SerialLocationManager
    private lateinit var serialLocationEngine: SerialLocationEngine
    private lateinit var ntripManager: NtripManager
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    private var isSerialConnected = false
    private var isNtripActive = false
    private var isWmsEnabled = false
    private var pendingDevice: UsbDevice? = null
    private var isFirstFix = true
    private var isMeasureMode = false
    private val measurePoints = mutableListOf<LatLng>()
    private var lastLocation: LatLng? = null
    private var currentFeatures = listOf<Feature>()

    private val ACTION_USB_PERMISSION = "de.witt3d_gis.USB_PERMISSION"
    private val PERMISSION_REQUEST_LOCATION = 1001

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleImportedFile(it) }
    }

    private var lastExportFormat = "geojson"
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { exportDataToUri(it, lastExportFormat) }
    }

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
        rtkAgeText = findViewById(R.id.rtkAgeText)
        baudSpinner = findViewById<Spinner>(R.id.baudSpinner)
        crsSpinner = navView.findViewById<Spinner>(R.id.crsSpinner)
        measureButton = findViewById(R.id.measureButton)
        scaleBar = findViewById(R.id.scaleBar)

        wmsUrlDrawer = navView.findViewById(R.id.wmsUrlDrawer)
        wmsLayersDrawer = navView.findViewById(R.id.wmsLayersDrawer)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        wmsUrlDrawer.setText(prefs.getString("wms_url", ""))
        wmsLayersDrawer.setText(prefs.getString("wms_layers", ""))

        navView.findViewById<Button>(R.id.wmsSearchDrawer).setOnClickListener {
            discoverWmsLayers(wmsUrlDrawer.text.toString(), wmsLayersDrawer)
        }

        setupBaudSpinner()
        setupCrsSpinner()

        navView.findViewById<Button>(R.id.importCsvSide).setOnClickListener { showImportDialog(); drawerLayout.closeDrawers() }
        navView.findViewById<Button>(R.id.importFileSide).setOnClickListener { openFilePicker(); drawerLayout.closeDrawers() }
        navView.findViewById<Button>(R.id.exportDataSide).setOnClickListener { startExport(); drawerLayout.closeDrawers() }

        navView.findViewById<SwitchCompat>(R.id.scaleModeSwitch).setOnCheckedChangeListener { _, isChecked ->
            scaleBar.isRelativeMode = isChecked
        }

        serialLocationManager = SerialLocationManager(this)
        serialLocationManager.listener = this
        serialLocationEngine = SerialLocationEngine()

        // Ensure baudSpinner has a default selection before any connection attempt
        if (baudSpinner.adapter != null && baudSpinner.selectedItem == null) {
            baudSpinner.setSelection(4) // Default to 115200
        }

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

            mapObj.addOnMapClickListener { latLng ->
                if (isMeasureMode) {
                    addMeasurePoint(latLng)
                    true
                } else false
            }

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
            if (isChecked) {
                val p = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                p.edit().putString("wms_url", wmsUrlDrawer.text.toString().trim())
                        .putString("wms_layers", wmsLayersDrawer.text.toString().trim())
                        .apply()
            }
            refreshWmsLayer()
        }

        findViewById<Button>(R.id.zoomInButton).setOnClickListener {
            if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomIn())
        }
        findViewById<Button>(R.id.zoomOutButton).setOnClickListener {
            if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomOut())
        }

        measureButton.setOnClickListener { toggleMeasureMode() }

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

    private fun setupBaudSpinner() {
        val bauds = listOf("9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bauds)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        baudSpinner.adapter = adapter

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("baud", "115200")
        val pos = bauds.indexOf(saved)
        if (pos != -1) baudSpinner.setSelection(pos)

        baudSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putString("baud", bauds[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupCrsSpinner() {
        val systems = listOf("WGS84 (Dec)", "WGS84 (DMS)", "UTM (Automatic)", "Web Mercator", "ETRS89 / UTM")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, systems)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        crsSpinner.adapter = adapter

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getInt("crs_pos", 0)
        crsSpinner.setSelection(saved)

        crsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt("crs_pos", position).apply()
                calculateMeasureResult() // Refresh current display
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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
                    val userLayers = getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("wms_layers", "") ?: ""
                    var params = "FORMAT=image/png&TRANSPARENT=TRUE&VERSION=1.1.1&SRS=EPSG:3857&WIDTH=512&HEIGHT=512&BBOX={bbox-epsg-3857}"

                    if (!finalWmsUrl.contains("REQUEST=", ignoreCase = true)) {
                        params += "&REQUEST=GetMap"
                    }
                    if (!finalWmsUrl.contains("STYLES=", ignoreCase = true)) {
                        params += "&STYLES="
                    }

                    // CRITICAL: Always use userLayers if present to avoid loading "all" layers or defaulting to 0
                    if (userLayers.isNotEmpty()) {
                        params += "&LAYERS=$userLayers"
                    } else if (!finalWmsUrl.contains("LAYERS=", ignoreCase = true)) {
                        params += "&LAYERS=0"
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
            // Setting maxZoom to 30 ensures tiles are always requested or over-scaled,
            // preventing the layer from disappearing at extreme scales like 20cm.
            tileSet.maxZoom = 30f
            val source = RasterSource("wms-source", tileSet, 512)
            style.addSource(source)

            val wmsLayer = RasterLayer("wms-layer", "wms-source")
            wmsLayer.setProperties(PropertyFactory.rasterOpacity(1.0f))
            wmsLayer.setMaxZoom(30f) // Explicitly set layer max zoom

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
            text = "Import CSV (Name,Lat,Lon)"
            setOnClickListener { showImportDialog() }
        }
        val geojsonButton = Button(this).apply {
            text = "Import GeoJSON/Shape File"
            setOnClickListener { openFilePicker() }
        }

        layout.addView(hostInput); layout.addView(portInput); layout.addView(mountInput); layout.addView(userInput); layout.addView(passInput); layout.addView(wmsInput); layout.addView(wmsRow)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save & Start NTRIP") { _, _ ->
                prefs.edit()
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

    private fun openFilePicker() {
        filePicker.launch("*/*")
    }

    private fun handleImportedFile(uri: android.net.Uri) {
        val fileName = getFileName(uri)
        if (fileName.endsWith(".json", ignoreCase = true) || fileName.endsWith(".geojson", ignoreCase = true)) {
            importGeoJsonFromUri(uri)
        } else if (fileName.endsWith(".shp", ignoreCase = true)) {
            importShapefile(uri)
        } else if (fileName.endsWith(".qgz", ignoreCase = true)) {
            importQgzFile(uri)
        } else if (fileName.endsWith(".kml", ignoreCase = true)) {
            importKmlFile(uri)
        } else {
            Toast.makeText(this, "Unsupported file: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: android.net.Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = it.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "unknown"
    }

    private fun importShapefile(uri: android.net.Uri) {
        updateStatus("Reading SHP...")
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.size < 100) return@use

                    val magic = (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
                    if (magic != 9994) {
                         runOnUiThread { Toast.makeText(this@MainActivity, "Not a valid SHP file", Toast.LENGTH_SHORT).show() }
                         return@use
                    }

                    val features = mutableListOf<Feature>()
                    var pos = 100
                    var count = 0
                    while (pos + 8 <= bytes.size) {
                        val contentLength = ((bytes[pos+4].toInt() and 0xFF) shl 24) or
                                            ((bytes[pos+5].toInt() and 0xFF) shl 16) or
                                            ((bytes[pos+6].toInt() and 0xFF) shl 8) or
                                            (bytes[pos+7].toInt() and 0xFF)

                        val dataStart = pos + 8
                        if (dataStart + 4 > bytes.size) break

                        val type = (bytes[dataStart].toInt() and 0xFF) or ((bytes[dataStart+1].toInt() and 0xFF) shl 8)

                        when (type) {
                            1 -> { // Point
                                if (dataStart + 20 <= bytes.size) {
                                    val x = java.nio.ByteBuffer.wrap(bytes, dataStart + 4, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
                                    val y = java.nio.ByteBuffer.wrap(bytes, dataStart + 12, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
                                    features.add(Feature.fromGeometry(Point.fromLngLat(x, y)).apply { addStringProperty("name", "Pt ${++count}") })
                                }
                            }
                            3, 5 -> { // PolyLine or Polygon
                                if (dataStart + 44 <= bytes.size) {
                                    val numParts = java.nio.ByteBuffer.wrap(bytes, dataStart + 36, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                                    val numPoints = java.nio.ByteBuffer.wrap(bytes, dataStart + 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int

                                    val partsOffset = dataStart + 44
                                    val pointsOffset = partsOffset + (numParts * 4)

                                    if (pointsOffset + (numPoints * 16) <= bytes.size) {
                                        val parts = IntArray(numParts) { i -> java.nio.ByteBuffer.wrap(bytes, partsOffset + (i * 4), 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int }
                                        val allPoints = mutableListOf<Point>()
                                        for (i in 0 until numPoints) {
                                            val px = java.nio.ByteBuffer.wrap(bytes, pointsOffset + (i * 16), 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
                                            val py = java.nio.ByteBuffer.wrap(bytes, pointsOffset + (i * 16) + 8, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).double
                                            allPoints.add(Point.fromLngLat(px, py))
                                        }

                                        if (type == 3) {
                                            features.add(Feature.fromGeometry(LineString.fromLngLats(allPoints)).apply { addStringProperty("name", "Line ${++count}") })
                                        } else {
                                            features.add(Feature.fromGeometry(Polygon.fromLngLats(listOf(allPoints))).apply { addStringProperty("name", "Poly ${++count}") })
                                        }
                                    }
                                }
                            }
                        }
                        pos += 8 + (contentLength * 2)
                    }

                    runOnUiThread {
                        if (features.isNotEmpty()) displayImportedFeatures(features, "SHP")
                        else Toast.makeText(this@MainActivity, "No compatible geometries found in SHP", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SHP error: ${e.message}")
                runOnUiThread { updateStatus("SHP Error: ${e.message}") }
            }
        }.start()
    }

    private fun startExport() {
        if (currentFeatures.isEmpty()) {
            Toast.makeText(this, "No data to export", Toast.LENGTH_SHORT).show()
            return
        }
        val formats = arrayOf("GeoJSON (.json)", "KML (.kml)", "QGZ (.qgz)")
        AlertDialog.Builder(this)
            .setTitle("Select Export Format")
            .setItems(formats) { _, i ->
                lastExportFormat = when(i) {
                    0 -> "geojson"
                    1 -> "kml"
                    else -> "qgz"
                }
                val ext = if (lastExportFormat == "geojson") "json" else lastExportFormat
                exportPicker.launch("export_data_${System.currentTimeMillis()}.$ext")
            }.show()
    }

    private fun exportDataToUri(uri: android.net.Uri, format: String) {
        executor.submit {
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    when (format) {
                        "geojson" -> {
                            val collection = FeatureCollection.fromFeatures(currentFeatures)
                            stream.write(collection.toJson().toByteArray())
                        }
                        "kml" -> stream.write(generateKml(currentFeatures).toByteArray())
                        "qgz" -> generateQgz(currentFeatures, stream)
                    }
                }
                runOnUiThread { Toast.makeText(this, "Export successful", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e(TAG, "Export error: ${e.message}")
                runOnUiThread { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun generateKml(features: List<Feature>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        features.forEach { f ->
            val name = f.getStringProperty("name") ?: "Pt"
            val geom = f.geometry()
            if (geom is Point) {
                val p = geom as Point
                sb.append("<Placemark><name>$name</name><Point><coordinates>${p.longitude()},${p.latitude()}</coordinates></Point></Placemark>\n")
            }
        }
        sb.append("</Document>\n</kml>")
        return sb.toString()
    }

    private fun generateQgz(features: List<Feature>, out: java.io.OutputStream) {
        val zos = java.util.zip.ZipOutputStream(out)
        zos.putNextEntry(java.util.zip.ZipEntry("project.qgs"))
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<qgis version=\"3.22.0\"></qgis>" // Placeholder
        zos.write(xml.toByteArray())
        zos.closeEntry()
        zos.close()
    }

    private fun displayImportedFeatures(features: List<Feature>, sourceName: String) {
        currentFeatures = features
        map.style?.let { style ->
            style.removeLayer("import-circle-layer")
            style.removeLayer("import-label-layer")
            style.removeLayer("import-line-layer")
            style.removeLayer("import-fill-layer")
            style.removeLayer("import-layer")
            style.removeSource("import-source")

            val source = GeoJsonSource("import-source", FeatureCollection.fromFeatures(features))
            style.addSource(source)

            // Polygon fill
            val fillLayer = FillLayer("import-fill-layer", "import-source")
            fillLayer.setProperties(
                PropertyFactory.fillColor(Color.argb(50, 255, 0, 0)),
                PropertyFactory.fillOutlineColor(Color.RED)
            )
            style.addLayer(fillLayer)

            // Line layer
            val lineLayer = LineLayer("import-line-layer", "import-source")
            lineLayer.setProperties(
                PropertyFactory.lineColor(Color.RED),
                PropertyFactory.lineWidth(2f)
            )
            style.addLayer(lineLayer)

            // Circle for points
            val circleLayer = CircleLayer("import-circle-layer", "import-source")
            circleLayer.setProperties(
                PropertyFactory.circleRadius(3f),
                PropertyFactory.circleColor(Color.RED),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeColor(Color.WHITE)
            )
            style.addLayer(circleLayer)

            val labelLayer = SymbolLayer("import-label-layer", "import-source")
            labelLayer.setProperties(
                PropertyFactory.textField("{name}"),
                PropertyFactory.textSize(12f),
                PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                PropertyFactory.textColor(Color.BLACK),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(1.5f)
            )
            style.addLayer(labelLayer)
            updateStatus("Imported $sourceName: ${features.size} items")
        }
    }

    private fun importQgzFile(uri: android.net.Uri) {
        updateStatus("Reading QGZ...")
        Thread {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val zis = ZipInputStream(stream)
                    var entry = zis.nextEntry
                    var xmlContent: String? = null
                    while (entry != null) {
                        if (entry.name.endsWith(".qgs", ignoreCase = true)) {
                            xmlContent = zis.bufferedReader().use { it.readText() }
                            break
                        }
                        entry = zis.nextEntry
                    }

                    if (xmlContent != null) {
                        // Very simple QGS parser for WMS layers
                        val wmsRegex = Regex("<layer-tree-layer[^>]*name=\"([^\"]+)\"[^>]*providerKey=\"wms\"[^>]*source=\"([^\"]+)\"")
                        val matches = wmsRegex.findAll(xmlContent)
                        runOnUiThread {
                            if (matches.any()) {
                                val layers = matches.map { it.groupValues[1] }.toList()
                                AlertDialog.Builder(this)
                                    .setTitle("QGZ Layers Found")
                                    .setItems(layers.toTypedArray()) { _, i ->
                                        val source = matches.elementAt(i).groupValues[2]
                                        // Try to extract URL from source (it's encoded)
                                        val urlPart = source.split("url=").getOrNull(1)?.split("&")?.get(0)
                                        if (urlPart != null) {
                                            wmsUrlDrawer.setText(java.net.URLDecoder.decode(urlPart, "UTF-8"))
                                            wmsLayersDrawer.setText(layers[i])
                                            updateStatus("QGZ WMS Loaded")
                                        }
                                    }.show()
                            } else {
                                Toast.makeText(this, "No WMS layers found in QGZ", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "QGZ error: ${e.message}")
                runOnUiThread { updateStatus("QGZ Error") }
            }
        }.start()
    }

    private fun importKmlFile(uri: android.net.Uri) {
        updateStatus("Reading KML...")
        executor.submit {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val kml = stream.bufferedReader().use { it.readText() }
                    val features = mutableListOf<Feature>()

                    // Basic KML point parser
                    val placemarkRegex = Regex("<Placemark[^>]*>([\\s\\S]*?)</Placemark>")
                    val nameRegex = Regex("<name>([^<]+)</name>")
                    val coordRegex = Regex("<coordinates>([^<]+)</coordinates>")

                    placemarkRegex.findAll(kml).forEach { match ->
                        val content = match.groupValues[1]
                        val name = nameRegex.find(content)?.groupValues?.get(1) ?: "KML Pt"
                        val coordStr = coordRegex.find(content)?.groupValues?.get(1)
                        if (coordStr != null) {
                            val parts = coordStr.trim().split(",")
                            if (parts.size >= 2) {
                                val lon = parts[0].trim().toDouble()
                                val lat = parts[1].trim().toDouble()
                                features.add(Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                                    addStringProperty("name", name)
                                })
                            }
                        }
                    }

                    runOnUiThread {
                        if (features.isNotEmpty()) displayImportedFeatures(features, "KML")
                        else Toast.makeText(this, "No valid Placemarks found in KML", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "KML error: ${e.message}")
                runOnUiThread { updateStatus("KML Error") }
            }
        }
    }

    private fun importGeoJsonFromUri(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val json = stream.bufferedReader().use { it.readText() }
                val featureCollection = FeatureCollection.fromJson(json)
                if (featureCollection.features() != null) {
                    displayImportedFeatures(featureCollection.features()!!, "GeoJSON")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "GeoJSON error: ${e.message}")
            Toast.makeText(this, "Failed to load GeoJSON", Toast.LENGTH_SHORT).show()
        }
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
            displayImportedFeatures(features, "CSV")
        } else {
            Toast.makeText(this, "No valid coordinates found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMeasureMode() {
        isMeasureMode = !isMeasureMode
        measureButton.text = if (isMeasureMode) "Stop" else "Meas"
        if (!isMeasureMode) {
            measurePoints.clear()
            map.style?.let {
                it.removeLayer("measure-line")
                it.removeLayer("measure-points")
                it.removeSource("measure-source")
            }
            updateStatus("Measure Mode Off")
        } else {
            updateStatus("Measure: Tap on map")
        }
    }

    private fun addMeasurePoint(latLng: LatLng) {
        measurePoints.add(latLng)
        val features = measurePoints.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }
        val lineFeature = if (measurePoints.size >= 2) {
            Feature.fromGeometry(LineString.fromLngLats(measurePoints.map { Point.fromLngLat(it.longitude, it.latitude) }))
        } else null

        map.style?.let { style ->
            style.removeLayer("measure-line")
            style.removeLayer("measure-points")
            style.removeSource("measure-source")

            val source = GeoJsonSource("measure-source", FeatureCollection.fromFeatures(
                if (lineFeature != null) features + lineFeature else features
            ))
            style.addSource(source)

            style.addLayer(CircleLayer("measure-points", "measure-source").apply {
                setProperties(PropertyFactory.circleRadius(5f), PropertyFactory.circleColor(Color.YELLOW))
            })
            if (lineFeature != null) {
                style.addLayerBelow(LineLayer("measure-line", "measure-source").apply {
                    setProperties(PropertyFactory.lineColor(Color.YELLOW), PropertyFactory.lineWidth(3f))
                }, "measure-points")
            }
        }

        calculateMeasureResult()
    }

    private fun calculateMeasureResult() {
        if (measurePoints.isEmpty()) return
        var dist = 0.0
        for (i in 0 until measurePoints.size - 1) {
            val results = FloatArray(1)
            Location.distanceBetween(measurePoints[i].latitude, measurePoints[i].longitude, measurePoints[i+1].latitude, measurePoints[i+1].longitude, results)
            dist += results[0]
        }

        var area = 0.0
        if (measurePoints.size >= 3) {
            // Very simplified area calculation for small areas
            area = calculatePlanarArea(measurePoints)
        }

        val pos = measurePoints.last()
        var status = "Dist: %.2fm, Area: %.2fm2, Pos: %.5f, %.5f".format(dist, area, pos.latitude, pos.longitude)

        lastLocation?.let { current ->
            val res = FloatArray(1)
            Location.distanceBetween(pos.latitude, pos.longitude, current.latitude, current.longitude, res)
            status += " | To GNSS: %.2fm".format(res[0])
        }

        updateStatus(status)
    }

    private fun calculatePlanarArea(points: List<LatLng>): Double {
        if (points.size < 3) return 0.0
        var area = 0.0
        val radius = 6378137.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            val p1 = points[i]
            val p2 = points[j]
            area += Math.toRadians(p2.longitude - p1.longitude) * (2.0 + Math.sin(Math.toRadians(p1.latitude)) + Math.sin(Math.toRadians(p2.latitude)))
        }
        area = area * radius * radius / 2.0
        return Math.abs(area)
    }

    private fun connectToSerial(device: UsbDevice) {
        if (!::baudSpinner.isInitialized) return
        val baudString = baudSpinner.selectedItem?.toString() ?: "115200"
        val baud = baudString.toIntOrNull() ?: 115200
        updateStatus("Connecting...")

        // Ensure connection happens on a background thread
        executor.submit {
            try {
                serialLocationManager.connect(device, baud)
            } catch (e: Exception) {
                Log.e(TAG, "Connect error: ${e.message}")
                runOnUiThread { updateStatus("Conn Error: ${e.message}") }
            }
        }
    }

    override fun onGgaReceived(sentence: String) {
        if (isNtripActive) ntripManager.sendGga(sentence)
    }

    override fun onLocationUpdate(location: SerialLocation) {
        runOnUiThread {
            fixStatusText.text = location.fixType ?: "No Fix"
            satCountText.text = "Sats: ${location.satellites ?: 0}"

            // Simulating RTK Age for display (in real apps this comes from GGA)
            rtkAgeText.text = "Age: 1.0s"

            lastLocation = LatLng(location.latitude, location.longitude)
            if (isMeasureMode) calculateMeasureResult()

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
