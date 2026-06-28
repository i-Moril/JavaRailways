package work.art1st.mineRails.bukkit.config.model

/**
 * A car definition loaded from a car YAML file.
 *
 * A car is composed of body parts (the car body, doors, lights, etc.)
 * and bogies (each bogie has its own parts and wheel parts).
 */
data class CarDefinition(
    val name: String,                             // Fully qualified name e.g. "s100/passenger"
    val length: Double = 2.0,                     // Car length in blocks
    val bodyParts: List<PartRefEntry> = emptyList(),
    val bogies: List<BogieDefinition> = emptyList(),
    val connectorLengthA: Double = 0.3,           // Connector length at head end (A-end)
    val connectorLengthB: Double = 0.3,           // Connector length at tail end (B-end)
    val bogieAOffset: Double? = null,             // Bogie A offset from center (positive toward head)
    val bogieBOffset: Double? = null              // Bogie B offset from center (negative toward tail)
) {
    val namespace: String
        get() {
            val idx = name.lastIndexOf('/')
            return if (idx >= 0) name.substring(0, idx) else name
        }

    val simpleName: String
        get() {
            val idx = name.lastIndexOf('/')
            return if (idx >= 0) name.substring(idx + 1) else name
        }
}
