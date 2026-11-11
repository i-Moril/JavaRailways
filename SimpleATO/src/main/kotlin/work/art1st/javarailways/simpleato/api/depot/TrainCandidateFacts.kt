package work.art1st.javarailways.simpleato.api.depot

/** Ephemeral owner-captured query value. Core owns the corresponding physical membership. */
class TrainCandidateFacts private constructor(
    val modelPackageId: String?,
    tags: Set<String>,
    val maximumSpeedMetersPerSecond: Double?,
    capabilities: Set<String>,
    carFacts: Map<String, TrainCandidateFacts>
) {
    /** Uniform classification supplied by a host profile or fixture, without captured membership. */
    constructor(modelPackageId: String?, tags: Set<String>, maximumSpeedMetersPerSecond: Double?,
        capabilities: Set<String>) : this(modelPackageId, tags, maximumSpeedMetersPerSecond, capabilities, emptyMap())

    val tags: Set<String> = java.util.Set.copyOf(tags)
    val capabilities: Set<String> = java.util.Set.copyOf(capabilities)
    val carFacts: Map<String, TrainCandidateFacts> = java.util.Map.copyOf(carFacts)

    override fun equals(other: Any?): Boolean = other is TrainCandidateFacts &&
        modelPackageId == other.modelPackageId && tags == other.tags &&
        maximumSpeedMetersPerSecond == other.maximumSpeedMetersPerSecond &&
        capabilities == other.capabilities && carFacts == other.carFacts
    override fun hashCode(): Int = java.util.Objects.hash(modelPackageId, tags,
        maximumSpeedMetersPerSecond, capabilities, carFacts)

    init {
        modelPackageId?.let { requireStableId("modelPackageId", it) }
        requireTags("tags", this.tags)
        requireTags("capabilities", this.capabilities)
        require(maximumSpeedMetersPerSecond == null ||
            maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0.0)
    }

    companion object {
        /** Reduce exact car leaves; unknown cars are ordinary null/empty uniform leaves. */
        fun fromCars(carFacts: Map<String, TrainCandidateFacts>): TrainCandidateFacts {
            require(carFacts.isNotEmpty()) { "car classification requires nonempty membership" }
            val captured = java.util.Map.copyOf(carFacts)
            captured.keys.forEach { requireStableId("carId", it) }
            require(captured.values.all { it.carFacts.isEmpty() }) { "car classification entries must be leaves" }
            val values = captured.values.toList()
            return TrainCandidateFacts(
                values.map { it.modelPackageId }.distinct().singleOrNull(),
                values.map { it.tags }.reduce { common, next -> common.intersect(next) },
                if (values.all { it.maximumSpeedMetersPerSecond != null })
                    values.minOf { checkNotNull(it.maximumSpeedMetersPerSecond) } else null,
                values.map { it.capabilities }.reduce { common, next -> common.intersect(next) },
                captured
            )
        }
    }
}
