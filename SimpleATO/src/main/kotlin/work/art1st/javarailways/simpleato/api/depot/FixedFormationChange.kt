package work.art1st.javarailways.simpleato.api.depot

import java.util.Collections

/** Exact operating owner and ordered whole-unit membership at a structural boundary. */
@ConsistentCopyVisibility
data class FixedFormationOwner private constructor(
    val trainId: String,
    val assignmentId: String,
    val carIds: List<String>,
    val reversed: Boolean
) {
    constructor(trainId: String, assignmentId: String, carIds: Collection<String>, reversed: Boolean = false) :
        this(trainId, assignmentId, Collections.unmodifiableList(ArrayList(carIds)), reversed)

    init {
        requireStableId("trainId", trainId)
        requireStableId("assignmentId", assignmentId)
        require(carIds.isNotEmpty() && carIds.distinct().size == carIds.size)
        carIds.forEach { requireStableId("carId", it) }
    }
}

enum class FixedFormationChangeKind { COUPLE, SPLIT }

/** The ledger side is explicit; observing physical outputs alone never commits ownership. */
enum class FixedFormationPhase { SOURCES_HELD, OUTPUT_OWNERS_COMMITTED }

/** A finished stopped output has independent persisted ownership, unrelated to continuing peers. */
data class FixedFormationHeldOutput(val owner: FixedFormationOwner,
    val disposition: FixedFormationDisposition = FixedFormationDisposition.HOLD)

/** All outputs retain an explicit assignment, including a stopped output without a next duty. */
enum class FixedFormationDisposition { CONTINUE_DUTY, HOLD }

data class FixedFormationOutput(val owner: FixedFormationOwner, val disposition: FixedFormationDisposition)

/** Persistent unresolved intent, never evidence that Core published the change. */
@ConsistentCopyVisibility
data class FixedFormationChange private constructor(
    val eventId: String,
    val kind: FixedFormationChangeKind,
    val sources: List<FixedFormationOwner>,
    val outputs: List<FixedFormationOutput>,
    val phase: FixedFormationPhase
) {
    constructor(eventId: String, kind: FixedFormationChangeKind, sources: Collection<FixedFormationOwner>, outputs: Collection<FixedFormationOutput>,
                phase: FixedFormationPhase = FixedFormationPhase.SOURCES_HELD) :
        this(eventId, kind, Collections.unmodifiableList(ArrayList(sources)), Collections.unmodifiableList(ArrayList(outputs)), phase)

    init {
        requireStableId("eventId", eventId)
        require(when (kind) {
            FixedFormationChangeKind.COUPLE -> sources.size == 2 && outputs.size == 1
            FixedFormationChangeKind.SPLIT -> sources.size == 1 && outputs.size == 2
        }) { "invalid source/output count for $kind" }
        listOf(sources, outputs.map { it.owner }).forEach { owners ->
            require(owners.map { it.trainId }.distinct().size == owners.size)
            require(owners.map { it.assignmentId }.distinct().size == owners.size)
            val cars = owners.flatMap { it.carIds }
            require(cars.distinct().size == cars.size) { "car appears in more than one formation" }
        }
        require(sources.flatMap { it.carIds }.toSet() == outputs.flatMap { it.owner.carIds }.toSet()) {
            "formation change must conserve every car"
        }
    }
}

enum class RecoveredFormationComposition { UNCHANGED_SOURCES, EXPECTED_OUTPUTS }

/** Startup observation; neither outcome authorizes replay or release of the recovery hold. */
data class FixedFormationRecoveryHold(val change: FixedFormationChange, val composition: RecoveredFormationComposition)
