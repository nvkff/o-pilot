package io.github.nvkff.opilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

class NavActivity : AppCompatActivity(), SensorEventListener, LocationListener {
    private lateinit var navMap: NavMapView
    private lateinit var compass: SportsCompassView
    private lateinit var tvTitle: TextView
    private lateinit var tvStats: TextView
    private lateinit var btnTake: Button
    private lateinit var sm: SensorManager
    private lateinit var lm: LocationManager
    private var rotSens: Sensor? = null; private var accSens: Sensor? = null; private var magSens: Sensor? = null
    private val grav = FloatArray(3); private val geom = FloatArray(3); private var hasG = false; private var hasM = false
    private var project: MapProject? = null; private var bmpW = 1; private var bmpH = 1
    private var curLoc: Location? = null; private var customGeo: Pair<Double, Double>? = null
    private var heading = 0f; private var declination = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_nav)
        navMap = findViewById(R.id.navMapView); compass = findViewById(R.id.compassView)
        tvTitle = findViewById(R.id.tvTargetTitle); tvStats = findViewById(R.id.tvTargetStats); btnTake = findViewById(R.id.btnTakeCp)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager; lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        rotSens = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSens == null) { accSens = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); magSens = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
        val pid = intent.getStringExtra("project_id") ?: ""
        project = MapProjectStorage.loadById(this, pid); val bmp = MapProjectStorage.loadBitmap(this, pid)
        if (project != null && bmp != null) { bmpW = bmp.width; bmpH = bmp.height; navMap.setData(bmp, project!!) }
        navMap.onSelectCp = { idx -> customGeo = null; navMap.activeIdx = idx; refreshTarget() }
        navMap.onSelectPoint = { bx, by -> project?.let { customGeo = MapProjectStorage.pixelToLatLon(it, bmpW, bmpH, bx, by); refreshTarget() } }
        val btnGps = findViewById<Button>(R.id.btnToggleGps)
        btnGps.setOnClickListener { navMap.showGps = !navMap.showGps; btnGps.text = if (navMap.showGps) "GPS: ВКЛ" else "GPS: СКРЫТ"; navMap.invalidate() }
        findViewById<Button>(R.id.btnPrevCp).setOnClickListener { stepCp(-1) }
        findViewById<Button>(R.id.btnNextCp).setOnClickListener { stepCp(1) }
        btnTake.setOnClickListener { toggleTaken() }
        refreshTarget()
    }
    private fun stepCp(d: Int) { val p = project ?: return; if (p.cps.isEmpty()) return; customGeo = null; navMap.customBx = -1f; navMap.activeIdx = (navMap.activeIdx + d + p.cps.size) % p.cps.size; navMap.invalidate(); refreshTarget() }
    private fun toggleTaken() {
        val p = project ?: return; if (customGeo != null) return; val cp = p.cps.getOrNull(navMap.activeIdx) ?: return
        cp.taken = !cp.taken; btnTake.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (cp.taken) { val nxt = p.cps.indexOfFirst { !it.taken }; if (nxt >= 0) navMap.activeIdx = nxt }
        MapProjectStorage.save(this, p); navMap.invalidate(); refreshTarget()
    }
    private fun refreshTarget() {
        val p = project; val cg = customGeo
        val tLat = cg?.first ?: p?.cps?.getOrNull(navMap.activeIdx)?.lat
        val tLon = cg?.second ?: p?.cps?.getOrNull(navMap.activeIdx)?.lon
        val title = if (cg != null) "Точка на карте" else p?.cps?.getOrNull(navMap.activeIdx)?.let { "Цель: КП ${it.label}" } ?: "Тапни точку на карте"
        val isT = if (cg != null) false else p?.cps?.getOrNull(navMap.activeIdx)?.taken == true
        tvTitle.text = title; compass.isTaken = isT; btnTake.isEnabled = cg == null; btnTake.text = if (isT) "СНЯТЬ ОТМЕТКУ" else "КП ВЗЯТ ✓"
        if (tLat == null || tLon == null) { compass.hasTarget = false; tvStats.text = "Нет цели"; compass.invalidate(); return }
        compass.hasTarget = true; val loc = curLoc
        if (loc != null) {
            val dst = Location("t").apply { latitude = tLat; longitude = tLon }
            var b = loc.bearingTo(dst); if (b < 0f) b += 360f; val d = loc.distanceTo(dst); compass.targetBearingDeg = b
            val dStr = if (d >= 1000f) String.format(Locale.US, "%.2f км", d / 1000f) else "${d.roundToInt()} м"
            tvStats.text = "$dStr · Азимут ${b.roundToInt()}°"
        } else tvStats.text = "Ожидание GPS..."
        compass.invalidate()
    }
    override fun onResume() {
        super.onResume()
        rotSens?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } ?: run { accSens?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }; magSens?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 1f, this)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 2f, this)
        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 102)
    }
    override fun onPause() { super.onPause(); sm.unregisterListener(this); lm.removeUpdates(this) }
    override fun onLocationChanged(l: Location) { curLoc = l; declination = GeomagneticField(l.latitude.toFloat(), l.longitude.toFloat(), l.altitude.toFloat(), System.currentTimeMillis()).declination; navMap.gpsLat = l.latitude; navMap.gpsLon = l.longitude; navMap.invalidate(); refreshTarget() }
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) { val r = FloatArray(9); SensorManager.getRotationMatrixFromVector(r, e.values); updateH(r) }
        else if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) { System.arraycopy(e.values, 0, grav, 0, 3); hasG = true; if (hasM) { val r = FloatArray(9); if (SensorManager.getRotationMatrix(r, FloatArray(9), grav, geom)) updateH(r) } }
        else if (e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) { System.arraycopy(e.values, 0, geom, 0, 3); hasM = true; if (hasG) { val r = FloatArray(9); if (SensorManager.getRotationMatrix(r, FloatArray(9), grav, geom)) updateH(r) } }
    }
    private fun updateH(r: FloatArray) { if (r[1] * r[1] + r[4] * r[4] < 0.05f) return; val mag = Math.toDegrees(atan2(r[1].toDouble(), r[4].toDouble())).toFloat(); heading = (mag + declination + 360f) % 360f; compass.headingDeg = heading; compass.invalidate() }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
