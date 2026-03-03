package de.witt3d_gis

import android.app.PendingIntent
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import java.util.concurrent.CopyOnWriteArrayList

class SerialLocationEngine : LocationEngine {
    private val TAG = "SerialLocationEngine"
    private val callbacks = CopyOnWriteArrayList<LocationEngineCallback<LocationEngineResult>>()
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
        if (!callbacks.contains(callback)) {
            callbacks.add(callback)
            Log.d(TAG, "Added location callback. Total: ${callbacks.size}")
        }
    }

    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        pendingIntent: PendingIntent?
    ) {
    }

    override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
        callbacks.remove(callback)
        Log.d(TAG, "Removed location callback. Total: ${callbacks.size}")
    }

    override fun removeLocationUpdates(pendingIntent: PendingIntent?) {
    }

    fun updateLocation(location: Location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        lastLocation = location
        val result = LocationEngineResult.create(location)
        for (callback in callbacks) {
            try {
                callback.onSuccess(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error in location callback", e)
            }
        }
    }
}
