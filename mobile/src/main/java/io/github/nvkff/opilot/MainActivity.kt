package io.github.nvkff.opilot

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {
    private lateinit var tvStatus: TextView
    private lateinit var ivPreview: ImageView
    private lateinit var btnAnalyze: Button
    private var currentBitmap: Bitmap? = null
    private var detectedCps: List<PointF> = emptyList()
    private var isAnalyzed = false

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val uri = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.firstOrNull()?.imageUri ?: return@registerForActivityResult
            processUri(uri, false)
        }
    }
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) processUri(uri, false) }
    private val pdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) processUri(uri, true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        ivPreview = findViewById(R.id.ivPreview)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        tvStatus.text = if (OpenCVLoader.initLocal()) "OpenCV 4.12 готов к работе" else "Ошибка OpenCV"
        findViewById<Button>(R.id.btnScanCamera).setOnClickListener { startScanner() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { askGalleryMode() }
        findViewById<Button>(R.id.btnImportPdf).setOnClickListener { pdfLauncher.launch(arrayOf("application/pdf")) }
        btnAnalyze.setOnClickListener { if (!isAnalyzed) runAnalysis() else openGeoref() }
    }
    private fun askGalleryMode() {
        AlertDialog.Builder(this).setTitle("Загрузка из галереи").setMessage("Если фото под углом, лучше открыть сканер (иконка галереи слева внизу).")
            .setPositiveButton("Готовый скан") { _, _ -> galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .setNeutralButton("Открыть сканер") { _, _ -> startScanner() }.show()
    }
    private fun startScanner() {
        val opts = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(1).setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
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
                currentBitmap = bmp; isAnalyzed = false; ivPreview.setImageBitmap(bmp)
                btnAnalyze.text = "Далее: поиск КП и севера (OpenCV)"; btnAnalyze.visibility = View.VISIBLE
                tvStatus.text = "Карта: ${bmp.width}x${bmp.height} px"
            } else tvStatus.text = "Не удалось открыть файл"
        }
    }
    private fun runAnalysis() {
        val bmp = currentBitmap ?: return
        tvStatus.text = "Анализ OpenCV (север и КП)..."
        CoroutineScope(Dispatchers.Main).launch {
            val res = withContext(Dispatchers.Default) { MapAnalyzer.analyze(bmp) }
            withContext(Dispatchers.IO) { MapLoader.saveWorkingMap(this@MainActivity, res.alignedBitmap) }
            currentBitmap = res.alignedBitmap; detectedCps = res.controlPoints; isAnalyzed = true
            ivPreview.setImageBitmap(res.previewBitmap); btnAnalyze.text = "Далее: привязка к карте"
            tvStatus.text = "Найдено КП: ${res.controlPoints.size} | Поворот: ${res.rotationDeg} град"
        }
    }
    private fun openGeoref() {
        val arr = FloatArray(detectedCps.size * 2)
        detectedCps.forEachIndexed { i, p -> arr[i * 2] = p.x; arr[i * 2 + 1] = p.y }
        startActivity(Intent(this, GeorefActivity::class.java).putExtra("cps", arr))
    }
}
