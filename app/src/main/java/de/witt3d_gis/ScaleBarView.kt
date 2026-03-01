package de.witt3d_gis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import org.maplibre.android.maps.MapLibreMap

class ScaleBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var map: MapLibreMap? = null
    var isRelativeMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 4f
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    fun setMap(map: MapLibreMap) {
        this.map = map
        map.addOnCameraMoveListener { invalidate() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val map = map ?: return
        val projection = map.projection
        val width = width.toFloat()
        val height = height.toFloat()

        // Calculate 100 pixels distance in meters at the center of the map
        val center = map.cameraPosition.target ?: return
        val p1 = projection.toScreenLocation(center)
        val p2 = android.graphics.PointF(p1.x + 100, p1.y)
        val latLng2 = projection.fromScreenLocation(p2) ?: return

        val results = FloatArray(1)
        android.location.Location.distanceBetween(center.latitude, center.longitude, latLng2.latitude, latLng2.longitude, results)
        val metersPer100Pixels = results[0]

        // Find a nice round number for the scale (including cm and mm for very high zoom)
        val niceDistances = doubleArrayOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
        var scaleMeters = niceDistances[0]
        for (d in niceDistances) {
            if (metersPer100Pixels > d * 0.5) scaleMeters = d
            else break
        }

        val scaleWidthPixels = (scaleMeters / metersPer100Pixels * 100).toFloat()
        val xEnd = width - 20
        val xStart = xEnd - scaleWidthPixels
        val y = height - 20

        // Draw scale line
        canvas.drawLine(xStart, y, xEnd, y, paint)
        canvas.drawLine(xStart, y, xStart, y - 10, paint)
        canvas.drawLine(xEnd, y, xEnd, y - 10, paint)

        val text = if (isRelativeMode) {
            val scale = (metersPer100Pixels / (100.0 / 96.0 * 0.0254)) // Very rough DPI scale estimation
            "1:${scale.toInt()}"
        } else {
            when {
                scaleMeters >= 1000 -> "${(scaleMeters / 1000).toInt()} km"
                scaleMeters >= 1.0 -> "${scaleMeters.toInt()} m"
                scaleMeters >= 0.01 -> "${(scaleMeters * 100).toInt()} cm"
                else -> "${(scaleMeters * 1000).toInt()} mm"
            }
        }
        canvas.drawText(text, xStart + scaleWidthPixels / 2, y - 15, paint)
    }
}
