package de.witt3d_gis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

data class SatInfo(
    val svId: Int,
    val elevation: Int,
    val azimuth: Int,
    val cno: Int,
    val gnssId: Int // 0: GPS, 1: SBAS, 2: Galileo, 3: BeiDou, 4: IMES, 5: QZSS, 6: GLONASS
)

class SkyplotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var satellites = listOf<SatInfo>()
    private val circlePaint = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
    private val textPaint = Paint().apply { color = Color.BLACK; textSize = 30f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    private val satPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    fun setSatellites(sats: List<SatInfo>) {
        this.satellites = sats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (Math.min(width, height) / 2f) * 0.8f

        // Draw rings
        canvas.drawCircle(cx, cy, radius, circlePaint)
        canvas.drawCircle(cx, cy, radius * 0.66f, circlePaint)
        canvas.drawCircle(cx, cy, radius * 0.33f, circlePaint)

        // Draw cross
        canvas.drawLine(cx - radius, cy, cx + radius, cy, circlePaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, circlePaint)

        // Draw Cardinals
        canvas.drawText("N", cx, cy - radius - 10, textPaint)
        canvas.drawText("S", cx, cy + radius + 30, textPaint)
        canvas.drawText("E", cx + radius + 20, cy + 10, textPaint)
        canvas.drawText("W", cx - radius - 20, cy + 10, textPaint)

        satellites.forEach { sat ->
            val r = radius * (90f - sat.elevation) / 90f
            val angleRad = Math.toRadians(sat.azimuth - 90.0)
            val sx = cx + r * Math.cos(angleRad).toFloat()
            val sy = cy + r * Math.sin(angleRad).toFloat()

            satPaint.color = when(sat.gnssId) {
                0 -> Color.GREEN // GPS
                2 -> Color.BLUE // Galileo
                3 -> Color.RED // BeiDou
                6 -> Color.MAGENTA // GLONASS
                else -> Color.YELLOW
            }

            // Adjust alpha based on signal
            satPaint.alpha = (sat.cno.coerceIn(0, 50) * 5.1).toInt().coerceIn(100, 255)

            canvas.drawCircle(sx, sy, 15f, satPaint)
            canvas.drawText(sat.svId.toString(), sx, sy - 20f, textPaint)
        }
    }
}
