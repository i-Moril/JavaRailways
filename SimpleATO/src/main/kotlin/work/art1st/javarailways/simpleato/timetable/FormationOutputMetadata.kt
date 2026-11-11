package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.simpleato.api.depot.DepotStablingPoint
import work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts

/** Short-lived candidate classification paired with exact captured physical membership and lengths. */
internal class FormationOutputMetadata private constructor(
    val candidateFacts: TrainCandidateFacts,
    val carCount: Int,
    val lengthMeters: Double
) {
    val modelPackageId: String? get() = candidateFacts.modelPackageId
    val tags: Set<String> get() = candidateFacts.tags
    val maximumSpeedMetersPerSecond: Double? get() = candidateFacts.maximumSpeedMetersPerSecond
    val capabilities: Set<String> get() = candidateFacts.capabilities

    override fun equals(other: Any?): Boolean = other is FormationOutputMetadata &&
        candidateFacts == other.candidateFacts && carCount == other.carCount && lengthMeters == other.lengthMeters

    override fun hashCode(): Int = java.util.Objects.hash(candidateFacts, carCount, lengthMeters)

    /** Point compatibility uses these captured facts; physical occupancy is a separate proof. */
    fun accepts(point: DepotStablingPoint): Boolean =
        (point.usableLengthMeters == null || lengthMeters <= point.usableLengthMeters) &&
            (point.allowedTags.isEmpty() || tags.any(point.allowedTags::contains))

    companion object {
        fun source(facts: TrainCandidateFacts, carIds: List<String>, carLengthsMeters: Map<String, Double>): FormationOutputMetadata {
            val length = carIds.sumOf { requireNotNull(carLengthsMeters[it]) { "missing physical car length" } }
            requireConfigured(carIds.size, length, carIds, carLengthsMeters)
            val perCar = if (facts.carFacts.isEmpty()) carIds.associateWith { facts } else {
                require(facts.carFacts.keys == carIds.toSet()) { "captured classifications differ from physical membership" }
                facts.carFacts
            }
            return FormationOutputMetadata(TrainCandidateFacts.fromCars(perCar), carIds.size, length)
        }

        /** Preserve exact car leaves while recomputing a combined candidate classification. */
        fun combine(sources: List<FormationOutputMetadata>, sourceCarIds: List<List<String>>,
            outputCarIds: List<String>, carLengthsMeters: Map<String, Double>): FormationOutputMetadata {
            require(sources.size == 2 && sourceCarIds.size == 2) { "coupling requires two exact classifications" }
            require(carLengthsMeters.keys == sourceCarIds.flatten().toSet()) { "coupling requires exact configured car lengths" }
            sources.zip(sourceCarIds).forEach { (value, ids) ->
                requireConfigured(value.carCount, value.lengthMeters, ids, ids.associateWith(carLengthsMeters::getValue))
                require(value.candidateFacts.carFacts.keys == ids.toSet()) { "source classification membership changed" }
            }
            val all = sourceCarIds.flatten()
            require(all.distinct().size == all.size && carLengthsMeters.keys == all.toSet() &&
                outputCarIds.size == all.size && outputCarIds.toSet() == all.toSet()) {
                "coupling output metadata must conserve every source car exactly once" }
            val length = outputCarIds.sumOf(carLengthsMeters::getValue)
            require(length.isFinite() && length > 0.0)
            val leaves = sources.flatMap { it.candidateFacts.carFacts.entries }.associate { it.key to it.value }
            return FormationOutputMetadata(TrainCandidateFacts.fromCars(leaves), outputCarIds.size, length)
        }

        fun split(source: FormationOutputMetadata, sourceCarIds: List<String>, outputCarIds: List<String>,
            carLengthsMeters: Map<String, Double>): FormationOutputMetadata {
            requireConfigured(source.carCount, source.lengthMeters, sourceCarIds, carLengthsMeters)
            require(source.candidateFacts.carFacts.keys == sourceCarIds.toSet()) { "source classification membership changed" }
            val start = outputCarIds.firstOrNull()?.let(sourceCarIds::indexOf) ?: -1
            require(outputCarIds.isNotEmpty() && outputCarIds.size < sourceCarIds.size && start >= 0 &&
                start + outputCarIds.size <= sourceCarIds.size &&
                sourceCarIds.subList(start, start + outputCarIds.size) == outputCarIds) {
                "split output must be a proper contiguous stable-car subset" }
            val length = outputCarIds.sumOf(carLengthsMeters::getValue)
            require(length.isFinite() && length > 0.0)
            val leaves = outputCarIds.associateWith(source.candidateFacts.carFacts::getValue)
            return FormationOutputMetadata(TrainCandidateFacts.fromCars(leaves), outputCarIds.size, length)
        }

        private fun requireConfigured(count: Int, length: Double, ids: List<String>, sizes: Map<String, Double>) {
            require(ids.isNotEmpty() && ids.distinct().size == ids.size && ids.size == count) {
                "formation source metadata car count does not match physical membership" }
            require(sizes.keys == ids.toSet() && sizes.values.all { it.isFinite() && it > 0.0 }) {
                "formation source requires exact finite positive configured car lengths" }
            val actual = ids.sumOf(sizes::getValue)
            require(actual.isFinite() && actual == length) { "formation source metadata length does not match configured car lengths" }
        }
    }
}
