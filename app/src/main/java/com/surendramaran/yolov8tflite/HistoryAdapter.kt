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
    private val onItemClick: (HistoryItem, View) -> Unit // Update to accept View
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.historyImage)
        val date: TextView = view.findViewById(R.id.historyDate)
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
        holder.counts.text = item.fishCount
        holder.details.text = item.details

        val imgFile = File(item.imagePath)
        if (imgFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            holder.image.setImageBitmap(bitmap)
        } else {
            holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // REQUIRED FOR ANIMATION: Unique Transition Name
        holder.image.transitionName = item.imagePath

        // Pass the ImageView to the click listener
        holder.itemView.setOnClickListener {
            onItemClick(item, holder.image)
        }
    }

    override fun getItemCount() = historyList.size
}