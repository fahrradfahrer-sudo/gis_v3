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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class WmsLayerConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var layers: String,
    var enabled: Boolean = true
)

class MainActivity : AppCompatActivity(), SerialLocationManager.LocationListener {
    private val TAG = "MainActivity"
    private val apiKey = "YOUR_MAPTILER_API_KEY"

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var statusText: TextView
    private lateinit var fixStatusText: TextView
    private lateinit var satCountText: TextView
    private lateinit var connectSerialButton: Button
    private lateinit var followSwitch: SwitchCompat
    private lateinit var settingsButton: Button
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: Button
    private lateinit var styleRadioGroup: RadioGroup
    private lateinit var rtkAgeText: TextView
    private lateinit var altText: TextView
    private lateinit var speedText: TextView
    private lateinit var baudSpinner: Spinner
    private lateinit var measureButton: Button
    private lateinit var drawPointButton: Button
    private lateinit var addPointAtPosButton: Button
    private lateinit var drawLineButton: Button
    private lateinit var drawPolyButton: Button
    private lateinit var editFeatureButton: Button
    private lateinit var clearDrawButton: Button
    private lateinit var snapSwitch: SwitchCompat
    private lateinit var distanceArrow: View
    private lateinit var distanceArrowText: TextView
    private lateinit var arrowIcon: View
    private lateinit var scaleBar: ScaleBarView
    private lateinit var crsSpinner: Spinner
    private lateinit var wmsLayerContainer: LinearLayout
    private lateinit var addWmsButton: Button

    private lateinit var serialLocationManager: SerialLocationManager
    private lateinit var serialLocationEngine: SerialLocationEngine
    private lateinit var ntripManager: NtripManager
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    private var isSerialConnected = false
    private var isNtripActive = false
    private var wmsLayers = mutableListOf<WmsLayerConfig>()
    private var pendingDevice: UsbDevice? = null
    private var isFirstFix = true
    private var isMeasureMode = false
    private var drawingMode = 0 // 0: None, 1: Point, 2: Line, 3: Poly, 4: Edit
    private val measurePoints = mutableListOf<LatLng>()
    private val drawPoints = mutableListOf<LatLng>()
    private var lastLocation: LatLng? = null
    private var lastManualMapInteraction = 0L
    private var isRecentlyInteracted = false
    private var targetIconRotation = 0f
    private var currentFeatures = mutableListOf<Feature>()
    private var movingFeature: Feature? = null
    private var movingVertexIndex: Int = -1

    private val ACTION_USB_PERMISSION = "de.witt3d_gis.USB_PERMISSION"
    private val PERMISSION_REQUEST_LOCATION = 1001

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { handleImportedFile(it) }
    }

    private var lastExportFormat = "geojson"
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { exportDataToUri(it, lastExportFormat) }
    }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { exportShpToFolder(it) }
    }

    private val mapStyles = listOf(
        "MapTiler Basic" to "https://api.maptiler.com/maps/basic/style.json?key=$apiKey",
        "OSM Bright" to "https://api.maptiler.com/maps/bright/style.json?key=$apiKey",
        "Toner" to "https://api.maptiler.com/maps/toner/style.json?key=$apiKey",
        "MapLibre Demo" to "https://demotiles.maplibre.org/style.json",
        "OpenStreetMap" to "{\"version\": 8, \"sources\": {\"osm\": {\"type\": \"raster\", \"tiles\": [\"https://a.tile.openstreetmap.org/{z}/{x}/{y}.png\"], \"tileSize\": 256}}, \"layers\": [{\"id\": \"osm\", \"type\": \"raster\", \"source\": \"osm\"}]}",
        "No Base Map" to "{\"version\": 8, \"sources\": {\"empty\": {\"type\": \"vector\", \"tiles\": []}}, \"layers\": [{\"id\": \"background\", \"type\": \"background\", \"paint\": {\"background-color\": \"#FFFFFF\"}}]}"
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

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e(TAG, "Uncaught Exception in ${thread.name}\n$stackTrace")
            runOnUiThread {
                Toast.makeText(this, "Crash: ${throwable.message}", Toast.LENGTH_LONG).show()
                updateStatus("CRASH: ${throwable.message} (See Logs)")
            }
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration()
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        fixStatusText = findViewById(R.id.fixStatusText)
        satCountText = findViewById(R.id.satCountText)
        mapView = findViewById(R.id.mapView)
        connectSerialButton = findViewById(R.id.connectSerialButton)
        followSwitch = findViewById(R.id.followSwitch)
        settingsButton = findViewById(R.id.settingsButton)
        drawerLayout = findViewById(R.id.drawerLayout)
        menuButton = findViewById(R.id.menuButton)

        val navView = findViewById<NavigationView>(R.id.navigationView)
        styleRadioGroup = navView.findViewById(R.id.styleRadioGroup)
        rtkAgeText = findViewById(R.id.rtkAgeText)
        altText = findViewById(R.id.altText)
        speedText = findViewById(R.id.speedText)
        baudSpinner = findViewById<Spinner>(R.id.baudSpinner)
        crsSpinner = navView.findViewById<Spinner>(R.id.crsSpinner)
        measureButton = findViewById(R.id.measureButton)
        drawPointButton = findViewById(R.id.drawPointButton)
        addPointAtPosButton = findViewById(R.id.addPointAtPosButton)
        drawLineButton = findViewById(R.id.drawLineButton)
        drawPolyButton = findViewById(R.id.drawPolyButton)
        editFeatureButton = findViewById(R.id.editFeatureButton)
        clearDrawButton = findViewById(R.id.clearDrawButton)
        snapSwitch = findViewById(R.id.snapSwitch)
        distanceArrow = findViewById(R.id.distanceArrow)
        distanceArrowText = findViewById(R.id.distanceArrowText)
        arrowIcon = findViewById(R.id.arrowIcon)
        scaleBar = findViewById(R.id.scaleBar)
        wmsLayerContainer = navView.findViewById(R.id.wmsLayerContainer)
        addWmsButton = navView.findViewById(R.id.addWmsButton)

        loadWmsConfigs()

        addWmsButton.setOnClickListener { showWmsEditDialog(null) }

        setupBaudSpinner()
        setupCrsSpinner()

        navView.findViewById<Button>(R.id.importCsvSide).setOnClickListener { showImportDialog(); drawerLayout.closeDrawers() }
        navView.findViewById<Button>(R.id.importFileSide).setOnClickListener { openFilePicker(); drawerLayout.closeDrawers() }
        navView.findViewById<Button>(R.id.exportDataSide).setOnClickListener { startExport(); drawerLayout.closeDrawers() }

        val languageButton = Button(this).apply {
            text = "Language / Sprache"
            textSize = 10f
            setOnClickListener { showLanguageDialog() }
        }
        navView.findViewById<LinearLayout>(R.id.nav_content_layout).addView(languageButton)

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

            mapObj.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    lastManualMapInteraction = SystemClock.elapsedRealtime()
                }
            }

            mapView.addOnDidFailLoadingMapListener { error -> updateStatus("Map Error: $error") }
            mapObj.setMaxZoomPreference(32.0)

            // Center on Dirmstein for start
            mapObj.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(49.56333, 8.24750), 15.0))

            mapObj.addOnMapClickListener { latLng ->
                var finalLatLng = latLng
                if (snapSwitch.isChecked && (drawingMode in 1..3 || movingFeature != null)) {
                    finalLatLng = findSnapPoint(latLng) ?: latLng
                }

                if (movingFeature != null) {
                    finishMovingFeature(finalLatLng)
                    return@addOnMapClickListener true
                }
                when {
                    isMeasureMode -> {
                        addMeasurePoint(finalLatLng)
                        true
                    }
                    drawingMode in 1..3 -> {
                        handleDrawingClick(finalLatLng)
                        true
                    }
                    drawingMode == 4 -> {
                        handleEditClick(finalLatLng)
                        true
                    }
                    else -> false
                }
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

        followSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (::map.isInitialized && map.locationComponent.isLocationComponentActivated) {
                if (isChecked) {
                    // map.locationComponent.cameraMode = CameraMode.TRACKING // Using custom follow logic instead
                    map.locationComponent.cameraMode = CameraMode.NONE
                    Toast.makeText(this, "Smooth Follow ON", Toast.LENGTH_SHORT).show()
                } else {
                    map.locationComponent.cameraMode = CameraMode.NONE
                }
            }
        }

        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<Button>(R.id.zoomInButton).setOnClickListener {
            if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomIn())
        }
        findViewById<Button>(R.id.zoomOutButton).setOnClickListener {
            if (::map.isInitialized) map.animateCamera(CameraUpdateFactory.zoomOut())
        }

        measureButton.setOnClickListener { toggleMeasureMode() }
        drawPointButton.setOnClickListener { setDrawingMode(1) }
        addPointAtPosButton.setOnClickListener { addPointAtCurrentPosition() }
        drawLineButton.setOnClickListener { setDrawingMode(2) }
        drawPolyButton.setOnClickListener { setDrawingMode(3) }
        editFeatureButton.setOnClickListener { setDrawingMode(4) }
        clearDrawButton.setOnClickListener { clearDrawing() }

        settingsButton.setOnClickListener { showSettingsDialog() }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        setupLayerMenu()

        checkLocationPermission()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let {
                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                if (usbManager.hasPermission(it)) {
                    connectToSerial(it)
                } else {
                    pendingDevice = it
                    val piIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
                    val pi = PendingIntent.getBroadcast(this, 0, piIntent, PendingIntent.FLAG_MUTABLE)
                    usbManager.requestPermission(it, pi)
                }
            }
        }
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
        updateStatus(getString(R.string.loading_style))
        if (url.startsWith("{")) {
            map.setStyle(Style.Builder().fromJson(url)) { style ->
                updateStatus(getString(R.string.style_ready))
                onStyleLoaded(style)
            }
        } else {
            map.setStyle(url) { style ->
                updateStatus(getString(R.string.style_ready))
                onStyleLoaded(style)
            }
        }
    }

    private fun onStyleLoaded(style: Style) {
        try {
            style.addImage("measure-target", ContextCompat.getDrawable(this, R.drawable.ic_measure_target)!!)
            style.addImage("measure-target-last", ContextCompat.getDrawable(this, R.drawable.ic_measure_target)!!.apply { setTint(Color.RED) })
            enableLocationComponent(style)
            refreshWmsLayers()
            refreshFeatureLayer()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStyleLoaded: ${e.message}")
        }
    }

    private fun refreshWmsLayers() {
        val style = map.style ?: return

        // First, remove all existing WMS layers/sources
        style.layers.filter { it.id.startsWith("wms-layer-") }.forEach { style.removeLayer(it) }
        style.sources.filter { it.id.startsWith("wms-source-") }.forEach { style.removeSource(it) }

        // Find reference layer for z-ordering
        var belowLayerId: String? = null
        for (layer in style.layers) {
            if (layer.id.contains("label", ignoreCase = true) || layer.id.contains("symbol", ignoreCase = true)) {
                belowLayerId = layer.id
                break
            }
        }

        // Add enabled layers in reverse order (so the first in list is on top)
        wmsLayers.filter { it.enabled }.reversed().forEachIndexed { index, config ->
            try {
                var finalWmsUrl = config.url.trim()
                if (finalWmsUrl.contains("SERVICE=WMS", ignoreCase = true)) {
                    if (finalWmsUrl.contains("REQUEST=GetCapabilities", ignoreCase = true)) {
                        finalWmsUrl = finalWmsUrl.replace("REQUEST=GetCapabilities", "REQUEST=GetMap", ignoreCase = true)
                    }
                    if (!finalWmsUrl.contains("BBOX", ignoreCase = true)) {
                        val separator = if (finalWmsUrl.contains("?")) "&" else "?"
                        var params = "FORMAT=image/png&TRANSPARENT=TRUE&VERSION=1.1.1&SRS=EPSG:3857&WIDTH=512&HEIGHT=512&BBOX={bbox-epsg-3857}"
                        if (!finalWmsUrl.contains("REQUEST=", ignoreCase = true)) params += "&REQUEST=GetMap"
                        if (!finalWmsUrl.contains("STYLES=", ignoreCase = true)) params += "&STYLES="
                        if (config.layers.isNotEmpty()) params += "&LAYERS=${config.layers}"
                        else if (!finalWmsUrl.contains("LAYERS=", ignoreCase = true)) params += "&LAYERS=0"
                        finalWmsUrl += separator + params
                    }
                }

                val tileSet = TileSet("2.2.0", finalWmsUrl)
                tileSet.maxZoom = 24f
                val source = RasterSource("wms-source-${config.id}", tileSet, 512)
                style.addSource(source)

                val wmsLayer = RasterLayer("wms-layer-${config.id}", "wms-source-${config.id}")
                wmsLayer.setProperties(
                    PropertyFactory.rasterOpacity(1.0f),
                    PropertyFactory.rasterResampling(org.maplibre.android.style.layers.Property.RASTER_RESAMPLING_NEAREST)
                )
                wmsLayer.setMaxZoom(40f)

                if (belowLayerId != null) {
                    style.addLayerBelow(wmsLayer, belowLayerId)
                    // Ensure the next one is below this one
                    belowLayerId = wmsLayer.id
                } else {
                    style.addLayer(wmsLayer)
                }
            } catch (e: Exception) {
                Log.e(TAG, "WMS error for ${config.name}: ${e.message}")
            }
        }
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText.text = getString(R.string.status_prefix, msg) }
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
                        .foregroundDrawable(R.drawable.ic_crosshair)
                        .gpsDrawable(R.drawable.ic_crosshair)
                        .bearingDrawable(R.drawable.ic_crosshair)
                        .backgroundDrawable(R.drawable.transparent_drawable)
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

    private fun showLanguageDialog() {
        val languages = arrayOf("English", "Deutsch")
        AlertDialog.Builder(this)
            .setTitle("Select Language / Sprache wählen")
            .setItems(languages) { _, i ->
                val locale = if (i == 0) "en" else "de"
                setLocale(locale)
            }
            .show()
    }

    private fun setLocale(lang: String) {
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration()
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("language", lang).apply()

        // Restart activity to apply changes
        val intent = intent
        finish()
        startActivity(intent)
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }

        val hostInput = EditText(this).apply { hint = getString(R.string.ntrip_host); setText(prefs.getString("host", "")) }
        val portInput = EditText(this).apply { hint = getString(R.string.ntrip_port); setText(prefs.getString("port", "2101")) }
        val mountInput = EditText(this).apply { hint = getString(R.string.ntrip_mount); setText(prefs.getString("mount", "")) }
        val userInput = EditText(this).apply { hint = getString(R.string.ntrip_user); setText(prefs.getString("user", "")) }
        val passInput = EditText(this).apply { hint = getString(R.string.ntrip_pass); setText(prefs.getString("pass", "")) }

        val manualGgaCheck = CheckBox(this).apply { text = getString(R.string.manual_position); isChecked = prefs.getBoolean("manual_gga_en", false) }

        var defLat = prefs.getString("manual_lat", "") ?: ""
        var defLon = prefs.getString("manual_lon", "") ?: ""
        var defAlt = prefs.getString("manual_alt", "") ?: ""

        if (defLat.isEmpty() || defLon.isEmpty()) {
            lastLocation?.let {
                defLat = "%.7f".format(it.latitude)
                defLon = "%.7f".format(it.longitude)
                defAlt = lastAlt.toString()
            } ?: run {
                defLat = "49.56333"
                defLon = "8.24750"
                defAlt = "108.0"
            }
        }

        val manualLatInput = EditText(this).apply { hint = "Lat"; setText(defLat); isEnabled = manualGgaCheck.isChecked; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED }
        val manualLonInput = EditText(this).apply { hint = "Lon"; setText(defLon); isEnabled = manualGgaCheck.isChecked; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED }
        val manualAltInput = EditText(this).apply { hint = "Alt (m)"; setText(defAlt); isEnabled = manualGgaCheck.isChecked; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }

        manualGgaCheck.setOnCheckedChangeListener { _, isChecked ->
            manualLatInput.isEnabled = isChecked
            manualLonInput.isEnabled = isChecked
            manualAltInput.isEnabled = isChecked
        }

        val wmsInput = EditText(this).apply { hint = getString(R.string.wms_url); setText(prefs.getString("wms_url", "")) }

        val wmsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val wmsLayersInput = EditText(this).apply {
            hint = getString(R.string.wms_layers_hint)
            setText(prefs.getString("wms_layers", ""))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val discoverButton = Button(this).apply {
            text = getString(R.string.search)
            textSize = 10f
            setOnClickListener { discoverWmsLayers(wmsInput.text.toString(), wmsLayersInput) }
        }
        val clearWmsButton = Button(this).apply {
            text = getString(R.string.clear)
            textSize = 10f
            setOnClickListener { wmsLayersInput.setText("") }
        }
        wmsRow.addView(wmsLayersInput)
        wmsRow.addView(discoverButton)
        wmsRow.addView(clearWmsButton)

        val importButton = Button(this).apply {
            text = getString(R.string.import_csv_hint)
            textSize = 10f
            setOnClickListener { showImportDialog() }
        }
        val geojsonButton = Button(this).apply {
            text = getString(R.string.import_gis_hint)
            textSize = 10f
            setOnClickListener { openFilePicker() }
        }

        layout.addView(hostInput); layout.addView(portInput); layout.addView(mountInput); layout.addView(userInput); layout.addView(passInput)
        layout.addView(manualGgaCheck); layout.addView(manualLatInput); layout.addView(manualLonInput); layout.addView(manualAltInput)
        layout.addView(wmsInput); layout.addView(wmsRow)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(layout)
            .setPositiveButton(R.string.save_start_ntrip) { _, _ ->
                val latStr = manualLatInput.text.toString().trim()
                val lonStr = manualLonInput.text.toString().trim()
                val altStr = manualAltInput.text.toString().trim()

                prefs.edit()
                    .putString("host", hostInput.text.toString().trim())
                    .putString("port", portInput.text.toString().trim())
                    .putString("mount", mountInput.text.toString().trim())
                    .putString("user", userInput.text.toString().trim())
                    .putString("pass", passInput.text.toString().trim())
                    .putBoolean("manual_gga_en", manualGgaCheck.isChecked)
                    .putString("manual_lat", latStr)
                    .putString("manual_lon", lonStr)
                    .putString("manual_alt", altStr)
                    .apply()

                if (manualGgaCheck.isChecked) {
                    val gga = constructGga(latStr.toDoubleOrNull() ?: 0.0, lonStr.toDoubleOrNull() ?: 0.0, altStr.toDoubleOrNull() ?: 0.0)
                    ntripManager.setManualGga(gga)
                } else {
                    ntripManager.setManualGga(null)
                }

                if (isSerialConnected) startNtripFromPrefs()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.stop_ntrip) { _, _ -> ntripManager.disconnect() }
            .show()
    }

    private fun loadWmsConfigs() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("wms_layers_json", null)
        if (json != null) {
            val type = object : TypeToken<List<WmsLayerConfig>>() {}.type
            wmsLayers = Gson().fromJson<List<WmsLayerConfig>>(json, type).toMutableList()
        }
        updateWmsLayerUI()
    }

    private fun saveWmsConfigs() {
        val json = Gson().toJson(wmsLayers)
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putString("wms_layers_json", json)
            .apply()
        refreshWmsLayers()
    }

    private fun updateWmsLayerUI() {
        wmsLayerContainer.removeAllViews()
        wmsLayers.forEachIndexed { index, config ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }

            val cb = CheckBox(this).apply {
                isChecked = config.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    config.enabled = isChecked
                    saveWmsConfigs()
                }
            }

            val title = TextView(this).apply {
                text = config.name
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showWmsEditDialog(config) }
            }

            val upBtn = Button(this).apply {
                text = "↑"
                setPadding(0,0,0,0)
                layoutParams = LinearLayout.LayoutParams(60, 60)
                isEnabled = index > 0
                setOnClickListener {
                    java.util.Collections.swap(wmsLayers, index, index - 1)
                    saveWmsConfigs()
                    updateWmsLayerUI()
                }
            }

            val downBtn = Button(this).apply {
                text = "↓"
                setPadding(0,0,0,0)
                layoutParams = LinearLayout.LayoutParams(60, 60)
                isEnabled = index < wmsLayers.size - 1
                setOnClickListener {
                    java.util.Collections.swap(wmsLayers, index, index + 1)
                    saveWmsConfigs()
                    updateWmsLayerUI()
                }
            }

            val deleteBtn = Button(this).apply {
                text = "X"
                setPadding(0,0,0,0)
                layoutParams = LinearLayout.LayoutParams(60, 60)
                setOnClickListener {
                    wmsLayers.removeAt(index)
                    saveWmsConfigs()
                    updateWmsLayerUI()
                }
            }

            row.addView(cb); row.addView(title); row.addView(upBtn); row.addView(downBtn); row.addView(deleteBtn)
            wmsLayerContainer.addView(row)
        }
    }

    private fun showWmsEditDialog(config: WmsLayerConfig?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val nameInput = EditText(this).apply { hint = "Name"; setText(config?.name ?: "") }
        val urlInput = EditText(this).apply { hint = "WMS URL"; setText(config?.url ?: "") }
        val layersInput = EditText(this).apply { hint = "Layers"; setText(config?.layers ?: "") }
        val searchBtn = Button(this).apply {
            text = "Search Layers"
            setOnClickListener { discoverWmsLayers(urlInput.text.toString(), layersInput) }
        }

        layout.addView(nameInput); layout.addView(urlInput); layout.addView(layersInput); layout.addView(searchBtn)

        AlertDialog.Builder(this)
            .setTitle(if (config == null) "Add WMS" else "Edit WMS")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                if (config == null) {
                    wmsLayers.add(WmsLayerConfig(name = nameInput.text.toString(), url = urlInput.text.toString(), layers = layersInput.text.toString()))
                } else {
                    config.name = nameInput.text.toString()
                    config.url = urlInput.text.toString()
                    config.layers = layersInput.text.toString()
                }
                saveWmsConfigs()
                updateWmsLayerUI()
            }
            .setNegativeButton("Cancel", null)
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
        val formats = arrayOf("GeoJSON (.json)", "KML (.kml)", "QGZ (.qgz)", "Shapefile (Folder: .shp/.dbf/.shx)")
        AlertDialog.Builder(this)
            .setTitle("Select Export Format")
            .setItems(formats) { _, i ->
                lastExportFormat = when(i) {
                    0 -> "geojson"
                    1 -> "kml"
                    2 -> "qgz"
                    else -> "shp"
                }
                if (lastExportFormat == "shp") {
                    folderPicker.launch(null)
                } else {
                    val ext = if (lastExportFormat == "geojson") "json" else lastExportFormat
                    exportPicker.launch("export_data_${System.currentTimeMillis()}.$ext")
                }
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

    private fun exportShpToFolder(folderUri: android.net.Uri) {
        executor.submit {
            try {
                val pickedDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, folderUri) ?: return@submit
                val baseName = "export_${System.currentTimeMillis()}"

                val points = currentFeatures.filter { it.geometry() is Point }
                val lines = currentFeatures.filter { it.geometry() is LineString }
                val polys = currentFeatures.filter { it.geometry() is Polygon }

                if (points.isNotEmpty()) {
                    writeShpComponent(pickedDir, "${baseName}_points", 1, points)
                }
                if (lines.isNotEmpty()) {
                    writeShpComponent(pickedDir, "${baseName}_lines", 3, lines)
                }
                if (polys.isNotEmpty()) {
                    writeShpComponent(pickedDir, "${baseName}_polys", 5, polys)
                }

                runOnUiThread { Toast.makeText(this, "Shapefile components exported to folder", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                Log.e(TAG, "SHP Folder Export error: ${e.message}")
                runOnUiThread { Toast.makeText(this, "Folder Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun writeShpComponent(dir: androidx.documentfile.provider.DocumentFile, name: String, type: Int, features: List<Feature>) {
        val shpFile = dir.createFile("application/octet-stream", "$name.shp")
        contentResolver.openOutputStream(shpFile!!.uri)?.use { it.write(writeShpBytes(features, type)) }

        val shxFile = dir.createFile("application/octet-stream", "$name.shx")
        contentResolver.openOutputStream(shxFile!!.uri)?.use { it.write(writeShxBytes(features, type)) }

        val dbfFile = dir.createFile("application/octet-stream", "$name.dbf")
        contentResolver.openOutputStream(dbfFile!!.uri)?.use { it.write(writeDbfBytes(features)) }
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
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<qgis version=\"3.22.0\">\n" +
                  "<projectlayers>\n" +
                  "<maplayer type=\"vector\" name=\"Exported Features\">\n" +
                  "</maplayer>\n" +
                  "</projectlayers>\n" +
                  "</qgis>"
        zos.write(xml.toByteArray())
        zos.closeEntry()
        zos.close()
    }


    private fun writeShpBytes(features: List<Feature>, type: Int): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(2 * 1024 * 1024).order(java.nio.ByteOrder.BIG_ENDIAN)
        bb.putInt(9994); bb.putInt(0); bb.putInt(0); bb.putInt(0); bb.putInt(0); bb.putInt(0)
        val sizePos = bb.position(); bb.putInt(0)
        bb.putInt(1000); bb.putInt(type)

        val bounds = calculateBounds(features)
        bb.putDouble(bounds[0]); bb.putDouble(bounds[1]); bb.putDouble(bounds[2]); bb.putDouble(bounds[3])
        bb.putDouble(0.0); bb.putDouble(0.0); bb.putDouble(0.0); bb.putDouble(0.0)

        features.forEachIndexed { i, f ->
            bb.order(java.nio.ByteOrder.BIG_ENDIAN)
            bb.putInt(i + 1)
            val contentSizePos = bb.position(); bb.putInt(0)

            bb.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val startPos = bb.position()
            writeGeometry(bb, f.geometry(), type)
            val endPos = bb.position()
            val contentLenWords = (endPos - startPos) / 2

            bb.order(java.nio.ByteOrder.BIG_ENDIAN)
            bb.putInt(contentSizePos, contentLenWords)
        }

        val finalSize = bb.position()
        bb.putInt(sizePos, finalSize / 2)
        return bb.array().copyOf(finalSize)
    }

    private fun writeShxBytes(features: List<Feature>, type: Int): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(1024 * 1024).order(java.nio.ByteOrder.BIG_ENDIAN)
        bb.putInt(9994); bb.putInt(0); bb.putInt(0); bb.putInt(0); bb.putInt(0); bb.putInt(0)
        val sizePos = bb.position(); bb.putInt(0)
        bb.putInt(1000); bb.putInt(type)

        val bounds = calculateBounds(features)
        bb.putDouble(bounds[0]); bb.putDouble(bounds[1]); bb.putDouble(bounds[2]); bb.putDouble(bounds[3])
        bb.putDouble(0.0); bb.putDouble(0.0); bb.putDouble(0.0); bb.putDouble(0.0)

        var offsetWords = 50
        features.forEach { f ->
            val tempBb = java.nio.ByteBuffer.allocate(1024 * 64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            writeGeometry(tempBb, f.geometry(), type)
            val contentLenWords = tempBb.position() / 2
            bb.putInt(offsetWords)
            bb.putInt(contentLenWords)
            offsetWords += 4 + contentLenWords
        }

        val finalSize = bb.position()
        bb.putInt(sizePos, finalSize / 2)
        return bb.array().copyOf(finalSize)
    }

    private fun writeDbfBytes(features: List<Feature>): ByteArray {
        val headerSize = 32 + 32 + 1
        val recordSize = 1 + 50
        val bb = java.nio.ByteBuffer.allocate(headerSize + (features.size * recordSize) + 1).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        bb.put(0x03.toByte()); bb.put(24.toByte()); bb.put(3.toByte()); bb.put(1.toByte())
        bb.putInt(features.size); bb.putShort(headerSize.toShort()); bb.putShort(recordSize.toShort())
        bb.position(bb.position() + 20)

        val nameField = "NAME".padEnd(11, '\u0000').toByteArray()
        bb.put(nameField); bb.put('C'.toByte()); bb.position(bb.position() + 4)
        bb.put(50.toByte()); bb.put(0.toByte()); bb.position(bb.position() + 14)
        bb.put(0x0D.toByte())

        features.forEach { f ->
            bb.put(0x20.toByte())
            val name = (f.getStringProperty("name") ?: "").take(50).padEnd(50, ' ')
            bb.put(name.toByteArray(Charsets.US_ASCII))
        }
        bb.put(0x1A.toByte())
        return bb.array().copyOf(bb.position())
    }

    private fun calculateBounds(features: List<Feature>): DoubleArray {
        var minX = 180.0; var minY = 90.0; var maxX = -180.0; var maxY = -90.0
        features.forEach { f ->
            val geom = f.geometry()
            val pts = when(geom) {
                is Point -> listOf(geom)
                is LineString -> geom.coordinates()
                is Polygon -> geom.coordinates().flatten()
                else -> emptyList()
            }
            pts.forEach { p ->
                minX = Math.min(minX, p.longitude()); minY = Math.min(minY, p.latitude())
                maxX = Math.max(maxX, p.longitude()); maxY = Math.max(maxY, p.latitude())
            }
        }
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    private fun writeGeometry(bb: java.nio.ByteBuffer, geom: org.maplibre.geojson.Geometry?, type: Int) {
        bb.putInt(type)
        when (type) {
            1 -> { // Point
                val p = geom as Point
                bb.putDouble(p.longitude()); bb.putDouble(p.latitude())
            }
            3, 5 -> { // Polyline, Polygon
                val pts = if (type == 3) (geom as LineString).coordinates() else (geom as Polygon).coordinates().flatten()
                var minX = 180.0; var minY = 90.0; var maxX = -180.0; var maxY = -90.0
                pts.forEach { p ->
                    minX = Math.min(minX, p.longitude()); minY = Math.min(minY, p.latitude())
                    maxX = Math.max(maxX, p.longitude()); maxY = Math.max(maxY, p.latitude())
                }
                bb.putDouble(minX); bb.putDouble(minY); bb.putDouble(maxX); bb.putDouble(maxY)
                bb.putInt(1) // NumParts
                bb.putInt(pts.size) // NumPoints
                bb.putInt(0) // Parts[0]
                pts.forEach { p -> bb.putDouble(p.longitude()); bb.putDouble(p.latitude()) }
            }
        }
    }

    private fun java.nio.ByteBuffer.putInt(pos: Int, value: Int) {
        val old = position()
        position(pos)
        putInt(value)
        position(old)
    }

    private fun displayImportedFeatures(features: List<Feature>, sourceName: String) {
        currentFeatures = features.toMutableList()
        refreshFeatureLayer()
        updateStatus("Imported $sourceName: ${features.size} items")
    }

    private fun refreshFeatureLayer() {
        map.style?.let { style ->
            style.removeLayer("import-circle-layer")
            style.removeLayer("import-label-layer")
            style.removeLayer("import-line-layer")
            style.removeLayer("import-fill-layer")
            style.removeLayer("import-layer")
            style.removeSource("import-source")

            val source = GeoJsonSource("import-source", FeatureCollection.fromFeatures(currentFeatures))
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
                PropertyFactory.textField(
                    org.maplibre.android.style.expressions.Expression.coalesce(
                        org.maplibre.android.style.expressions.Expression.get("notes"),
                        org.maplibre.android.style.expressions.Expression.get("name"),
                        org.maplibre.android.style.expressions.Expression.literal("")
                    )
                ),
                PropertyFactory.textSize(12f),
                PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                PropertyFactory.textColor(Color.BLACK),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(1.5f)
            )
            style.addLayer(labelLayer)
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
                                            val url = java.net.URLDecoder.decode(urlPart, "UTF-8")
                                            val name = layers[i]
                                            wmsLayers.add(WmsLayerConfig(name = name, url = url, layers = name))
                                            saveWmsConfigs()
                                            updateWmsLayerUI()
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
                        val name = nameRegex.find(content)?.groupValues?.get(1) ?: "KML"

                        // Parse Points
                        coordRegex.find(content)?.groupValues?.get(1)?.let { coordStr ->
                            val parts = coordStr.trim().split(Regex("\\s+"))
                            if (parts.size == 1) {
                                val latLon = parts[0].split(",")
                                if (latLon.size >= 2) {
                                    features.add(Feature.fromGeometry(Point.fromLngLat(latLon[0].trim().toDouble(), latLon[1].trim().toDouble())).apply { addStringProperty("name", name) })
                                }
                            } else if (parts.size > 1) {
                                val pts = parts.mapNotNull {
                                    val ll = it.split(",")
                                    if (ll.size >= 2) Point.fromLngLat(ll[0].trim().toDouble(), ll[1].trim().toDouble()) else null
                                }
                                if (pts.isNotEmpty()) {
                                    if (content.contains("<LineString")) {
                                        features.add(Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addStringProperty("name", name) })
                                    } else if (content.contains("<Polygon")) {
                                        features.add(Feature.fromGeometry(Polygon.fromLngLats(listOf(pts))).apply { addStringProperty("name", name) })
                                    }
                                }
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

    private fun setDrawingMode(mode: Int) {
        if (drawingMode == mode) {
            if (drawingMode == 2 && drawPoints.size >= 2) finishLine()
            else if (drawingMode == 3 && drawPoints.size >= 3) finishPoly()
            else drawingMode = 0
        } else {
            if (drawingMode == 2 && drawPoints.size >= 2) finishLine()
            else if (drawingMode == 3 && drawPoints.size >= 3) finishPoly()
            drawingMode = mode
        }
        updateDrawingButtons()
        if (drawingMode != 0) {
            isMeasureMode = false
            measureButton.text = "Meas"
            measurePoints.clear()
            map.style?.let {
                it.removeLayer("measure-line")
                it.removeLayer("measure-points")
                it.removeSource("measure-source")
            }
        }
    }

    private fun updateDrawingButtons() {
        val ralBlue = Color.parseColor("#00538E")

        drawPointButton.setBackgroundColor(if (drawingMode == 1) ralBlue else Color.WHITE)
        drawPointButton.setTextColor(if (drawingMode == 1) Color.WHITE else Color.BLACK)

        drawLineButton.setBackgroundColor(if (drawingMode == 2) ralBlue else Color.WHITE)
        drawLineButton.setTextColor(if (drawingMode == 2) Color.WHITE else Color.BLACK)

        drawPolyButton.setBackgroundColor(if (drawingMode == 3) ralBlue else Color.WHITE)
        drawPolyButton.setTextColor(if (drawingMode == 3) Color.WHITE else Color.BLACK)

        editFeatureButton.setBackgroundColor(if (drawingMode == 4) ralBlue else Color.WHITE)
        editFeatureButton.setTextColor(if (drawingMode == 4) Color.WHITE else Color.BLACK)

        when(drawingMode) {
            1 -> updateStatus(getString(R.string.status_drawing_pt))
            2 -> updateStatus(getString(R.string.status_drawing_line))
            3 -> updateStatus(getString(R.string.status_drawing_poly))
            4 -> updateStatus(getString(R.string.status_edit))
            else -> updateStatus(getString(R.string.status_drawing_off))
        }
    }

    private fun addPointAtCurrentPosition() {
        val loc = lastLocation
        if (loc == null) {
            Toast.makeText(this, getString(R.string.no_gnss_pos), Toast.LENGTH_SHORT).show()
            return
        }
        val notesInput = EditText(this).apply { hint = getString(R.string.notes) }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_pt_gnss)
            .setView(notesInput)
            .setPositiveButton(R.string.save) { _, _ ->
                val f = Feature.fromGeometry(Point.fromLngLat(loc.longitude, loc.latitude))
                f.addStringProperty("notes", notesInput.text.toString())
                currentFeatures.add(f)
                refreshFeatureLayer()
                Toast.makeText(this, "Point added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleDrawingClick(latLng: LatLng) {
        when(drawingMode) {
            1 -> {
                val notesInput = EditText(this).apply { hint = getString(R.string.notes) }
                AlertDialog.Builder(this)
                    .setTitle(R.string.draw_pt)
                    .setView(notesInput)
                    .setPositiveButton(R.string.save) { _, _ ->
                        val f = Feature.fromGeometry(Point.fromLngLat(latLng.longitude, latLng.latitude))
                        f.addStringProperty("notes", notesInput.text.toString())
                        currentFeatures.add(f)
                        refreshFeatureLayer()
                        drawingMode = 0
                        updateDrawingButtons()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            2, 3 -> {
                drawPoints.add(latLng)
                updateTempDrawing()
            }
        }
    }

    private fun updateTempDrawing() {
        val style = map.style ?: return
        style.removeLayer("temp-draw-layer")
        style.removeSource("temp-draw-source")
        if (drawPoints.isEmpty()) return

        val features = mutableListOf<Feature>()
        drawPoints.forEach { features.add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
        if (drawPoints.size >= 2) {
            features.add(Feature.fromGeometry(LineString.fromLngLats(drawPoints.map { Point.fromLngLat(it.longitude, it.latitude) })))
        }

        val source = GeoJsonSource("temp-draw-source", FeatureCollection.fromFeatures(features))
        style.addSource(source)
        style.addLayer(LineLayer("temp-draw-layer", "temp-draw-source").apply {
            setProperties(PropertyFactory.lineColor(Color.BLUE), PropertyFactory.lineWidth(2f))
        })
    }

    private fun finishLine() {
        if (drawPoints.size >= 2) {
            val notesInput = EditText(this).apply { hint = getString(R.string.notes) }
            AlertDialog.Builder(this)
                .setTitle(R.string.draw_line)
                .setView(notesInput)
                .setPositiveButton(R.string.save) { _, _ ->
                    val f = Feature.fromGeometry(LineString.fromLngLats(drawPoints.map { Point.fromLngLat(it.longitude, it.latitude) }))
                    f.addStringProperty("notes", notesInput.text.toString())
                    currentFeatures.add(f)
                    drawPoints.clear()
                    updateTempDrawing()
                    refreshFeatureLayer()
                    drawingMode = 0
                    updateDrawingButtons()
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    drawPoints.clear()
                    updateTempDrawing()
                    drawingMode = 0
                    updateDrawingButtons()
                }
                .show()
        } else {
            drawPoints.clear()
            updateTempDrawing()
            drawingMode = 0
            updateDrawingButtons()
        }
    }

    private fun finishPoly() {
        if (drawPoints.size >= 3) {
            val notesInput = EditText(this).apply { hint = getString(R.string.notes) }
            AlertDialog.Builder(this)
                .setTitle(R.string.draw_poly)
                .setView(notesInput)
                .setPositiveButton(R.string.save) { _, _ ->
                    val pts = drawPoints.map { Point.fromLngLat(it.longitude, it.latitude) }.toMutableList()
                    pts.add(pts[0]) // Close
                    val f = Feature.fromGeometry(Polygon.fromLngLats(listOf(pts)))
                    f.addStringProperty("notes", notesInput.text.toString())
                    currentFeatures.add(f)
                    drawPoints.clear()
                    updateTempDrawing()
                    refreshFeatureLayer()
                    drawingMode = 0
                    updateDrawingButtons()
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    drawPoints.clear()
                    updateTempDrawing()
                    drawingMode = 0
                    updateDrawingButtons()
                }
                .show()
        } else {
            drawPoints.clear()
            updateTempDrawing()
            drawingMode = 0
            updateDrawingButtons()
        }
    }

    private fun handleEditClick(latLng: LatLng) {
        // Find nearest feature
        var nearest: Feature? = null
        var minDist = 20.0 // 20m tolerance for tap

        currentFeatures.forEach { f ->
            val geom = f.geometry()
            when (geom) {
                is Point -> {
                    val p = geom as Point
                    val res = FloatArray(1)
                    Location.distanceBetween(latLng.latitude, latLng.longitude, p.latitude(), p.longitude(), res)
                    if (res[0] < minDist) {
                        minDist = res[0].toDouble()
                        nearest = f
                    }
                }
                is LineString -> {
                    val ls = geom as LineString
                    val pts = ls.coordinates()
                    for (i in 0 until pts.size - 1) {
                        val dist = distToSegment(latLng, pts[i], pts[i+1])
                        if (dist < minDist) {
                            minDist = dist
                            nearest = f
                        }
                    }
                }
                is Polygon -> {
                    val poly = geom as Polygon
                    val outer = poly.coordinates().firstOrNull() ?: return@forEach
                    // Check if inside or near boundary
                    if (isPointInPolygon(latLng, outer)) {
                        minDist = 0.0 // Direct hit
                        nearest = f
                    } else {
                        for (i in 0 until outer.size - 1) {
                            val dist = distToSegment(latLng, outer[i], outer[i+1])
                            if (dist < minDist) {
                                minDist = dist
                                nearest = f
                            }
                        }
                    }
                }
            }
        }

        nearest?.let { f ->
            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 10)
            }

            val notesInput = EditText(this).apply {
                setText(f.getStringProperty("notes") ?: f.getStringProperty("name") ?: "")
                hint = getString(R.string.notes)
            }
            dialogView.addView(notesInput)

            val buttonRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val buttonRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            val geom = f.geometry()
            var vertexIndex = -1
            if (geom is LineString) {
                val coords = geom.coordinates()
                var minVDist = 20.0
                coords.forEachIndexed { i, p ->
                    val res = FloatArray(1)
                    Location.distanceBetween(latLng.latitude, latLng.longitude, p.latitude(), p.longitude(), res)
                    if (res[0] < minVDist) {
                        minVDist = res[0].toDouble()
                        vertexIndex = i
                    }
                }
            } else if (geom is Polygon) {
                val outer = geom.coordinates().firstOrNull()
                var minVDist = 20.0
                outer?.forEachIndexed { i, p ->
                    val res = FloatArray(1)
                    Location.distanceBetween(latLng.latitude, latLng.longitude, p.latitude(), p.longitude(), res)
                    if (res[0] < minVDist) {
                        minVDist = res[0].toDouble()
                        vertexIndex = i
                    }
                }
            }

            val alertDialog = AlertDialog.Builder(this)
                .setTitle(R.string.edit)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .create()

            val saveBtn = Button(this).apply {
                text = getString(R.string.save)
                setOnClickListener {
                    f.addStringProperty("notes", notesInput.text.toString())
                    refreshFeatureLayer()
                    Toast.makeText(this@MainActivity, getString(R.string.point_added), Toast.LENGTH_SHORT).show()
                    alertDialog.dismiss()
                }
            }
            val deleteBtn = Button(this).apply {
                text = getString(R.string.delete)
                setOnClickListener {
                    currentFeatures.remove(f)
                    refreshFeatureLayer()
                    alertDialog.dismiss()
                }
            }
            val moveBtn = Button(this).apply {
                text = getString(R.string.move)
                setOnClickListener {
                    movingFeature = f
                    movingVertexIndex = vertexIndex
                    updateStatus(getString(R.string.moving_status))
                    alertDialog.dismiss()
                }
            }
            val moveByBtn = Button(this).apply {
                text = getString(R.string.move_by)
                setOnClickListener {
                    alertDialog.dismiss()
                    showMoveByDistanceDialog(f)
                }
            }

            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            buttonRow1.addView(saveBtn, lp)
            buttonRow1.addView(deleteBtn, lp)
            buttonRow2.addView(moveBtn, lp)
            buttonRow2.addView(moveByBtn, lp)

            dialogView.addView(buttonRow1)
            dialogView.addView(buttonRow2)

            alertDialog.show()
        } ?: Toast.makeText(this, getString(R.string.no_feature_nearby), Toast.LENGTH_SHORT).show()
    }

    private fun showMoveByDistanceDialog(f: Feature) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val distInput = EditText(this).apply {
            hint = getString(R.string.distance_m)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val bearingInput = EditText(this).apply {
            hint = getString(R.string.bearing_deg)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        layout.addView(distInput); layout.addView(bearingInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.move_by_dist)
            .setView(layout)
            .setPositiveButton(R.string.move) { _, _ ->
                val dist = distInput.text.toString().toDoubleOrNull() ?: 0.0
                val bearing = bearingInput.text.toString().toDoubleOrNull() ?: 0.0
                moveFeatureByDistance(f, dist, bearing)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun moveFeatureByDistance(f: Feature, distance: Double, bearing: Double) {
        val geom = f.geometry()
        val bearingRad = Math.toRadians(bearing)
        val R = 6378137.0 // Earth radius in meters

        fun projectPoint(p: Point): Point {
            val lat1 = Math.toRadians(p.latitude())
            val lon1 = Math.toRadians(p.longitude())
            val lat2 = Math.asin(Math.sin(lat1) * Math.cos(distance / R) +
                    Math.cos(lat1) * Math.sin(distance / R) * Math.cos(bearingRad))
            val lon2 = lon1 + Math.atan2(Math.sin(bearingRad) * Math.sin(distance / R) * Math.cos(lat1),
                    Math.cos(distance / R) - Math.sin(lat1) * Math.sin(lat2))
            return Point.fromLngLat(Math.toDegrees(lon2), Math.toDegrees(lat2))
        }

        when (geom) {
            is Point -> {
                currentFeatures.remove(f)
                val newF = Feature.fromGeometry(projectPoint(geom))
                f.properties()?.entrySet()?.forEach { newF.addProperty(it.key, it.value) }
                currentFeatures.add(newF)
            }
            is LineString -> {
                val newCoords = geom.coordinates().map { projectPoint(it) }
                currentFeatures.remove(f)
                val newF = Feature.fromGeometry(LineString.fromLngLats(newCoords))
                f.properties()?.entrySet()?.forEach { newF.addProperty(it.key, it.value) }
                currentFeatures.add(newF)
            }
            is Polygon -> {
                val newRings = geom.coordinates().map { ring -> ring.map { projectPoint(it) } }
                currentFeatures.remove(f)
                val newF = Feature.fromGeometry(Polygon.fromLngLats(newRings))
                f.properties()?.entrySet()?.forEach { newF.addProperty(it.key, it.value) }
                currentFeatures.add(newF)
            }
        }
        refreshFeatureLayer()
    }

    private fun finishMovingFeature(newLatLng: LatLng) {
        val f = movingFeature ?: return
        val geom = f.geometry()
        val newPoint = Point.fromLngLat(newLatLng.longitude, newLatLng.latitude)

        when (geom) {
            is Point -> {
                currentFeatures.remove(f)
                val newF = Feature.fromGeometry(newPoint)
                f.properties()?.entrySet()?.forEach { newF.addProperty(it.key, it.value) }
                currentFeatures.add(newF)
            }
            is LineString -> {
                val coords = geom.coordinates().toMutableList()
                if (movingVertexIndex != -1) {
                    coords[movingVertexIndex] = newPoint
                    currentFeatures.remove(f)
                    val newF = Feature.fromGeometry(LineString.fromLngLats(coords))
                    f.properties()?.entrySet()?.forEach { newF.addProperty(it.key, it.value) }
                    currentFeatures.add(newF)
                }
            }
            is Polygon -> {
                val rings = geom.coordinates().toMutableList()
                val outer = rings[0].toMutableList()
                if (movingVertexIndex != -1) {
                    outer[movingVertexIndex] = newPoint
                    // If moving start/end of ring, update both
                    if (movingVertexIndex == 0) outer[outer.size - 1] = newPoint
                    if (movingVertexIndex == outer.size - 1) outer[0] = newPoint

                    rings[0] = outer
                    currentFeatures.remove(f)
                    val newF = Feature.fromGeometry(Polygon.fromLngLats(rings))
                    f.properties()?.entrySet()?.forEach { newF.addProperty(it.key, it.value) }
                    currentFeatures.add(newF)
                }
            }
        }
        movingFeature = null
        movingVertexIndex = -1
        refreshFeatureLayer()
        updateDrawingButtons()
    }

    private fun clearDrawing() {
        currentFeatures.clear()
        drawPoints.clear()
        updateTempDrawing()
        refreshFeatureLayer()
        updateStatus(getString(R.string.data_cleared))
    }

    private fun distToSegment(p: LatLng, s1: Point, s2: Point): Double {
        val results = FloatArray(1)
        // This is a rough approximation in meters
        Location.distanceBetween(s1.latitude(), s1.longitude(), s2.latitude(), s2.longitude(), results)
        val segmentLen = results[0]
        if (segmentLen == 0f) {
            Location.distanceBetween(p.latitude, p.longitude, s1.latitude(), s1.longitude(), results)
            return results[0].toDouble()
        }

        Location.distanceBetween(p.latitude, p.longitude, s1.latitude(), s1.longitude(), results)
        val d1 = results[0]
        Location.distanceBetween(p.latitude, p.longitude, s2.latitude(), s2.longitude(), results)
        val d2 = results[0]

        // Heron's formula for altitude or just min distance to ends if projection is outside
        // For simplicity in mobile GIS, we just check distance to both ends and midpoint
        val midLat = (s1.latitude() + s2.latitude()) / 2.0
        val midLon = (s1.longitude() + s2.longitude()) / 2.0
        Location.distanceBetween(p.latitude, p.longitude, midLat, midLon, results)
        val dMid = results[0]

        return Math.min(d1.toDouble(), Math.min(d2.toDouble(), dMid.toDouble()))
    }

    private fun findSnapPoint(latLng: LatLng): LatLng? {
        var nearestPt: Point? = null
        var minDist = 10.0 // 10m snap radius

        currentFeatures.forEach { f ->
            val geom = f.geometry()
            val pts = when(geom) {
                is Point -> listOf(geom)
                is LineString -> geom.coordinates()
                is Polygon -> geom.coordinates().flatten()
                else -> emptyList()
            }
            pts.forEach { p ->
                val res = FloatArray(1)
                Location.distanceBetween(latLng.latitude, latLng.longitude, p.latitude(), p.longitude(), res)
                if (res[0] < minDist) {
                    minDist = res[0].toDouble()
                    nearestPt = p
                }
            }
        }

        return nearestPt?.let { LatLng(it.latitude(), it.longitude()) }
    }

    private fun isPointInPolygon(p: LatLng, shell: List<Point>): Boolean {
        var intersectCount = 0
        for (i in 0 until shell.size - 1) {
            val p1 = shell[i]
            val p2 = shell[i + 1]
            if ((p1.latitude() > p.latitude) != (p2.latitude() > p.latitude) &&
                (p.longitude < (p2.longitude() - p1.longitude()) * (p.latitude - p1.latitude()) / (p2.latitude() - p1.latitude()) + p1.longitude())
            ) {
                intersectCount++
            }
        }
        return intersectCount % 2 != 0
    }

    private fun toggleMeasureMode() {
        isMeasureMode = !isMeasureMode
        if (isMeasureMode) {
            drawingMode = 0
            updateDrawingButtons()
        }
        measureButton.text = if (isMeasureMode) getString(R.string.cancel) else getString(R.string.meas)
        if (!isMeasureMode) {
            measurePoints.clear()
            map.style?.let {
                it.removeLayer("measure-line")
                it.removeLayer("measure-points")
                it.removeSource("measure-source")
            }
            updateStatus(getString(R.string.measure_mode_off))
        } else {
            updateStatus(getString(R.string.measure_tap))
        }
    }

    private fun addMeasurePoint(latLng: LatLng) {
        measurePoints.add(latLng)
        val features = measurePoints.mapIndexed { i, pt ->
            Feature.fromGeometry(Point.fromLngLat(pt.longitude, pt.latitude)).apply {
                addBooleanProperty("isLast", i == measurePoints.size - 1)
            }
        }
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

            style.addLayer(SymbolLayer("measure-points", "measure-source").apply {
                setProperties(
                    PropertyFactory.iconImage(
                        org.maplibre.android.style.expressions.Expression.match(
                            org.maplibre.android.style.expressions.Expression.get("isLast"),
                            org.maplibre.android.style.expressions.Expression.literal(true), org.maplibre.android.style.expressions.Expression.literal("measure-target-last"),
                            org.maplibre.android.style.expressions.Expression.literal("measure-target")
                        )
                    ),
                    PropertyFactory.iconRotate(
                        org.maplibre.android.style.expressions.Expression.match(
                            org.maplibre.android.style.expressions.Expression.get("isLast"),
                            org.maplibre.android.style.expressions.Expression.literal(true), org.maplibre.android.style.expressions.Expression.literal(targetIconRotation),
                            org.maplibre.android.style.expressions.Expression.literal(0f)
                        )
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(1.5f)
                )
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
        if (measurePoints.isEmpty()) {
            distanceArrow.visibility = View.GONE
            return
        }
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
            val dToGnss = res[0]
            status += " | To GNSS: %.2fm".format(dToGnss)

            // Check if point is on screen
            val point = map.projection.toScreenLocation(pos)
            val isOffScreen = point.x < 0 || point.y < 0 || point.x > mapView.width || point.y > mapView.height

            if (isOffScreen) {
                distanceArrow.visibility = View.VISIBLE
                val angleRad = Math.atan2(point.y.toDouble() - (mapView.height / 2.0), point.x.toDouble() - (mapView.width / 2.0))
                val angleDeg = Math.toDegrees(angleRad) + 90.0
                arrowIcon.rotation = angleDeg.toFloat()
                distanceArrowText.text = "%.1fm".format(dToGnss)

                // Position arrow on screen edge
                val margin = 100f
                var targetX = point.x.coerceIn(margin, mapView.width - margin)
                var targetY = point.y.coerceIn(margin, mapView.height - margin)

                distanceArrow.x = targetX - (distanceArrow.width / 2f)
                distanceArrow.y = targetY - (distanceArrow.height / 2f)
            } else {
                distanceArrow.visibility = View.GONE
            }
        } ?: run { distanceArrow.visibility = View.GONE }

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

        try {
            serialLocationManager.connect(device, baud)
        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}")
            updateStatus("Conn Error: ${e.message}")
        }
    }

    override fun onGgaReceived(sentence: String) {
        if (isNtripActive) ntripManager.sendGga(sentence)
    }

    private var lastFixType = ""
    private var lastSats = -1
    private var lastRtkAge = -1.0
    private var lastAlt = -1.0
    private var lastSpeed = -1.0

    override fun onLocationUpdate(location: SerialLocation) {
        runOnUiThread {
            if (isMeasureMode) {
                targetIconRotation = (targetIconRotation + 1f) % 360f
                map.style?.getLayerAs<SymbolLayer>("measure-points")?.setProperties(PropertyFactory.iconRotate(targetIconRotation))
            }

            // Custom Smooth Follow Logic
            if (followSwitch.isChecked && ::map.isInitialized) {
                val now = SystemClock.elapsedRealtime()
                val pos = LatLng(location.latitude, location.longitude)

                if (now - lastManualMapInteraction > 5000) {
                    if (isRecentlyInteracted) {
                        map.animateCamera(CameraUpdateFactory.newLatLng(pos))
                        isRecentlyInteracted = false
                    } else {
                        val screenPos = map.projection.toScreenLocation(pos)
                        val marginX = mapView.width * 0.2f
                        val marginY = mapView.height * 0.2f
                        val isOutside = screenPos.x < marginX || screenPos.x > (mapView.width - marginX) ||
                                        screenPos.y < marginY || screenPos.y > (mapView.height - marginY)
                        if (isOutside) {
                            map.animateCamera(CameraUpdateFactory.newLatLng(pos))
                        }
                    }
                } else {
                    isRecentlyInteracted = true
                }
            } else if (!followSwitch.isChecked && ::map.isInitialized) {
                isRecentlyInteracted = false
                // If follow is OFF, we don't move the map
            }

            val fixType = location.fixType ?: getString(R.string.no_fix)
            if (fixType != lastFixType) { fixStatusText.text = fixType; lastFixType = fixType }

            val sats = location.satellites ?: 0
            if (sats != lastSats) { satCountText.text = getString(R.string.sats, sats); lastSats = sats }

            val age = location.rtkAge ?: -1.0
            if (age != lastRtkAge) {
                val ageStr = if (age >= 0) "${age}s" else "-"
                rtkAgeText.text = getString(R.string.age, ageStr)
                lastRtkAge = age
            }

            val alt = location.altitude ?: -1.0
            if (alt != lastAlt) {
                val altStr = if (alt != -1.0) "%.2f m".format(alt) else "-"
                altText.text = getString(R.string.alt, altStr)
                lastAlt = alt
            }

            val speed = location.speed ?: 0.0
            if (Math.abs(speed - lastSpeed) > 0.1) {
                speedText.text = "%.1f km/h".format(speed)
                lastSpeed = speed
            }

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
        runOnUiThread { connectSerialButton.text = getString(R.string.disconnect) }
        updateStatus(getString(R.string.serial_connected))
        startNtripFromPrefs()
    }

    private fun constructGga(lat: Double, lon: Double, alt: Double): String {
        val time = java.text.SimpleDateFormat("HHmmss.SS", java.util.Locale.US).format(java.util.Date())

        val latDeg = Math.abs(lat).toInt()
        val latMin = (Math.abs(lat) - latDeg) * 60.0
        val latStr = "%02d%07.4f".format(java.util.Locale.US, latDeg, latMin)
        val latHem = if (lat >= 0) "N" else "S"

        val lonDeg = Math.abs(lon).toInt()
        val lonMin = (Math.abs(lon) - lonDeg) * 60.0
        val lonStr = "%03d%07.4f".format(java.util.Locale.US, lonDeg, lonMin)
        val lonHem = if (lon >= 0) "E" else "W"

        val gga = "GPGGA,$time,$latStr,$latHem,$lonStr,$lonHem,1,08,0.9,%.2f,M,0.0,M,,".format(java.util.Locale.US, alt)
        var checksum = 0
        gga.forEach { checksum = checksum xor it.code }
        return "\$$gga*%02X".format(checksum)
    }

    private fun startNtripFromPrefs() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val manualEn = prefs.getBoolean("manual_gga_en", false)
        if (manualEn) {
            val lat = prefs.getString("manual_lat", "0")?.toDoubleOrNull() ?: 0.0
            val lon = prefs.getString("manual_lon", "0")?.toDoubleOrNull() ?: 0.0
            val alt = prefs.getString("manual_alt", "0")?.toDoubleOrNull() ?: 0.0
            ntripManager.setManualGga(constructGga(lat, lon, alt))
        } else {
            ntripManager.setManualGga(null)
        }

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
        runOnUiThread { connectSerialButton.text = getString(R.string.connect) }
        updateStatus(getString(R.string.disconnected))
    }

    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) {
            try {
                mapView.onStart()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MapView", e)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            try {
                mapView.onResume()
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming MapView", e)
            }
        }
    }
    override fun onPause() {
        if (::mapView.isInitialized) {
            try {
                mapView.onPause()
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing MapView", e)
            }
        }
        super.onPause()
    }
    override fun onStop() {
        if (::mapView.isInitialized) {
            try {
                mapView.onStop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MapView", e)
            }
        }
        super.onStop()
    }
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
