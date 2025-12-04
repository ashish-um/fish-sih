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
    // UPDATED: Scientifically verified constants (cm/g)
    val speciesDB = mapOf(
        // FishBase (Indo-Pacific)
        "tuna" to SpeciesInfo(0.0145, 3.03, 0.60),

        // Standard Salmonid approximation
        "salmon" to SpeciesInfo(0.0100, 3.05, 0.55),

        // FishBase (Bay of Bengal)
        "hilsa" to SpeciesInfo(0.0158, 2.92, 0.40),

        // FishBase (Geometric Mean)
        "pomfret" to SpeciesInfo(0.0324, 3.00, 0.15),

        // FishBase / Oman Sea
        "sardine" to SpeciesInfo(0.0093, 2.95, 0.50),

        // Penaeus monodon (Pooled)
        "shrimp" to SpeciesInfo(0.0039, 3.21, 0.80),

        // Scylla serrata (Carapace Width)
        "mud crab" to SpeciesInfo(0.4300, 2.57, 0.30),

        // Portunus sanguinolentus (Outer Carapace Width)
        "3 spotted crab" to SpeciesInfo(0.1340, 2.63, 0.30),

        // Fallback (General Fusiform)
        "default" to SpeciesInfo(0.0120, 3.00, 0.50)
    )

    fun getSpeciesInfo(speciesName: String): SpeciesInfo {
        for ((key, value) in speciesDB) {
            if (speciesName.contains(key, ignoreCase = true)) {
                return value
            }
        }
        return speciesDB["default"]!!
    }
}