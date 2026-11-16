package io.github.nvkff.opilot

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.opencv.android.OpenCVLoader

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val cvReady = OpenCVLoader.initLocal()
        tvStatus.text = if (cvReady) "OpenCV 4.12 готов к работе" else "Ошибка OpenCV"
        val msg = { s: String -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.btnScanCamera).setOnClickListener { msg("Камера: шаг 1.2") }
        findViewById<Button>(R.id.btnPickGallery).setOnClickListener { msg("Галерея: шаг 1.2") }
        findViewById<Button>(R.id.btnImportPdf).setOnClickListener { msg("PDF: шаг 1.2") }
    }
}
