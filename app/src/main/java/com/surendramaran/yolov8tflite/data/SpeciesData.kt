package com.surendramaran.yolov8tflite.data

/**
 * Holds biological constants for weight and volume estimation.
 * Formula: Weight (g) = a * Length(cm)^b
 * Ratio: Used to estimate depth from width (Depth = Width * Ratio) for volume calc.
 */
data class SpeciesInfo(
    val a: Double,
    val b: Double,
    val ratio: Double
)

object SpeciesRepository {
    // Database of species constants verified for Indian/Indo-Pacific species
    val speciesDB = mapOf(
        // 1. Catfish (Clarias batrachus) - Magur
        // Source: Length-Weight Relationship studies in India (Chhattisgarh/Karnataka)
        "catfish" to SpeciesInfo(0.0046, 3.19, 0.80),

        // 2. Catla (Catla catla)
        // Source: Indian Major Carp studies (Jaisamand Lake/Harike Wetland).
        // Higher 'a' value reflects deep body shape.
        "catla" to SpeciesInfo(0.0200, 3.00, 0.45),

        // 3. Hilsa (Tenualosa ilisha)
        // Source: FishBase / Bay of Bengal studies
        "hilsa" to SpeciesInfo(0.0158, 2.92, 0.40),

        // 4. Mackerel (Indian Mackerel - Rastrelliger kanagurta)
        // Source: Studies from Mangalore/Maharashtra coast
        "mackerel" to SpeciesInfo(0.0045, 3.22, 0.55),

        // 5. Mud Crab (Scylla serrata)
        // Note: Length refers to Carapace Width (CW) in cm
        "mud crab" to SpeciesInfo(0.4300, 2.57, 0.30),

        // 6. Pomfret (Silver Pomfret - Pampus argenteus)
        // Source: FishBase / Daman estuary studies. Very compressed body (low ratio).
        "pomfret" to SpeciesInfo(0.0324, 3.00, 0.15),

        // 7. Rohu (Labeo rohita)
        // Source: General Indian Major Carp studies. Standard fusiform shape.
        "rohu" to SpeciesInfo(0.0130, 3.05, 0.55),

        // 8. Salmon (Atlantic - Salmo salar)
        // Standard aquaculture proxy
        "salmon" to SpeciesInfo(0.0100, 3.05, 0.55),

        // 9. Sardine (Indian Oil Sardine - Sardinella longiceps)
        // Source: FishBase / Oman Sea studies
        "sardine" to SpeciesInfo(0.0093, 2.95, 0.50),

        // 10. Shrimp (Tiger Shrimp - Penaeus monodon)
        // Source: Pooled sex studies
        "shrimp" to SpeciesInfo(0.0039, 3.21, 0.80),

        // 11. Three Spotted Crab (Portunus sanguinolentus)
        // Source: Studies on Outer Carapace Width
        "three spotted crab" to SpeciesInfo(0.1340, 2.63, 0.30),
        // Keep legacy key just in case
        "3 spotted crab" to SpeciesInfo(0.1340, 2.63, 0.30),

        // 12. Tuna (Yellowfin - Thunnus albacares)
        // Source: FishBase Indo-Pacific stock
        "tuna" to SpeciesInfo(0.0145, 3.03, 0.60),

        // Fallback for unknown species (General Fusiform shape)
        "default" to SpeciesInfo(0.0120, 3.00, 0.50)
    )

    fun getSpeciesInfo(speciesName: String): SpeciesInfo {
        for ((key, value) in speciesDB) {
            // Case-insensitive check matches "Catfish" to "catfish", "Three Spotted Crab" etc.
            if (speciesName.contains(key, ignoreCase = true)) {
                return value
            }
        }
        return speciesDB["default"]!!
    }
}