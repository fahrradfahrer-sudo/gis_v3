package de.witt3d_gis

import android.app.PendingIntent
import android.location.Location
import android.os.Looper
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult

class SerialLocationEngine : LocationEngine {
    private val callbacks = mutableSetOf<LocationEngineCallback<LocationEngineResult>>()
    private var lastLocation: Location? = null

    override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
        val location = lastLocation
        if (location != null) {
            callback.onSuccess(LocationEngineResult.create(location))
        } else {
            callback.onFailure(Exception("No location available"))
        }
    }

    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        callback: LocationEngineCallback<LocationEngineResult>,
        looper: Looper?
    ) {
        callbacks.add(callback)
    }

    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        pendingIntent: PendingIntent?
    ) {
        // Not implemented for this use case
    }

    override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
        callbacks.remove(callback)
    }

    override fun removeLocationUpdates(pendingIntent: PendingIntent?) {
        // Not implemented for this use case
    }

    fun updateLocation(location: Location) {
        lastLocation = location
        val result = LocationEngineResult.create(location)
        callbacks.forEach { it.onSuccess(result) }
    }
}
