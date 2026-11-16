package io.github.nvkff.opilot

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class OcrNumber(val num: Int, val pos: PointF)
data class OcrData(val scale: Int, val legend: Map<Int, Int>, val numbers: List<OcrNumber>)

object MapOcr {
    private val rxScale = Regex("1\\s*[:]\\s*(\\d{4,5})")
    private val rxLegend = Regex("\\b(\\d{1,2})\\s+(\\d{2,3})\\b")
    private val rxInt = Regex("^\\d{1,2}$")

    fun analyze(bmp: Bitmap, cb: (OcrData) -> Unit) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { txt ->
                val scale = rxScale.findAll(txt.text).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in 1000..50000 } ?: 0
                val legend = mutableMapOf<Int, Int>()
                for (m in rxLegend.findAll(txt.text)) {
                    val seq = m.groupValues[1].toIntOrNull() ?: continue
                    val code = m.groupValues[2].toIntOrNull() ?: continue
                    if (seq in 1..60) if (code in 31..299) legend[seq] = code
                }
                val nums = mutableListOf<OcrNumber>()
                for (blk in txt.textBlocks) for (line in blk.lines) for (el in line.elements) {
                    val v = el.text.trim()
                    if (rxInt.matches(v)) {
                        val n = v.toIntOrNull() ?: continue
                        val b = el.boundingBox ?: continue
                        if (n in 1..60) nums.add(OcrNumber(n, PointF(b.exactCenterX(), b.exactCenterY())))
                    }
                }
                cb(OcrData(scale, legend, nums))
            }
            .addOnFailureListener { cb(OcrData(0, emptyMap(), emptyList())) }
    }
}
