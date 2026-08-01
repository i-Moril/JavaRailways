package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A car definition loaded from a car YAML file.
 *
 * A car is composed of body parts (the car body, doors, lights, etc.)
 * and bogies (each bogie has its own parts and wheel parts).
 */
@Serializable
data class CarDefinition(
    val name: String,                             // Fully qualified name e.g. "s100/passenger"
    val length: Double = 2.0,                     // Car length in blocks
    val floorHeight: Double = 0.5,                // Height of car floor above rail curve (meters)
    val bodyParts: List<PartRefEntry> = emptyList(),
    val bogies: List<BogieDefinition> = emptyList(),
    val connectorLengthA: Double = 0.3,           // Connector length at head end (A-end)
    val connectorLengthB: Double = 0.3,           // Connector length at tail end (B-end)
    val bogieAOffset: Double? = null,             // Bogie A offset from center (positive toward head)
    val bogieBOffset: Double? = null,             // Bogie B offset from center (negative toward tail)
    /** Physics/control data is not part of render snapshots. */
    @Transient
    val control: CarControlConfig = CarControlConfig()
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

    /** The package this car belongs to. Set by PackageLoader after loading. */
    @Transient
    var packageName: String? = null
}
