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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

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

    // ---- Mini harita (OpenStreetMap karoları) ----
    @Volatile var mapBitmap: Bitmap? = null
        private set
    private val mapExec = Executors.newSingleThreadExecutor()
    private var lastMapLoc: Location? = null
    private var lastMapTime = 0L
    @Volatile private var mapBusy = false
    private val tileCache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 40
    }

    private fun maybeFetchMap(l: Location) {
        if (mapBusy) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMapTime < 5_000) return
        val last = lastMapLoc
        if (mapBitmap != null && last != null && last.distanceTo(l) < 30f && now - lastMapTime < 300_000) return
        mapBusy = true
        lastMapTime = now
        mapExec.execute {
            try {
                buildMap(l)?.let {
                    mapBitmap = it
                    lastMapLoc = l
                }
            } catch (_: Throwable) {
            } finally {
                mapBusy = false
            }
        }
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        synchronized(tileCache) { tileCache[key]?.let { return it } }
        return try {
            val conn = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Buradayim/1.0 (Android camera app)")
            conn.inputStream.use { BitmapFactory.decodeStream(it) }?.also {
                synchronized(tileCache) { tileCache[key] = it }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildMap(l: Location): Bitmap? {
        val z = 17
        val n = 1 shl z
        val latR = Math.toRadians(l.latitude)
        val xf = (l.longitude + 180.0) / 360.0 * n
        val yf = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / Math.PI) / 2.0 * n
        val tx = floor(xf).toInt()
        val ty = floor(yf).toInt()

        val big = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        val bc = Canvas(big)
        var any = false
        for (dy in -1..1) for (dx in -1..1) {
            val t = tile(z, tx + dx, ty + dy) ?: continue
            bc.drawBitmap(t, ((dx + 1) * 256).toFloat(), ((dy + 1) * 256).toFloat(), null)
            any = true
        }
        if (!any) return null

        val cx = ((xf - tx) + 1.0) * 256.0
        val cy = ((yf - ty) + 1.0) * 256.0
        val sx = (cx - 256.0).toFloat().coerceIn(0f, 256f)
        val sy = (cy - 256.0).toFloat().coerceIn(0f, 256f)
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawBitmap(big, -sx, -sy, null)
        big.recycle()

        // İğne
        val px = cx.toFloat() - sx
        val py = cy.toFloat() - sy
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
        oc.drawCircle(px, py, 22f, white)
        oc.drawCircle(px, py, 15f, red)

        // OpenStreetMap kaynak belirtmesi
        val strip = Paint().apply { color = Color.argb(140, 0, 0, 0) }
        oc.drawRect(0f, 512f - 30f, 512f, 512f, strip)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
        }
        oc.drawText("© OpenStreetMap", 10f, 512f - 9f, txt)
        return out
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
        if (prefs.showMap) location?.let { maybeFetchMap(it) }
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
