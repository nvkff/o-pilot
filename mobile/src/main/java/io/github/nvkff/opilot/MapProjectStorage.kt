package io.github.nvkff.opilot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

data class SavedCp(val id: Int, val label: String, val lat: Double, val lon: Double, val bx: Float, val by: Float, var taken: Boolean = false)
data class MapProject(val id: String, var name: String, val centerLat: Double, val centerLon: Double, val widthMeters: Double, val rotationDeg: Float, val georeferenced: Boolean, val cps: MutableList<SavedCp>)

object MapProjectStorage {
    private fun dir(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun latLonToPixel(p: MapProject, bw: Int, bh: Int, lat: Double, lon: Double): PointF {
        val pxPerM = bw / p.widthMeters.coerceAtLeast(10.0)
        val sx = (lon - p.centerLon) * 111320.0 * cos(Math.toRadians(p.centerLat)) * pxPerM
        val sy = -(lat - p.centerLat) * 111320.0 * pxPerM
        val rad = Math.toRadians(p.rotationDeg.toDouble())
        val bx = bw / 2.0 + (sx * cos(rad) + sy * sin(rad))
        val by = bh / 2.0 + (-sx * sin(rad) + sy * cos(rad))
        return PointF(bx.toFloat(), by.toFloat())
    }
    fun pixelToLatLon(p: MapProject, bw: Int, bh: Int, bx: Float, by: Float): Pair<Double, Double> {
        val pxPerM = bw / p.widthMeters.coerceAtLeast(10.0)
        val ux = bx - bw / 2.0; val uy = by - bh / 2.0
        val rad = Math.toRadians(p.rotationDeg.toDouble())
        val sx = ux * cos(rad) - uy * sin(rad)
        val sy = ux * sin(rad) + uy * cos(rad)
        val lat = p.centerLat + (-sy / pxPerM) / 111320.0
        val lon = p.centerLon + (sx / pxPerM) / (111320.0 * cos(Math.toRadians(p.centerLat)).coerceAtLeast(0.2))
        return lat to lon
    }
    fun save(ctx: Context, proj: MapProject, bmp: Bitmap? = null) {
        val pDir = File(dir(ctx), proj.id).apply { mkdirs() }
        bmp?.let { FileOutputStream(File(pDir, "map.png")).use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) } }
        val arr = JSONArray()
        proj.cps.forEach { c -> arr.put(JSONObject().put("id", c.id).put("label", c.label).put("lat", c.lat).put("lon", c.lon).put("bx", c.bx.toDouble()).put("by", c.by.toDouble()).put("taken", c.taken)) }
        val obj = JSONObject().put("id", proj.id).put("name", proj.name).put("cLat", proj.centerLat).put("cLon", proj.centerLon).put("wM", proj.widthMeters).put("rot", proj.rotationDeg.toDouble()).put("geo", proj.georeferenced).put("cps", arr)
        File(pDir, "project.json").writeText(obj.toString())
    }
    fun loadAll(ctx: Context): List<MapProject> {
        val res = mutableListOf<MapProject>()
        dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.forEach { d ->
            val f = File(d, "project.json"); if (!f.exists()) return@forEach
            try {
                val o = JSONObject(f.readText()); val arr = o.optJSONArray("cps") ?: JSONArray()
                val cps = mutableListOf<SavedCp>()
                for (i in 0 until arr.length()) { val c = arr.getJSONObject(i); cps.add(SavedCp(c.getInt("id"), c.getString("label"), c.getDouble("lat"), c.getDouble("lon"), c.getDouble("bx").toFloat(), c.getDouble("by").toFloat(), c.optBoolean("taken", false))) }
                res.add(MapProject(o.getString("id"), o.getString("name"), o.getDouble("cLat"), o.getDouble("cLon"), o.getDouble("wM"), o.getDouble("rot").toFloat(), o.optBoolean("geo", true), cps))
            } catch (_: Exception) {}
        }
        return res
    }
    fun loadById(ctx: Context, id: String) = loadAll(ctx).firstOrNull { it.id == id }
    fun loadBitmap(ctx: Context, id: String): Bitmap? { val f = File(File(dir(ctx), id), "map.png"); return if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null }
    fun delete(ctx: Context, id: String) { File(dir(ctx), id).deleteRecursively() }
}
