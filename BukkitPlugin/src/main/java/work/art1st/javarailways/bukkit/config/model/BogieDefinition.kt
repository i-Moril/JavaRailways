package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Bogie definition within a car: position + parts + wheel parts.
 */
@Serializable
data class BogieDefinition(
    val offset: Vec3Config = Vec3Config(),        // Bogie offset relative to car center
    val parts: List<PartRefEntry> = emptyList(),  // Bogie body part references
    val wheelParts: List<PartRefEntry> = emptyList(), // Wheel part references
    val wheelDiameter: Double = 1.0
)
