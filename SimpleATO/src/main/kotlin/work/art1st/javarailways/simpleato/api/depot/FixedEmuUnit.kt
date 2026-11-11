package work.art1st.javarailways.simpleato.api.depot

import java.util.Collections

/** Read-only Core composition projection, optionally annotated with an operational control declaration. */
@ConsistentCopyVisibility
data class FixedEmuUnit private constructor(
    val id: String,
    val carIds: List<String>,
    /** Declared control family only; structural operations still require actual actuator preflight. */
    val controlCompatibilityKey: String? = null
) {
    constructor(id: String, carIds: Collection<String>, controlCompatibilityKey: String? = null) :
        this(id, Collections.unmodifiableList(ArrayList(carIds)), controlCompatibilityKey)

    init {
        requireStableId("unitId", id)
        controlCompatibilityKey?.let { requireStableId("controlCompatibilityKey", it) }
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
