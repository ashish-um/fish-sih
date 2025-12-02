package com.surendramaran.yolov8tflite.ml.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.ml.BoundingBox
import com.surendramaran.yolov8tflite.R
import kotlin.collections.iterator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DrawImages(private val context: Context) {

    data class SpeciesInfo(val a: Double, val b: Double, val ratio: Double)

    private val speciesDB = mapOf(
        "tuna" to SpeciesInfo(0.0149, 2.95, 0.60),
        "salmon" to SpeciesInfo(0.0134, 2.98, 0.55),
        "hilsa" to SpeciesInfo(0.0151, 3.02, 0.40),
        "pomfret" to SpeciesInfo(0.0210, 2.90, 0.15),
        "sardine" to SpeciesInfo(0.0075, 3.08, 0.50),
        "shrimp" to SpeciesInfo(0.0050, 2.80, 0.80),
        "mud crab" to SpeciesInfo(0.2400, 2.75, 0.30),
        "3 spotted crab" to SpeciesInfo(0.1800, 2.80, 0.30),
        "default" to SpeciesInfo(0.0120, 3.00, 0.50)
    )

    private val boxColor = listOf(
        R.color.overlay_orange, R.color.overlay_blue, R.color.overlay_green,
        R.color.overlay_red, R.color.overlay_pink, R.color.overlay_cyan,
        R.color.overlay_purple, R.color.overlay_gray
    )
    private var currentColorBox = 0
    private fun getNextColor(): Int {
        val color = boxColor[currentColorBox]
        currentColorBox = (currentColorBox + 1) % boxColor.size
        return color
    }

    fun invoke(
        original: Bitmap,
        success: Success,
        coinResults: List<SegmentationResult>,
        isSeparateOut: Boolean,
        isMaskOut: Boolean,
        speciesBoxes: List<BoundingBox>,
        pixelsPerCm: Float,
        isMarkerDetected: Boolean
    ) : List<AnalysisResult> {

        val width = original.width
        val height = original.height

        // 1. CAROUSEL MODE (Separate View)
        if (isSeparateOut) {
            val outputList = mutableListOf<AnalysisResult>()
            val theCoin = coinResults.firstOrNull() // Assume 1 coin as per instructions

            // A. If Fish Found -> Create one slide per Fish (with Coin as reference)
            if (success.results.isNotEmpty()) {
                val colorPairs: MutableMap<Int, Int> = mutableMapOf()
                success.results.forEach { colorPairs[it.box.cls] = getNextColor() }

                success.results.forEachIndexed { index, fishResult ->
                    val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val sb = StringBuilder()

                    // Draw Coin (Reference) if exists
                    if (theCoin != null) {
                        val coinDesc = applyTransparentOverlay(
                            context, overlay, theCoin,
                            R.color.white,
                            emptyList(),
                            pixelsPerCm,
                            isCoin = true
                        )
                        sb.append(context.getString(R.string.reference_coin_description, coinDesc))
                    }

                    // Draw Target Fish
                    val fishDesc = applyTransparentOverlay(
                        context, overlay, fishResult,
                        colorPairs[fishResult.box.cls] ?: R.color.primary,
                        speciesBoxes,
                        pixelsPerCm,
                        isCoin = false
                    )
                    sb.append(context.getString(R.string.fish_description, index + 1, fishDesc))

                    outputList.add(AnalysisResult(original, overlay, sb.toString()))
                }
            }
            // B. No Fish? Just show Coin slide (if coin exists)
            else if (theCoin != null) {
                val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val coinDesc = applyTransparentOverlay(
                    context, overlay, theCoin,
                    R.color.white,
                    emptyList(),
                    pixelsPerCm,
                    isCoin = true
                )
                outputList.add(AnalysisResult(original, overlay, context.getString(R.string.reference_only_description, coinDesc)))
            }

            return outputList
        }

        // 2. COMBINED MODE (One image, all objects)
        else {
            if (success.results.isEmpty() && coinResults.isEmpty()) return emptyList()

            val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val sb = StringBuilder()

            // Draw Coin
            coinResults.forEach { result ->
                val desc = applyTransparentOverlay(
                    context, combined, result,
                    R.color.white,
                    emptyList(),
                    pixelsPerCm,
                    isCoin = true
                )
                sb.append(context.getString(R.string.reference_description, desc))
            }

            // Draw Fish
            val colorPairs: MutableMap<Int, Int> = mutableMapOf()
            success.results.forEach { colorPairs[it.box.cls] = getNextColor() }

            success.results.forEachIndexed { index, result ->
                val desc = applyTransparentOverlay(
                    context, combined, result,
                    colorPairs[result.box.cls] ?: R.color.primary,
                    speciesBoxes,
                    pixelsPerCm,
                    isCoin = false
                )
                sb.append(context.getString(R.string.fish_description, index + 1, desc))
            }

            return listOf(AnalysisResult(original, combined, sb.toString()))
        }
    }

    private fun applyTransparentOverlay(
        context: Context,
        overlay: Bitmap,
        segmentationResult: SegmentationResult,
        overlayColorResId: Int,
        speciesBoxes: List<BoundingBox>,
        pixelsPerCm: Float,
        isCoin: Boolean
    ): String {
        val width = overlay.width
        val height = overlay.height
        val overlayColor = ContextCompat.getColor(context, overlayColorResId)

        // Pixel Painting
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (segmentationResult.mask[y][x] > 0) {
                    overlay.setPixel(x, y, applyTransparentOverlayColor(overlayColor))
                }
            }
        }

        // Draw Box
        val canvas = Canvas(overlay)
        val boxPaint = Paint().apply {
            color = if (isCoin) Color.YELLOW else Color.WHITE
            strokeWidth = 4F
            style = Paint.Style.STROKE
        }
        val textPaint = Paint().apply {
            color = if (isCoin) Color.YELLOW else Color.WHITE
            style = Paint.Style.FILL
            textSize = 28f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        val box = segmentationResult.box
        val left = box.x1 * width
        val top = box.y1 * height
        val right = box.x2 * width
        val bottom = box.y2 * height

        canvas.drawRect(left, top, right, bottom, boxPaint)

        // Math & Details
        val wPx = (right - left)
        val hPx = (bottom - top)
        val lengthPx = maxOf(wPx, hPx)
        val widthPx = minOf(wPx, hPx)

        val lengthCm = (lengthPx / pixelsPerCm).toDouble()
        val widthCm = (widthPx / pixelsPerCm).toDouble()

        var displayText = ""
        var detailedInfo = ""

        if (isCoin) {
            displayText = context.getString(R.string.coin_display_text, f(lengthCm))
            detailedInfo = context.getString(R.string.coin_detailed_info, f(lengthCm), f(widthCm), f(lengthPx.toDouble()/2.7))
        } else {
            // Fish Identification
            var bestName = context.getString(R.string.unknown)
            if (speciesBoxes.isNotEmpty()) {
                val maskRect = RectF(box.x1, box.y1, box.x2, box.y2)
                var maxIoU = 0.0f
                for (sBox in speciesBoxes) {
                    val sRect = RectF(sBox.x1, sBox.y1, sBox.x2, sBox.y2)
                    val iou = calculateIoU(maskRect, sRect)
                    if (iou > maxIoU && iou > 0.1) {
                        maxIoU = iou
                        bestName = sBox.clsName
                    }
                }
            } else {
                bestName = box.clsName
            }

            var bio = speciesDB["default"]!!
            for ((key, value) in speciesDB) {
                if (bestName.contains(key, ignoreCase = true)) {
                    bio = value
                    break
                }
            }

            val weightG = bio.a * lengthCm.pow(bio.b)
            val areaCm2 = lengthCm * widthCm * 0.65
            val depthCm = widthCm * bio.ratio
            val volumeCm3 = areaCm2 * depthCm

            displayText = context.getString(R.string.fish_display_text, bestName, f0(weightG))

            detailedInfo = context.getString(R.string.fish_detailed_info, bestName, f(lengthCm), f(widthCm), bio.a, bio.b, f0(weightG), f0(volumeCm3))
        }

        val xPos = left
        var yPos = top - 10
        if (yPos < 30) yPos = top + 40

        canvas.drawText(displayText, xPos, yPos, textPaint)

        return detailedInfo
    }

    private fun calculateIoU(r1: RectF, r2: RectF): Float {
        val intersectLeft = max(r1.left, r2.left)
        val intersectTop = max(r1.top, r2.top)
        val intersectRight = min(r1.right, r2.right)
        val intersectBottom = min(r1.bottom, r2.bottom)
        if (intersectRight < intersectLeft || intersectBottom < intersectTop) return 0f
        val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val r1Area = (r1.right - r1.left) * (r1.bottom - r1.top)
        val r2Area = (r2.right - r2.left) * (r2.bottom - r2.top)
        return intersectionArea / (r1Area + r2Area - intersectionArea)
    }

    private fun f(value: Double) = String.format("%.1f", value)
    private fun f0(value: Double) = String.format("%.0f", value)

    private fun applyTransparentOverlayColor(color: Int): Int {
        val alpha = 96
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    private fun maskOut(image: Bitmap, mask: Array<IntArray>) : Bitmap {
        if (image.height != mask.size || image.width != mask[0].size) return image
        val result = Bitmap.createBitmap(image.width, image.height, image.config)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                result.setPixel(x, y, if (mask[y][x] > 0) pixel else Color.BLACK)
            }
        }
        return result
    }

    private fun Array<Array<IntArray>>.combineMasks(): Array<IntArray> {
        if (this.isEmpty() || this.first().isEmpty()) return emptyArray()
        val h = first().size
        val w = first()[0].size
        val result = Array(h) { IntArray(w) }
        for (mask in this) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (mask[y][x] > 0) result[y][x] = 1
                }
            }
        }
        return result
    }
}