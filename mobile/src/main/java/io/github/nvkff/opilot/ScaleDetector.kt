package io.github.nvkff.opilot

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object ScaleDetector {
    private val rx = Regex("1\\s*[:\\-]\\s*(\\d{4,5})")
    fun detect(bmp: Bitmap, cb: (Int) -> Unit) {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        client.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { txt ->
                val found = rx.findAll(txt.text).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 1000..50000 } ?: 0
                cb(found)
            }
            .addOnFailureListener { cb(0) }
    }
}
