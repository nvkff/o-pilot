package io.github.nvkff.opilot

import android.graphics.Bitmap
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

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            val scanRes = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val uri = scanRes?.pages?.firstOrNull()?.imageUri ?: return@registerForActivityResult
            processUri(uri, isPdf = false)
        }
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) processUri(uri, isPdf = false)
    }

    private val pdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) processUri(uri, isPdf = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)
        ivPreview = findViewById(R.id.ivPreview)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        val cvReady = OpenCVLoader.initLocal()
        tvStatus.text = if (cvReady) "OpenCV 4.12 готов к работе" else "Ошибка OpenCV"
        findViewById<Button>(R.id.btnScanCamera).setOnClickListener { startScanner() }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { askGalleryMode() }
        findViewById<Button>(R.id.btnImportPdf).setOnClickListener { pdfLauncher.launch(arrayOf("application/pdf")) }
        btnAnalyze.setOnClickListener { Toast.makeText(this, "Анализ OpenCV: шаг 1.3", Toast.LENGTH_SHORT).show() }
    }

    private fun askGalleryMode() {
        AlertDialog.Builder(this)
            .setTitle("Загрузка из галереи")
            .setMessage("Если фото снято под углом, лучше открыть его через сканер (иконка галереи слева внизу).")
            .setPositiveButton("Готовый скан") { _, _ -> galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            .setNeutralButton("Открыть сканер") { _, _ -> startScanner() }
            .show()
    }

    private fun startScanner() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { sender -> scannerLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener { e -> Toast.makeText(this, "Сканер: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun processUri(uri: Uri, isPdf: Boolean) {
        tvStatus.text = "Обработка карты..."
        CoroutineScope(Dispatchers.Main).launch {
            val bmp = withContext(Dispatchers.IO) {
                val loaded = if (isPdf) MapLoader.loadPdfFirstPage(this@MainActivity, uri) else MapLoader.loadBitmapFromUri(this@MainActivity, uri)
                loaded?.also { MapLoader.saveWorkingMap(this@MainActivity, it) }
            }
            if (bmp != null) {
                currentBitmap = bmp
                ivPreview.setImageBitmap(bmp)
                btnAnalyze.visibility = View.VISIBLE
                tvStatus.text = "Карта загружена: ${bmp.width}x${bmp.height} px"
            } else {
                tvStatus.text = "Не удалось открыть файл"
            }
        }
    }
}
