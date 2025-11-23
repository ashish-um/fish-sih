package com.surendramaran.yolov8tflite

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File

class MapFragment : Fragment() {

    private lateinit var map: MapView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val context = requireContext()

        // --- THE FIX FOR ANDROID 13+ ---
        // Configure Osmdroid to use App-Specific Storage (No Permissions Needed)
        Configuration.getInstance().userAgentValue = context.packageName

        // Set the base path to: /storage/emulated/0/Android/data/your.package.name/files/osmdroid
        val basePath = File(context.getExternalFilesDir(null), "osmdroid")
        Configuration.getInstance().osmdroidBasePath = basePath

        // Set the tile cache to a subdirectory
        val tileCache = File(basePath, "tiles")
        Configuration.getInstance().osmdroidTileCache = tileCache

        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        map = view.findViewById(R.id.map)

        // 1. SETUP MAP (Standard Google-style)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // 2. ENABLE CACHING
        // Since we set the path above, downloaded tiles are saved automatically to that private folder.
        map.setUseDataConnection(true)
        map.overlayManager.tilesOverlay.isEnabled = true

        // 3. Center Map on India
        val indiaCenter = GeoPoint(20.5937, 78.9629)
        map.controller.setZoom(5.5)
        map.controller.setCenter(indiaCenter)

        // 4. Load Your ISRO Layers
        loadOfflineLayers()

        // Optional: Center Button Logic
        view.findViewById<View>(R.id.btn_center_map)?.setOnClickListener {
            map.controller.animateTo(indiaCenter)
            map.controller.setZoom(5.5)
        }
    }

    private fun loadOfflineLayers() {
        try {
            // Adjust file names if yours are different
            // Note: Ensure these files exist in app/src/main/assets/
            parseGeoJson("indiaeez.json", Color.RED, 3f, isPolygon = true)
            parseGeoJson("sector_new.json", Color.parseColor("#FF5722"), 2f, isPolygon = true, fillColor = 0x11FF5722)
            parseGeoJson("pfz.json", Color.YELLOW, 6f, isPolygon = false)
            loadLandingCenters("landing.json")

        } catch (e: Exception) {
            Log.e("MapFragment", "Error loading layers", e)
            // Optional: Show a toast only if debugging
            // Toast.makeText(context, "Error loading map data", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseGeoJson(filename: String, color: Int, width: Float, isPolygon: Boolean, fillColor: Int? = null) {
        try {
            val jsonString = requireContext().assets.open(filename).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val features = json.getJSONArray("features")

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val type = geometry.getString("type")
                val coordinates = geometry.getJSONArray("coordinates")

                when (type) {
                    "Polygon" -> {
                        // Single Polygon: [Ring1, Ring2...]
                        // We usually only care about the outer ring (Index 0)
                        drawPolygon(coordinates.getJSONArray(0), color, width, fillColor)
                    }
                    "MultiPolygon" -> {
                        // Array of Polygons: [ [Ring1...], [Ring1...] ]
                        for (k in 0 until coordinates.length()) {
                            val polygonCoords = coordinates.getJSONArray(k)
                            drawPolygon(polygonCoords.getJSONArray(0), color, width, fillColor)
                        }
                    }
                    "LineString" -> {
                        // Single Line: [ [lon, lat], [lon, lat] ]
                        drawLine(coordinates, color, width)
                    }
                    "MultiLineString" -> {
                        // Array of Lines: [ [[lon, lat]...], [[lon, lat]...] ]
                        for (k in 0 until coordinates.length()) {
                            drawLine(coordinates.getJSONArray(k), color, width)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MapFragment", "Failed to parse $filename", e)
        }
    }

    // Helper to draw a single Polygon
    private fun drawPolygon(coordArray: org.json.JSONArray, color: Int, width: Float, fillColor: Int?) {
        val points = ArrayList<GeoPoint>()
        for (j in 0 until coordArray.length()) {
            val p = coordArray.getJSONArray(j)
            // GeoJSON is [Lon, Lat], Osmdroid needs [Lat, Lon]
            points.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
        }
        val polygon = Polygon()
        polygon.points = points
        polygon.strokeColor = color
        polygon.strokeWidth = width
        polygon.fillColor = fillColor ?: Color.TRANSPARENT
        map.overlays.add(polygon)
    }

    // Helper to draw a single Line
    private fun drawLine(coordArray: org.json.JSONArray, color: Int, width: Float) {
        val points = ArrayList<GeoPoint>()
        for (j in 0 until coordArray.length()) {
            val p = coordArray.getJSONArray(j)
            points.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
        }
        val line = Polyline()
        line.setPoints(points)
        line.color = color
        line.width = width
        line.paint.strokeCap = Paint.Cap.ROUND
        map.overlays.add(line)
    }

    private fun loadLandingCenters(filename: String) {
        try {
            val jsonString = requireContext().assets.open(filename).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val features = json.getJSONArray("features")
            val iconDrawable = createSmallDot(Color.MAGENTA)

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val coord = geometry.getJSONArray("coordinates")
                val lat = coord.getDouble(1)
                val lon = coord.getDouble(0)
                val name = feature.optJSONObject("properties")?.optString("name") ?: "Port"

                val marker = Marker(map)
                marker.position = GeoPoint(lat, lon)
                marker.icon = iconDrawable
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                marker.title = name

                map.overlays.add(marker)
            }
        } catch (e: Exception) {
            Log.e("MapFragment", "Failed to load ports", e)
        }
    }

    private fun createSmallDot(color: Int): BitmapDrawable {
        val size = 24
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 4, paint)
        return BitmapDrawable(resources, bitmap)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}