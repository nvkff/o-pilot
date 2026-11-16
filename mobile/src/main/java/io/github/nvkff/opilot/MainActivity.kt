package io.github.nvkff.opilot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.*
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnAnalyze: Button
    private var currentBitmap: Bitmap? = null
    private var detectedCps: List<PointF> = emptyList()
    private var detectedLabels: List<String> = emptyList()
    private var detectedScale = 0
    private var isAnalyzed = false
    private val prefs by lazy { getSharedPreferences("opilot_prefs", MODE_PRIVATE) }

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.firstOrNull()?.imageUri?.let { processUri(it, false) }
    }
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) processUri(uri, false) }
    private val pdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) processUri(uri, true) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus); ivPreview = findViewById(R.id.ivPreview); btnAnalyze = findViewById(R.id.btnAnalyze)
        tvStatus.text = if (OpenCVLoader.initLocal()) "OpenCV 4.12 готов к работе" else "Ошибка OpenCV"
        findViewById<Button>(R.id.btnLibrary).setOnClickListener { openLibrary() }
        findViewById<Button>(R.id.btnGeminiKey).setOnClickListener { askApiKey() }
        findViewById<Button>(R.id.btnScanCamera).setOnClickListener { startScanner() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { askGalleryMode() }
        findViewById<Button>(R.id.btnImportPdf).setOnClickListener { pdfLauncher.launch(arrayOf("application/pdf")) }
        btnAnalyze.setOnClickListener { if (!isAnalyzed) runAnalysis() else openGeoref() }
    }
    private fun askApiKey() {
        val et = EditText(this).apply { hint = "AQ...."; setText(prefs.getString("gemini_key", "")) }
        AlertDialog.Builder(this).setTitle("Ключ Google AI Studio").setView(et)
            .setPositiveButton("Сохранить") { _, _ -> prefs.edit().putString("gemini_key", et.text.toString().trim()).apply() }
            .setNeutralButton("Очистить") { _, _ -> prefs.edit().remove("gemini_key").apply() }.show()
    }
    private fun askGalleryMode() {
        AlertDialog.Builder(this).setTitle("Загрузка из галереи").setMessage("Если фото под углом, лучше открыть сканер.")
            .setPositiveButton("Готовый скан") { _, _ -> galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .setNeutralButton("Открыть сканер") { _, _ -> startScanner() }.show()
    }
    private fun startScanner() {
        val opts = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(1).setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this).addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { Toast.makeText(this, "Сканер: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    private fun processUri(uri: Uri, isPdf: Boolean) {
        tvStatus.text = "Загрузка карты..."
        CoroutineScope(Dispatchers.Main).launch {
            val bmp = withContext(Dispatchers.IO) {
                val loaded = if (isPdf) MapLoader.loadPdfFirstPage(this@MainActivity, uri) else MapLoader.loadBitmapFromUri(this@MainActivity, uri)
                loaded?.also { MapLoader.saveWorkingMap(this@MainActivity, it) }
            }
            if (bmp != null) {
                currentBitmap = bmp; isAnalyzed = false; detectedScale = 0; detectedLabels = emptyList(); ivPreview.setImageBitmap(bmp)
                btnAnalyze.text = "Далее: поиск КП и севера"; btnAnalyze.visibility = View.VISIBLE; tvStatus.text = "Карта: ${bmp.width}x${bmp.height} px"
            } else tvStatus.text = "Не удалось открыть файл"
        }
    }
    private fun runAnalysis() {
        val bmp = currentBitmap ?: return; val key = prefs.getString("gemini_key", "").orEmpty()
        tvStatus.text = if (key.isNotEmpty()) "OpenCV + Gemini AI анализ..." else "Анализ OpenCV..."
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.Default) { MapAnalyzer.analyze(bmp) }
            withContext(Dispatchers.IO) { MapLoader.saveWorkingMap(this@MainActivity, res.alignedBitmap) }
            var pts = res.controlPoints; var lbls = pts.indices.map { "${it + 1}" }; var fin = res.finishPoint; var legCnt = 0; var gErr: String? = null
            if (key.isNotEmpty()) {
                val g = withContext(Dispatchers.IO) { GeminiCourseAnalyzer.analyze(res.alignedBitmap, res.controlPoints, key) }
                if (g.err == null) { pts = g.points; lbls = g.labels; fin = g.finish ?: fin; legCnt = g.legendCount } else gErr = g.err
            }
            ScaleDetector.detect(res.alignedBitmap) { sc ->
                detectedScale = sc; currentBitmap = res.alignedBitmap; detectedCps = pts; detectedLabels = lbls; isAnalyzed = true
                ivPreview.setImageBitmap(MapAnalyzer.drawMarkers(res.alignedBitmap, pts, lbls, fin))
                btnAnalyze.text = "Далее: привязка к карте"
                val scT = if (sc > 0) "1:$sc" else "нет"
                if (gErr != null) AlertDialog.Builder(this@MainActivity).setTitle("Ошибка Gemini AI").setMessage(gErr).setPositiveButton("Ок", null).show() else if (key.isEmpty()) Toast.makeText(this@MainActivity, "Ключ Gemini не задан (кнопка справа сверху)", Toast.LENGTH_LONG).show(); tvStatus.text = "КП: ${pts.size} · Легенда: $legCnt · Масштаб: $scT"
            }
        }
    }
    private fun openGeoref() {
        val arr = FloatArray(detectedCps.size * 2); detectedCps.forEachIndexed { i, p -> arr[i * 2] = p.x; arr[i * 2 + 1] = p.y }
        startActivity(Intent(this, GeorefActivity::class.java).putExtra("cps", arr).putStringArrayListExtra("labels", ArrayList(detectedLabels)).putExtra("scale", detectedScale))
    }
    override fun onResume() {
        super.onResume()
        val cnt = MapProjectStorage.loadAll(this).size
        findViewById<Button>(R.id.btnLibrary).text = if (cnt > 0) "Мои карты ($cnt)" else "Мои карты"
    }
    private fun openLibrary() {
        val list = MapProjectStorage.loadAll(this)
        if (list.isEmpty()) { Toast.makeText(this, "Пока нет сохранённых карт", Toast.LENGTH_SHORT).show(); return }
        val titles = list.map { "${it.name} (${it.cps.size} КП)" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Мои карты").setItems(titles) { _, idx ->
            val pr = list[idx]
            AlertDialog.Builder(this).setTitle(pr.name)
                .setPositiveButton("Навигация") { _, _ -> startActivity(Intent(this, NavActivity::class.java).putExtra("project_id", pr.id)) }
                .setNegativeButton("Удалить") { _, _ -> MapProjectStorage.delete(this, pr.id); onResume() }
                .setNeutralButton("Отмена", null).show()
        }.show()
    }
}
