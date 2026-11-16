package io.github.nvkff.opilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.location.LocationManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.*
import org.osmdroid.config.Configuration
import org.osmdroid.events.*
import org.osmdroid.tileprovider.tilesource.*
import org.osmdroid.util.*
import org.osmdroid.views.MapView
import java.io.File
import java.util.Locale

class GeorefActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var overlayView: MapOverlayView
    private lateinit var tvInfo: TextView
    private lateinit var modeBtns: List<Button>
    private var scanBmp: Bitmap? = null
    private var isSat = false
    private var scaleDenom = 0
    private val esriSat = object : OnlineTileSourceBase("EsriSat", 1, 19, 256, ".jpg", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
        override fun getTileURLString(idx: Long) = baseUrl + MapTileIndex.getZoom(idx) + "/" + MapTileIndex.getY(idx) + "/" + MapTileIndex.getX(idx)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_georef)
        mapView = findViewById(R.id.mapView); overlayView = findViewById(R.id.overlayView); tvInfo = findViewById(R.id.tvGeorefInfo)
        overlayView.mapViewRef = mapView; mapView.setTileSource(TileSourceFactory.MAPNIK); mapView.setMultiTouchControls(true)
        mapView.addMapListener(object : MapListener {
            override fun onScroll(e: ScrollEvent?): Boolean { overlayView.invalidate(); return false }
            override fun onZoom(e: ZoomEvent?): Boolean { overlayView.invalidate(); return false }
        })
        val startGeo = centerMap()
        scaleDenom = intent.getIntExtra("scale", 0)
        scanBmp = BitmapFactory.decodeFile(File(filesDir, "current_map.png").absolutePath)
        val arr = intent.getFloatArrayExtra("cps") ?: floatArrayOf()
        val pts = (0 until arr.size / 2).map { PointF(arr[it * 2], arr[it * 2 + 1]) }
        val lbls = intent.getStringArrayListExtra("labels") ?: arrayListOf()
        scanBmp?.let { overlayView.setMap(it, pts, startGeo, lbls); overlayView.widthMeters = if (scaleDenom > 0) scaleDenom * 0.28 else 1800.0 }
        if (scaleDenom > 0) tvInfo.text = "Масштаб: 1:$scaleDenom · Жми Авто или 2 точки"
        findViewById<SeekBar>(R.id.seekAlpha).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) = overlayView.setOverlayAlpha(p)
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val btnLayer = findViewById<Button>(R.id.btnLayer)
        btnLayer.setOnClickListener { isSat = !isSat; mapView.setTileSource(if (isSat) esriSat else TileSourceFactory.MAPNIK); btnLayer.text = if (isSat) "Схема OSM" else "Спутник" }
        findViewById<Button>(R.id.btnBringHere).setOnClickListener { val c = mapView.mapCenter; overlayView.centerGeo = GeoPoint(c.latitude, c.longitude); overlayView.invalidate() }
        val bMap = findViewById<Button>(R.id.btnModeMap); val bScan = findViewById<Button>(R.id.btnModeScan)
        val bCp = findViewById<Button>(R.id.btnModeCp); val b2Pt = findViewById<Button>(R.id.btnMode2Pt)
        modeBtns = listOf(bMap, bScan, bCp, b2Pt)
        overlayView.onStatus = { msg -> tvInfo.text = msg; if (overlayView.mode == MapOverlayView.Mode.MAP) refreshModeBtns(0) }
        bMap.setOnClickListener { overlayView.mode = MapOverlayView.Mode.MAP; refreshModeBtns(0) }
        bScan.setOnClickListener { overlayView.mode = MapOverlayView.Mode.ADJUST_SCAN; refreshModeBtns(1) }
        bCp.setOnClickListener { overlayView.mode = MapOverlayView.Mode.EDIT_CP; refreshModeBtns(2) }
        b2Pt.setOnClickListener { overlayView.startTwoPt(); refreshModeBtns(3) }
        refreshModeBtns(0)
        findViewById<Button>(R.id.btnAutoAlign).setOnClickListener { runAutoAlign() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finishGeoref() }
    }
    private fun refreshModeBtns(activeIdx: Int) {
        modeBtns.forEachIndexed { i, b -> b.backgroundTintList = ColorStateList.valueOf(if (i == activeIdx) 0xFF2EA043.toInt() else 0xFF2D333B.toInt()) }
    }
    private fun centerMap(): GeoPoint {
        mapView.controller.setZoom(13.5); var target = GeoPoint(55.7558, 37.6173)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) target = GeoPoint(loc.latitude, loc.longitude)
        } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
        mapView.controller.setCenter(target); return target
    }
    private fun runAutoAlign() {
        val bmp = scanBmp ?: return; val c = mapView.mapCenter; tvInfo.text = "Загрузка OSM (Overpass API)..."
        CoroutineScope(Dispatchers.Main).launch {
            var err: String? = null
            val res = withContext(Dispatchers.IO) {
                try { OverpassGeoref.autoAlign(bmp, c.latitude, c.longitude, OverpassGeoref.fetchWays(c.latitude, c.longitude), scaleDenom) }
                catch (e: Exception) { err = e.message; null }
            }
            if (res != null) {
                val gp = GeoPoint(res.centerLat, res.centerLon); overlayView.setPlacement(gp, res.widthMeters); mapView.controller.animateTo(gp)
                tvInfo.text = String.format(Locale.US, "Авто-привязка: %.2f", res.score)
            } else tvInfo.text = "Ошибка OSM: ${err ?: "нет совпадения"}"
        }
    }
    private fun finishGeoref() {
        val cps = overlayView.exportControlPoints(mapView.projection); val arr = JSONArray()
        cps.forEach { cp -> arr.put(JSONObject().put("id", cp.id).put("label", cp.label).put("lat", cp.lat).put("lon", cp.lon).put("taken", false)) }
        File(filesDir, "current_route.json").writeText(arr.toString())
        val summary = if (cps.isEmpty()) "КП не отмечены" else cps.joinToString("\n") { String.format(Locale.US, "%s: %.5f, %.5f", it.label, it.lat, it.lon) }
        AlertDialog.Builder(this).setTitle("Сохранено (${cps.size} КП)").setMessage(summary).setPositiveButton("Ок", null).show()
    }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
}
