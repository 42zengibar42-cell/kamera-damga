package com.kameradamga

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Konum, adres ve pusula bilgisini toplar; damga satırlarını üretir. */
class StampSource(private val ctx: Context) : LocationListener, SensorEventListener {

    @Volatile var location: Location? = null
        private set
    @Volatile private var addressLines: List<String> = emptyList()
    @Volatile private var heading: Float = Float.NaN

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val geoExec = Executors.newSingleThreadExecutor()

    private var lastGeo: Location? = null
    private var lastGeoTime = 0L

    private var sinS = 0.0
    private var cosS = 0.0
    private var compassInit = false
    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    private val prefs = Prefs(ctx)

    private fun hasLocationPermission() =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        stop()
        if (hasLocationPermission()) {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    if (lm.isProviderEnabled(p)) {
                        lm.requestLocationUpdates(p, 1000L, 0f, this)
                        lm.getLastKnownLocation(p)?.let { accept(it) }
                    }
                } catch (_: Exception) {
                }
            }
        }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        try {
            lm.removeUpdates(this)
        } catch (_: Exception) {
        }
        sm.unregisterListener(this)
    }

    // ---- Konum ----
    override fun onLocationChanged(l: Location) = accept(l)
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    private fun accept(l: Location) {
        val cur = location
        if (cur == null || l.provider == LocationManager.GPS_PROVIDER ||
            l.time - cur.time > 10_000 || l.accuracy <= cur.accuracy
        ) {
            location = l
            maybeGeocode(l)
        }
    }

    private fun maybeGeocode(l: Location) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGeoTime < 5_000) return
        val moved = lastGeo?.distanceTo(l) ?: Float.MAX_VALUE
        if (moved < 25f && now - lastGeoTime < 120_000 && addressLines.isNotEmpty()) return
        if (!Geocoder.isPresent()) return
        lastGeo = l
        lastGeoTime = now
        geoExec.execute {
            try {
                @Suppress("DEPRECATION")
                val res = Geocoder(ctx, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)
                val a = res?.firstOrNull() ?: return@execute
                addressLines = addressLinesOf(a)
            } catch (_: Exception) {
            }
        }
    }

    private fun addressLinesOf(a: Address): List<String> {
        val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ")
        val parts = listOf(street, a.locality, a.subAdminArea, a.adminArea)
            .filterNotNull()
            .filter { it.isNotBlank() }
            .distinct()
        if (parts.isNotEmpty()) return parts
        return listOfNotNull(a.getAddressLine(0)).filter { it.isNotBlank() }
    }

    // ---- Pusula ----
    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orient)
        val az = orient[0].toDouble()
        if (!compassInit) {
            sinS = sin(az)
            cosS = cos(az)
            compassInit = true
        } else {
            sinS += 0.15 * (sin(az) - sinS)
            cosS += 0.15 * (cos(az) - cosS)
        }
        heading = ((Math.toDegrees(atan2(sinS, cosS)) + 360.0) % 360.0).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun cardinal(h: Float): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[((h + 22.5f) / 45f).toInt() % 8]
    }

    /** Damga metni. [index] sıra numarasıdır. */
    fun lines(index: Int): List<String> {
        val out = ArrayList<String>()
        if (prefs.showDateTime) {
            out += SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        }
        val hd = heading
        if (prefs.showHeading && !hd.isNaN()) {
            out += "${hd.roundToInt() % 360}° ${cardinal(hd)}"
        }
        if (prefs.showAddress) out += addressLines
        val l = location
        if (prefs.showCoords && l != null) {
            out += String.format(Locale.US, "%.6f, %.6f", l.latitude, l.longitude)
        }
        if (prefs.showAltSpeed && l != null) {
            out += ctx.getString(R.string.alt_fmt, l.altitude)
            out += ctx.getString(R.string.speed_fmt, l.speed * 3.6)
        }
        val custom = prefs.customText.trim()
        if (custom.isNotEmpty()) out += custom.lines().filter { it.isNotBlank() }
        if (prefs.showIndex) out += ctx.getString(R.string.index_fmt, index)
        return out
    }
}
