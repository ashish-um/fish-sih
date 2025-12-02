package com.surendramaran.yolov8tflite.ui.history

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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val context = requireContext()
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        return inflater.inflate(R.layout.fragment_history_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imagePathRaw = arguments?.getString("imagePath") ?: ""
        val timestamp = arguments?.getLong("timestamp") ?: 0L
        val title = arguments?.getString("fishCount")
        val detailsRaw = arguments?.getString("details") ?: ""
        val lat = arguments?.getDouble("lat") ?: 0.0
        val lng = arguments?.getDouble("lng") ?: 0.0

        val viewPager: ViewPager2 = view.findViewById(R.id.detailImagePager)
        val dateView: TextView = view.findViewById(R.id.detailDate)
        val locationView: TextView = view.findViewById(R.id.detailLocation)
        val countsView: TextView = view.findViewById(R.id.detailCounts)
        val rawView: TextView = view.findViewById(R.id.detailRaw)
        val mapCard: View = view.findViewById(R.id.mapCard)
        miniMap = view.findViewById(R.id.miniMap)

        // Split paths and descriptions
        val imagePaths = imagePathRaw.split("|").filter { it.isNotEmpty() }
        val descriptionList = detailsRaw.split(";;;")

        viewPager.adapter = ImagePagerAdapter(imagePaths, descriptionList)

        val sdf = SimpleDateFormat("MMMM dd, yyyy • hh:mm a", Locale.getDefault())
        dateView.text = sdf.format(Date(timestamp))

        if (lat != 0.0 && lng != 0.0) {
            locationView.text = String.format("Lat: %.4f, Lng: %.4f", lat, lng)
            mapCard.visibility = View.VISIBLE
            setupMiniMap(lat, lng)
        } else {
            locationView.text = "Location data not available"
            mapCard.visibility = View.GONE
        }

        countsView.text = title

        // Show raw details string as fallback in the bottom view
        rawView.text = detailsRaw.replace(";;;", "\n\n")
    }

    private fun setupMiniMap(lat: Double, lng: Double) {
        miniMap?.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(10.0)
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
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_image, parent, false)
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

            // Set specific description for this image
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