package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.Date

class MapFragment : Fragment() {

    private lateinit var map: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private lateinit var dbHelper: DatabaseHelper

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            setupLocationOverlay()
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val context = requireContext()
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName

        val basePath = File(context.getExternalFilesDir(null), "osmdroid")
        Configuration.getInstance().osmdroidBasePath = basePath
        val tileCache = File(basePath, "tiles")
        Configuration.getInstance().osmdroidTileCache = tileCache

        return inflater.inflate(R.layout.fragment_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dbHelper = DatabaseHelper(requireContext())
        map = view.findViewById(R.id.map)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setUseDataConnection(true)
        map.overlayManager.tilesOverlay.isEnabled = true

        val indiaCenter = GeoPoint(20.5937, 78.9629)
        map.controller.setZoom(5.5)
        map.controller.setCenter(indiaCenter)

        if (checkLocationPermission()) {
            setupLocationOverlay()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        loadOfflineLayers()

        // NEW: Load saved detections onto the map
        loadSavedDetections()

        view.findViewById<View>(R.id.btn_center_map)?.setOnClickListener {
            val myLoc = locationOverlay?.myLocation
            if (myLoc != null) {
                map.controller.animateTo(myLoc)
                map.controller.setZoom(15.0)
            } else {
                Toast.makeText(context, "Waiting for GPS Signal...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedDetections() {
        val detections = dbHelper.getAllDetections()
        val detectionIcon = createSmallDot(Color.RED, 50) // Red dots for catches

        detections.forEach { item ->
            if (item.lat != 0.0 && item.lng != 0.0) {
                val marker = Marker(map)
                marker.position = GeoPoint(item.lat, item.lng)
                marker.icon = detectionIcon
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                marker.title = "Catch: ${item.fishCount}"
                marker.subDescription = "Date: ${Date(item.timestamp)}"

                // UPDATED: Load image if available to show in the info window
                if (item.imagePath.isNotEmpty()) {
                    try {
                        val imgFile = File(item.imagePath)
                        if (imgFile.exists()) {
                            // Decode file to bitmap
                            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                            if (bitmap != null) {
                                // Set the image property of the marker which osmdroid uses in the info window
                                marker.image = BitmapDrawable(resources, bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MapFragment", "Error loading image for marker", e)
                    }
                }

                map.overlays.add(marker)
            }
        }
        map.invalidate()
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createBlueArrow(): android.graphics.Bitmap {
        val width = 60
        val height = 60
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val paint = Paint()
        paint.color = Color.parseColor("#2979FF")
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true

        val path = android.graphics.Path()
        path.moveTo(width / 2f, 0f)
        path.lineTo(width.toFloat(), height.toFloat())
        path.lineTo(width / 2f, height * 0.75f)
        path.lineTo(0f, height.toFloat())
        path.close()

        canvas.drawPath(path, paint)

        val strokePaint = Paint()
        strokePaint.color = Color.WHITE
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 4f
        strokePaint.isAntiAlias = true
        canvas.drawPath(path, strokePaint)

        return bitmap
    }

    private fun createSmallDot(color: Int, size: Int = 20): BitmapDrawable {
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 6, paint)
        return BitmapDrawable(resources, bitmap)
    }

    private fun setupLocationOverlay() {
        val provider = GpsMyLocationProvider(requireContext())
        provider.addLocationSource(android.location.LocationManager.GPS_PROVIDER)
        provider.addLocationSource(android.location.LocationManager.NETWORK_PROVIDER)

        locationOverlay = MyLocationNewOverlay(provider, map)

        val blueArrow = createBlueArrow()
        locationOverlay?.setDirectionIcon(blueArrow)
        locationOverlay?.setPersonIcon(blueArrow)

        locationOverlay?.enableMyLocation()
        locationOverlay?.enableFollowLocation()
        locationOverlay?.isDrawAccuracyEnabled = true

        map.overlays.add(locationOverlay)
        map.invalidate()
    }

    private fun loadOfflineLayers() {
        try {
            parseGeoJson("indiaeez.json", Color.RED, 3f, isPolygon = true)
            parseGeoJson("sector_new.json", Color.parseColor("#FF5722"), 2f, isPolygon = true, fillColor = 0x11FF5722)
            parseGeoJson("pfz.json", Color.YELLOW, 6f, isPolygon = false)
            loadLandingCenters("landing.json")
        } catch (e: Exception) {
            Log.e("MapFragment", "Error loading layers", e)
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
                    "Polygon" -> drawPolygon(coordinates.getJSONArray(0), color, width, fillColor)
                    "MultiPolygon" -> {
                        for (k in 0 until coordinates.length()) {
                            drawPolygon(coordinates.getJSONArray(k).getJSONArray(0), color, width, fillColor)
                        }
                    }
                    "LineString" -> drawLine(coordinates, color, width)
                    "MultiLineString" -> {
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

    private fun drawPolygon(coordArray: org.json.JSONArray, color: Int, width: Float, fillColor: Int?) {
        val points = ArrayList<GeoPoint>()
        for (j in 0 until coordArray.length()) {
            val p = coordArray.getJSONArray(j)
            points.add(GeoPoint(p.getDouble(1), p.getDouble(0)))
        }
        val polygon = Polygon()
        polygon.points = points
        polygon.strokeColor = color
        polygon.strokeWidth = width
        polygon.fillColor = fillColor ?: Color.TRANSPARENT
        map.overlays.add(polygon)
    }

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

    override fun onResume() {
        super.onResume()
        map.onResume()
        locationOverlay?.enableMyLocation()
        // Reload points so new detections appear immediately
        loadSavedDetections()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        locationOverlay?.disableMyLocation()
    }
}