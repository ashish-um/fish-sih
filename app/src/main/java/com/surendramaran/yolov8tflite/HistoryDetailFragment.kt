package com.surendramaran.yolov8tflite

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.transition.ChangeBounds
import android.transition.ChangeImageTransform
import android.transition.ChangeTransform
import android.transition.Fade
import android.transition.TransitionSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryDetailFragment : Fragment() {

    private var miniMap: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val animationDuration = 300L

        val sharedTransition = TransitionSet().apply {
            addTransition(ChangeBounds())
            addTransition(ChangeTransform())
            addTransition(ChangeImageTransform())
            duration = animationDuration
        }
        sharedElementEnterTransition = sharedTransition
        sharedElementReturnTransition = sharedTransition

        enterTransition = Fade().apply { duration = animationDuration }
        returnTransition = Fade().apply { duration = animationDuration }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Initialize OSMDroid Configuration
        val context = requireContext()
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName

        return inflater.inflate(R.layout.fragment_history_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imagePath = arguments?.getString("imagePath")
        val timestamp = arguments?.getLong("timestamp") ?: 0L
        val fishCount = arguments?.getString("fishCount")
        val details = arguments?.getString("details")
        val placeName = arguments?.getString("placeName")
        val lat = arguments?.getDouble("lat") ?: 0.0
        val lng = arguments?.getDouble("lng") ?: 0.0

        val imageView: ImageView = view.findViewById(R.id.detailImage)
        val dateView: TextView = view.findViewById(R.id.detailDate)
        val locationView: TextView = view.findViewById(R.id.detailLocation)
        val countsView: TextView = view.findViewById(R.id.detailCounts)
        val rawView: TextView = view.findViewById(R.id.detailRaw)

        // Mini Map Elements
        val mapCard: androidx.cardview.widget.CardView = view.findViewById(R.id.mapCard)
        miniMap = view.findViewById(R.id.miniMap)

        imageView.transitionName = imagePath

        val sdf = SimpleDateFormat("MMMM dd, yyyy • hh:mm a", Locale.getDefault())
        dateView.text = sdf.format(Date(timestamp))

        // --- UPDATED LOGIC START ---

        // 1. Always make the Location TextView VISIBLE to prove UI update
        locationView.visibility = View.VISIBLE

        if (lat != 0.0 && lng != 0.0) {
            // Valid Location: Show Name + Map
            val finalText = if (!placeName.isNullOrEmpty() && placeName != "Location not available") {
                placeName
            } else {
                "Lat: $lat, Lng: $lng"
            }
            locationView.text = finalText

            mapCard.visibility = View.VISIBLE
            setupMiniMap(lat, lng)
        } else {
            // No Location: Show explicit message (This proves the layout works!)
            locationView.text = "Location data not available"
            mapCard.visibility = View.GONE
        }

        // --- UPDATED LOGIC END ---

        countsView.text = fishCount
        rawView.text = details

        if (!imagePath.isNullOrEmpty()) {
            val imgFile = File(imagePath)
            if (imgFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun setupMiniMap(lat: Double, lng: Double) {
        miniMap?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false) // Disable interaction for "mini" feel
            controller.setZoom(6.0)
            val point = GeoPoint(lat, lng)
            controller.setCenter(point)

            // Add Marker
            val marker = Marker(this)
            marker.position = point
            marker.icon = createSmallDot(Color.RED)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            overlays.add(marker)
        }
    }

    private fun createSmallDot(color: Int): BitmapDrawable {
        val size = 30
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

    override fun onResume() {
        super.onResume()
        miniMap?.onResume()
    }

    override fun onPause() {
        super.onPause()
        miniMap?.onPause()
    }
}