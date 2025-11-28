package com.surendramaran.yolov8tflite.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.BoundingBox
import com.surendramaran.yolov8tflite.R
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
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
        isSeparateOut: Boolean,
        isMaskOut: Boolean,
        speciesBoxes: List<BoundingBox>,
        pixelsPerCm: Float,
        isMarkerDetected: Boolean // <--- NEW PARAMETER
    ) : List<AnalysisResult> {

        // CASE 1: SEPARATE OUT
        if (isSeparateOut) {
            if (isMaskOut) {
                return success.results.map {
                    AnalysisResult(maskOut(original, it.mask), null, "Mask Only")
                }
            } else {
                val results = success.results
                if (results.isEmpty()) return emptyList()

                val width = results.first().mask[0].size
                val height = results.first().mask.size

                return success.results.map {
                    val new = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val infoText = applyTransparentOverlay(
                        context, new, it, R.color.overlay_pink,
                        speciesBoxes, pixelsPerCm, isMarkerDetected
                    )
                    AnalysisResult(original, new, infoText)
                }
            }
        }
        // CASE 2: COMBINED VIEW
        else {
            if (isMaskOut) {
                val list = success.results.map { it.mask }.toTypedArray()
                return listOf(AnalysisResult(maskOut(original, list.combineMasks()), null, "Combined Masks"))
            } else {
                val results = success.results
                if (results.isEmpty()) return emptyList()

                val colorPairs: MutableMap<Int, Int> = mutableMapOf()
                results.forEach { colorPairs[it.box.cls] = getNextColor() }

                val width = results.first().mask[0].size
                val height = results.first().mask.size
                val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                val allDescriptions = StringBuilder()
                results.forEachIndexed { index, result ->
                    val text = applyTransparentOverlay(
                        context, combined, result,
                        colorPairs[result.box.cls] ?: R.color.primary,
                        speciesBoxes, pixelsPerCm, isMarkerDetected
                    )
                    if (text.isNotEmpty()) {
                        allDescriptions.append("Fish ${index + 1}:\n$text\n\n")
                    }
                }
                return listOf(AnalysisResult(original, combined, allDescriptions.toString().trim()))
            }
        }
    }

    private fun maskOut(image: Bitmap, mask: Array<IntArray>) : Bitmap {
        if (image.height != mask.size || image.width != mask[0].size) {
            throw IllegalArgumentException("Mask dimensions must match image dimensions")
        }
        val result = Bitmap.createBitmap(image.width, image.height, image.config)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                result.setPixel(x, y, if (mask[y][x] == 1) pixel else Color.BLACK)
            }
        }
        return result
    }

    private fun Array<Array<IntArray>>.combineMasks(): Array<IntArray> {
        if (this.isEmpty() || this.first().isEmpty()) return emptyArray()
        val h = size; val w = first().first().size
        val result = Array(h) { IntArray(w) }
        for (mask in this) {
            if (mask.size != h || mask[0].size != w) continue
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (mask[y][x] > 0) result[y][x] = 1
                }
            }
        }
        return result
    }

    private fun applyTransparentOverlay(
        context: Context,
        overlay: Bitmap,
        segmentationResult: SegmentationResult,
        overlayColorResId: Int,
        speciesBoxes: List<BoundingBox>,
        pixelsPerCm: Float,
        isMarkerDetected: Boolean
    ): String {
        var detectedInfo = ""
        val width = overlay.width
        val height = overlay.height
        val overlayColor = ContextCompat.getColor(context, overlayColorResId)
        val maskData = ByteArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val maskValue = segmentationResult.mask[y][x]
                if (maskValue > 0) {
                    overlay.setPixel(x, y, applyTransparentOverlayColor(overlayColor))
                    maskData[y * width + x] = 255.toByte()
                } else {
                    maskData[y * width + x] = 0
                }
            }
        }

        try {
            val maskMat = Mat(height, width, CvType.CV_8UC1)
            maskMat.put(0, 0, maskData)
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(maskMat, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val canvas = Canvas(overlay)
            val paintBox = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }

            val paintText = Paint().apply {
                color = Color.WHITE
                textSize = 28f
                style = Paint.Style.FILL
                isAntiAlias = true
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }

            // Paint for WARNING text on image
            val paintWarning = Paint().apply {
                color = Color.RED
                textSize = 36f
                style = Paint.Style.FILL
                isAntiAlias = true
                setShadowLayer(3f, 0f, 0f, Color.WHITE)
            }

            // Draw Warning on Canvas if missing (Once per image is enough, but loop is okay)
            if (!isMarkerDetected) {
                canvas.drawText("⚠️ ESTIMATED SCALE", 20f, 50f, paintWarning)
            }

            for (contour in contours) {
                if (Imgproc.contourArea(contour) > 100) {
                    val point2f = MatOfPoint2f(*contour.toArray())
                    val rotatedRect = Imgproc.minAreaRect(point2f)

                    val points = arrayOfNulls<Point>(4)
                    rotatedRect.points(points)
                    val path = Path()
                    path.moveTo(points[0]!!.x.toFloat(), points[0]!!.y.toFloat())
                    (1..3).forEach { path.lineTo(points[it]!!.x.toFloat(), points[it]!!.y.toFloat()) }
                    path.close()
                    canvas.drawPath(path, paintBox)

                    val rect = Imgproc.boundingRect(contour)
                    val maskBoxNorm = RectF(
                        rect.x.toFloat() / width, rect.y.toFloat() / height,
                        (rect.x + rect.width).toFloat() / width, (rect.y + rect.height).toFloat() / height
                    )

                    var bestName = "Unknown"
                    var maxIoU = 0.0f
                    for (box in speciesBoxes) {
                        val speciesRect = RectF(box.x1, box.y1, box.x2, box.y2)
                        val iou = calculateIoU(maskBoxNorm, speciesRect)
                        if (iou > maxIoU && iou > 0.1) {
                            maxIoU = iou
                            bestName = box.clsName
                        }
                    }

                    val wPx = rotatedRect.size.width
                    val hPx = rotatedRect.size.height
                    val lengthPx = max(wPx, hPx)
                    val widthPx = min(wPx, hPx)

                    val lengthCm = lengthPx / pixelsPerCm
                    val widthCm = widthPx / pixelsPerCm

                    var bio = speciesDB["default"]!!
                    for ((key, value) in speciesDB) {
                        if (bestName.contains(key, ignoreCase = true)) {
                            bio = value
                            break
                        }
                    }

                    val weightG = bio.a * lengthCm.pow(bio.b)

                    // Volume Calculation
                    val areaPx = Imgproc.contourArea(contour)
                    val areaCm2 = areaPx / (pixelsPerCm * pixelsPerCm)
                    val estimatedDepthCm = widthCm * bio.ratio
                    val volumeCm3 = areaCm2 * estimatedDepthCm

                    // PREPEND WARNING TO DESCRIPTION
                    val warningPrefix = if (!isMarkerDetected) "⚠️ NO MARKER - ESTIMATED SCALE (50px/cm)\n" else ""

                    detectedInfo = warningPrefix + """
                        Species: $bestName
                        Box: L:${f(lengthCm)}cm x W:${f(widthCm)}cm
                        Const: a=${bio.a}, b=${bio.b}
                        Est: ${f(volumeCm3)}cm³ | ${f0(weightG)}g
                    """.trimIndent()

                    val overlayText = "$bestName | L:${f(lengthCm)} W:${f(widthCm)} | ${f0(weightG)}g"
                    canvas.drawText(overlayText, rect.x.toFloat(), rect.y.toFloat() - 10, paintText)
                }
                contour.release()
            }
            maskMat.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return detectedInfo
    }

    private fun f(value: Double) = String.format("%.1f", value)
    private fun f0(value: Double) = String.format("%.0f", value)

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

    private fun applyTransparentOverlayColor(color: Int): Int {
        val alpha = 96
        val red = Color.red(color); val green = Color.green(color); val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }
}