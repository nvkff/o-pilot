package io.github.nvkff.opilot

import android.graphics.*
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GeminiCourseResult(val points: List<PointF>, val labels: List<String>, val finish: PointF?, val legendCount: Int, val err: String? = null)

object GeminiCourseAnalyzer {
    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private fun tag(i: Int) = if (i < 26) ALPHA[i].toString() else "${ALPHA[i / 26 - 1]}${ALPHA[i % 26]}"

    private fun makeLabeledJpeg(src: Bitmap, pts: List<PointF>): ByteArray {
        val maxS = 1800f; val sc = if (maxOf(src.width, src.height) > maxS) maxS / maxOf(src.width, src.height) else 1f
        val w = (src.width * sc).toInt(); val h = (src.height * sc).toInt()
        val bmp = Bitmap.createScaledBitmap(src, w, h, true)
        val c = Canvas(bmp)
        val bgP = Paint().apply { color = 0xCC000000.toInt(); style = Paint.Style.FILL }
        val txP = Paint().apply { color = Color.CYAN; textSize = (w * 0.022f).coerceAtLeast(22f); isFakeBoldText = true; isAntiAlias = true }
        val dotP = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL }
        pts.forEachIndexed { i, p ->
            val x = p.x * sc; val y = p.y * sc; val t = tag(i)
            c.drawCircle(x, y, 6f, dotP)
            val tw = txP.measureText(t)
            c.drawRect(x + 8f, y - txP.textSize, x + 14f + tw, y + 4f, bgP)
            c.drawText(t, x + 11f, y - 2f, txP)
        }
        val baos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 88, baos); bmp.recycle()
        return baos.toByteArray()
    }
    fun analyze(bmp: Bitmap, rawPts: List<PointF>, apiKey: String): GeminiCourseResult {
        if (rawPts.isEmpty()) return GeminiCourseResult(rawPts, emptyList(), null, 0, "Нет точек")
        val b64 = Base64.encodeToString(makeLabeledJpeg(bmp, rawPts), Base64.NO_WRAP)
        val prompt = "Orienteering map with cyan letter markers (A,B,C...) on detected circles. Follow magenta lines and numbers (1,2,3...) and read the legend table (seq -> control code like 31,32). Return JSON: {\"course\":[{\"seq\":1,\"marker\":\"A\",\"code\":\"31\"}],\"finish_marker\":\"X\"}"
        val req = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)).put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))))).put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))
        var respText: String? = null; var lastErr = "Network error"
        for (model in listOf("gemini-3.6-flash", "gemini-2.5-flash")) {
            try {
                val u = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                val conn = (u.openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 45000; setRequestProperty("Content-Type", "application/json"); setRequestProperty("x-goog-api-key", apiKey.trim()) }
                conn.outputStream.use { it.write(req.toString().toByteArray()) }
                if (conn.responseCode == 200) { respText = conn.inputStream.bufferedReader().readText(); break }
                else lastErr = "HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText()?.take(120)}"
            } catch (e: Exception) { lastErr = e.message ?: "Err" }
        }
        if (respText == null) return GeminiCourseResult(rawPts, rawPts.indices.map { "${it + 1}" }, null, 0, lastErr)
        return try {
            val txt = JSONObject(respText).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            val obj = JSONObject(txt); val arr = obj.getJSONArray("course"); val finTag = obj.optString("finish_marker", "")
            val tagMap = HashMap<String, Int>(); rawPts.indices.forEach { tagMap[tag(it)] = it }
            val ptsOut = mutableListOf<PointF>(); val lblOut = mutableListOf<String>(); val used = mutableSetOf<Int>()
            val items = (0 until arr.length()).map { arr.getJSONObject(it) }.sortedBy { it.optInt("seq", 999) }
            var legCnt = 0
            for (it in items) {
                val idx = tagMap[it.optString("marker", "").trim()] ?: continue
                if (!used.add(idx)) continue
                val seq = it.optInt("seq", ptsOut.size + 1); val code = it.optString("code", "").trim()
                if (code.isNotEmpty()) legCnt++
                ptsOut.add(rawPts[idx]); lblOut.add(if (code.isNotEmpty()) "$seq ($code)" else "$seq")
            }
            val finIdx = tagMap[finTag.trim()]; val finPt = if (finIdx != null) rawPts[finIdx] else null
            rawPts.indices.forEach { i -> if (i !in used && i != finIdx) { ptsOut.add(rawPts[i]); lblOut.add("?") } }
            GeminiCourseResult(ptsOut, lblOut, finPt, legCnt)
        } catch (e: Exception) { GeminiCourseResult(rawPts, rawPts.indices.map { "${it + 1}" }, null, 0, e.message) }
    }
}
