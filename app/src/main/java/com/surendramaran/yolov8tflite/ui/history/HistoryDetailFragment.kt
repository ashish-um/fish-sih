package com.surendramaran.yolov8tflite.ui.history

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.surendramaran.yolov8tflite.R
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

    // Same color palette as CameraFragment to ensure matching colors
    private val boxColors = listOf(
        Color.parseColor("#FF5722"), // Orange
        Color.parseColor("#2979FF"), // Blue
        Color.parseColor("#00C853"), // Green
        Color.parseColor("#FFD600"), // Yellow
        Color.parseColor("#AA00FF"), // Purple
        Color.parseColor("#E91E63"), // Pink
        Color.parseColor("#00BCD4"), // Cyan
        Color.parseColor("#3E2723")  // Brown
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val context = requireContext()
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        return inflater.inflate(R.layout.fragment_history_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        val imagePathRaw = arguments?.getString("imagePath") ?: ""
        val timestamp = arguments?.getLong("timestamp") ?: 0L
        val titleRaw = arguments?.getString("fishCount") ?: ""
        val detailsRaw = arguments?.getString("details") ?: ""

        val placeName = arguments?.getString("placeName") ?: getString(R.string.unknown)
        val lat = arguments?.getFloat("lat")?.toDouble() ?: 0.0
        val lng = arguments?.getFloat("lng")?.toDouble() ?: 0.0

        val viewPager: ViewPager2 = view.findViewById(R.id.detailImagePager)
        val titleView: TextView = view.findViewById(R.id.detailTitle)
        val dateView: TextView = view.findViewById(R.id.detailDate)

        val placeNameView: TextView = view.findViewById(R.id.detailPlaceName)
        val coordsView: TextView = view.findViewById(R.id.detailCoords)

        val rawView: TextView = view.findViewById(R.id.detailRaw)
        val mapCard: View = view.findViewById(R.id.mapCard)
        val chipGroup: ChipGroup = view.findViewById(R.id.resultChipGroup)

        miniMap = view.findViewById(R.id.miniMap)

        val imagePaths = imagePathRaw.split("|").filter { it.isNotEmpty() }
        val descriptionList = detailsRaw.split(";;;")
        viewPager.adapter = ImagePagerAdapter(imagePaths, descriptionList)

        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        dateView.text = sdf.format(Date(timestamp))

        if (titleRaw.contains(",") || titleRaw.contains("%")) {
            titleView.text = "Analysis Report"
        } else {
            titleView.text = titleRaw.ifEmpty { "Detection Result" }
        }

        // --- UPDATED: Chip Logic for Multi-Color ---
        chipGroup.removeAllViews()
        if (titleRaw.isNotEmpty()) {
            val items = titleRaw.split(",").map { it.trim() }

            items.forEachIndexed { index, item ->
                if (item.isNotEmpty()) {
                    val chip = Chip(requireContext())
                    chip.text = item

                    // "Eyes" summary usually comes last. We color it distinctively.
                    if (item.startsWith("Eyes:")) {
                        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.white))
                        chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.overlay_red))
                        chip.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.overlay_red))
                        chip.chipStrokeWidth = 2f
                    } else {
                        // Apply color cycle for fish detections
                        // The index here matches the index in the saved list, which matches the drawing loop
                        val color = boxColors[index % boxColors.size]
                        chip.chipBackgroundColor = ColorStateList.valueOf(color)
                        chip.setTextColor(Color.WHITE)
                        chip.chipStrokeWidth = 0f
                    }

                    chip.textSize = 14f
                    chip.isClickable = false
                    chipGroup.addView(chip)
                }
            }
        } else {
            val chip = Chip(requireContext())
            chip.text = "No Detections"
            chipGroup.addView(chip)
        }

        if (lat != 0.0 && lng != 0.0) {
            placeNameView.text = if (placeName != getString(R.string.unknown)) placeName else "Unknown Location"
            coordsView.text = String.format("%.4f, %.4f", lat, lng)

            mapCard.visibility = View.VISIBLE
            setupMiniMap(lat, lng)
        } else {
            mapCard.visibility = View.GONE
        }

        rawView.text = detailsRaw.replace(";;;", "\n\n")
    }

    private fun setupMiniMap(lat: Double, lng: Double) {
        miniMap?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(14.0)
            val point = GeoPoint(lat, lng)
            controller.setCenter(point)

            val marker = Marker(this)
            marker.position = point
            marker.icon = createSmallDot(Color.RED)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            overlays.add(marker)

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    private fun createSmallDot(color: Int): BitmapDrawable {
        val size = 30
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            this.color = color; style = Paint.Style.FILL; isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return BitmapDrawable(resources, bitmap)
    }

    override fun onResume() { super.onResume(); miniMap?.onResume() }
    override fun onPause() { super.onPause(); miniMap?.onPause() }

    class ImagePagerAdapter(private val paths: List<String>, private val descriptions: List<String>) : RecyclerView.Adapter<ImagePagerAdapter.ImgViewHolder>() {
        class ImgViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val img: ImageView = itemView.findViewById(R.id.image)
            val desc: TextView = itemView.findViewById(R.id.tvDescription)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImgViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_image_detail, parent, false)
            return ImgViewHolder(view)
        }
        override fun onBindViewHolder(holder: ImgViewHolder, position: Int) {
            val file = File(paths[position])
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                holder.img.setImageBitmap(bmp)
            } else {
                holder.img.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            val detailText = descriptions.getOrElse(position) { "" }
            if (detailText.isNotEmpty()) {
                holder.desc.text = detailText
                holder.desc.visibility = View.VISIBLE
            } else {
                holder.desc.visibility = View.GONE
            }
        }
        override fun getItemCount() = paths.size
    }
}