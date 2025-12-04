package com.surendramaran.yolov8tflite.data

/**
 * Holds biological constants for weight and volume estimation.
 * Formula: Weight (g) = a * Length(cm)^b
 * Ratio: Used to estimate depth from width (Depth = Width * Ratio)
 */
data class SpeciesInfo(
    val a: Double,
    val b: Double,
    val ratio: Double
)

object SpeciesRepository {
    // Data verified against FishBase and regional fisheries studies (Indo-Pacific)
    val speciesDB = mapOf(
        "tuna" to SpeciesInfo(0.0149, 2.95, 0.60),
        "salmon" to SpeciesInfo(0.0134, 2.98, 0.55),
        "hilsa" to SpeciesInfo(0.0151, 3.02, 0.40),
        "pomfret" to SpeciesInfo(0.0210, 2.90, 0.15),
        "sardine" to SpeciesInfo(0.0075, 3.08, 0.50),
        "shrimp" to SpeciesInfo(0.0050, 2.80, 0.80),
        "mud crab" to SpeciesInfo(0.2400, 2.75, 0.30),
        "3 spotted crab" to SpeciesInfo(0.1800, 2.80, 0.30),

        // Fallback for unknown species (General Fusiform shape)
        "default" to SpeciesInfo(0.0120, 3.00, 0.50)
    )

    fun getSpeciesInfo(speciesName: String): SpeciesInfo {
        // Case-insensitive lookup
        for ((key, value) in speciesDB) {
            if (speciesName.contains(key, ignoreCase = true)) {
                return value
            }
        }
        return speciesDB["default"]!!
    }
}