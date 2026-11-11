package work.art1st.javarailways.simpleato.api.depot

import java.util.Collections

/** Permanent operating membership, not a second owner of Core train composition. */
@ConsistentCopyVisibility
data class FixedEmuUnit private constructor(
    val id: String,
    val homeDepotId: DepotId,
    val carIds: List<String>,
    /** Declared control family only; structural operations still require actual actuator preflight. */
    val controlCompatibilityKey: String
) {
    constructor(id: String, homeDepotId: DepotId, carIds: Collection<String>, controlCompatibilityKey: String) :
        this(id, homeDepotId, Collections.unmodifiableList(ArrayList(carIds)), controlCompatibilityKey)

    init {
        requireStableId("unitId", id)
        requireStableId("controlCompatibilityKey", controlCompatibilityKey)
        require(carIds.isNotEmpty() && carIds.distinct().size == carIds.size) { "unit cars must be non-empty and unique" }
        carIds.forEach { requireStableId("carId", it) }
    }
}

data class FixedEmuUnitAssignment(val unitId: String, val trainId: String, val assignmentId: String) {
    init {
        requireStableId("unitId", unitId)
        requireStableId("trainId", trainId)
        requireStableId("assignmentId", assignmentId)
    }
}
