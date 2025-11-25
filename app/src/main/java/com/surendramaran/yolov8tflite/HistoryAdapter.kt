package com.surendramaran.yolov8tflite

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val historyList: List<HistoryItem>,
    private val onItemClick: (HistoryItem, View) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.historyImage)
        val date: TextView = view.findViewById(R.id.historyDate)
        val location: TextView = view.findViewById(R.id.historyLocation)
        val counts: TextView = view.findViewById(R.id.historyCounts)
        val details: TextView = view.findViewById(R.id.historyDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = historyList[position]

        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
        holder.date.text = sdf.format(Date(item.timestamp))

        // UPDATED: Show Place Name (e.g. "Kanpur, Uttar Pradesh")
        holder.location.text = item.placeName
        // Only show if it has valid content (not default failure msg)
        holder.location.visibility = if (item.placeName.isNotEmpty() && item.placeName != "Location not available") View.VISIBLE else View.GONE

        holder.counts.text = item.fishCount
        holder.details.text = item.details

        val imgFile = File(item.imagePath)
        if (imgFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            holder.image.setImageBitmap(bitmap)
        } else {
            holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        holder.image.transitionName = item.imagePath

        holder.itemView.setOnClickListener {
            onItemClick(item, holder.image)
        }
    }

    override fun getItemCount() = historyList.size
}