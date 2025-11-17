package de.witt3d_gis

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var offlineManager: OfflineManager
    private var offlineRegion: OfflineRegion? = null
    private var isDownloading = false
    private lateinit var progressBar: ProgressBar

    // Replace with your own MapTiler API key
    private val apiKey = "YOUR_MAPTILER_API_KEY"

    private val mapStyles = listOf(
        "MapTiler Basic" to "https://api.maptiler.com/maps/basic/style.json?key=$apiKey",
        "OSM Bright" to "https://api.maptiler.com/maps/bright/style.json?key=$apiKey",
        "Toner" to "https://api.maptiler.com/maps/toner/style.json?key=$apiKey"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            this.map = map
            map.setStyle(mapStyles[0].second)
        }

        offlineManager = OfflineManager.getInstance(this)

        val downloadButton = findViewById<Button>(R.id.downloadButton)
        downloadButton.setOnClickListener {
            if (::map.isInitialized && !isDownloading) {
                downloadRegion()
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
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (::map.isInitialized) {
                    map.setStyle(mapStyles[position].second)
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
        offlineRegion?.setObserver(null)
        mapView.onDestroy()
    }
}
