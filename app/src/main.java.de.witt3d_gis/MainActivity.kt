package de.witt3d_gis

import android.app.ProgressDialog
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            this.map = map
            map.setStyle(Style.Builder().fromUri("https://demotiles.maplibre.org/style.json"))
        }

        offlineManager = OfflineManager.getInstance(this)

        val downloadButton = findViewById<Button>(R.id.downloadButton)
        downloadButton.setOnClickListener {
            if (::map.isInitialized) {
                downloadRegion()
            }
        }
    }

    private fun downloadRegion() {
        val styleUrl = map.style!!.uri
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

        val progressDialog = ProgressDialog(this)
        progressDialog.setTitle("Downloading Offline Map")
        progressDialog.setMessage("Please wait...")
        progressDialog.setCancelable(false)
        progressDialog.show()

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
                            progressDialog.progress = percentage
                            if (status.isComplete) {
                                progressDialog.dismiss()
                                Toast.makeText(
                                    this@MainActivity,
                                    "Offline map downloaded successfully",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else if (status.isDownloading) {
                                progressDialog.setMessage("Downloading: $percentage%")
                            }
                        }

                        override fun onError(error: OfflineRegionError) {
                            progressDialog.dismiss()
                            Toast.makeText(
                                this@MainActivity,
                                "Error downloading offline map: ${error.reason}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            progressDialog.dismiss()
                            Toast.makeText(
                                this@MainActivity,
                                "Mapbox tile count limit exceeded: $limit",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                }

                override fun onError(error: String) {
                    progressDialog.dismiss()
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
