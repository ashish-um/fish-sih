package com.surendramaran.yolov8tflite.ui.camera

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.surendramaran.yolov8tflite.R

data class DetectionItem(
    val fishName: String,
    val count: Int
)

class DetectionAdapter : RecyclerView.Adapter<DetectionAdapter.DetectionViewHolder>() {

    private var detectionItems = listOf<DetectionItem>()

    fun updateDetections(items: List<DetectionItem>) {
        detectionItems = items
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DetectionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_detection, parent, false)
        return DetectionViewHolder(view)
    }

    override fun onBindViewHolder(holder: DetectionViewHolder, position: Int) {
        holder.bind(detectionItems[position])
    }

    override fun getItemCount(): Int = detectionItems.size

    class DetectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fishName: TextView = itemView.findViewById(R.id.fishName)
        private val fishCount: TextView = itemView.findViewById(R.id.fishCount)

        fun bind(item: DetectionItem) {
            fishName.text = item.fishName
            fishCount.text = item.count.toString()
        }
    }
}