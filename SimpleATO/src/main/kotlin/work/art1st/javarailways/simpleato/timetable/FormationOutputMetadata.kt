package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.simpleato.api.depot.DepotId
import work.art1st.javarailways.simpleato.api.depot.DepotStablingPoint
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord

/** Declared stock classification, separate from permanent unit homes and future physical IDs. */
internal class FormationOutputMetadata private constructor(
    val homeDepotId: DepotId,
    val modelPackageId: String?,
    tags: Collection<String>,
    val carCount: Int,
    val lengthMeters: Double,
    val maximumSpeedMetersPerSecond: Double,
    capabilities: Collection<String>
) {
    val tags: Set<String> = java.util.Set.copyOf(tags)
    val capabilities: Set<String> = java.util.Set.copyOf(capabilities)

    fun toRecord(actualOutputTrainId: String): RollingStockRecord = RollingStockRecord(
        actualOutputTrainId, homeDepotId, modelPackageId, tags, carCount, lengthMeters,
        maximumSpeedMetersPerSecond, capabilities)

    /** Same classification test as registered Depot storage; current occupancy is runtime-only. */
    fun accepts(point: DepotStablingPoint): Boolean =
        (point.usableLengthMeters == null || lengthMeters <= point.usableLengthMeters) &&
            (point.allowedTags.isEmpty() || tags.any(point.allowedTags::contains))

    companion object {
        fun source(record: RollingStockRecord, carIds: List<String>, carLengthsMeters: Map<String, Double>): FormationOutputMetadata {
            requireConfigured(record.carCount, record.lengthMeters, carIds, carLengthsMeters)
            return FormationOutputMetadata(record.homeDepotId, record.modelPackageId, record.tags, record.carCount,
                record.lengthMeters, record.maximumSpeedMetersPerSecond, record.capabilities)
        }

        /** Aggregate classification is conservative; mixed models have no single model identity. */
        fun couple(sources: List<RollingStockRecord>, sourceCarIds: Map<String, List<String>>,
            outputCarIds: List<String>, carLengthsMeters: Map<String, Double>): FormationOutputMetadata {
            require(sources.size == 2 && sources.map { it.trainId }.distinct().size == 2 &&
                sourceCarIds.keys == sources.map { it.trainId }.toSet()) { "coupling requires two exact metadata owners" }
            val memberSets = sources.map { sourceCarIds.getValue(it.trainId) }
            require(carLengthsMeters.keys == memberSets.flatten().toSet()) { "coupling requires exact configured car lengths" }
            val values = sources.zip(memberSets).map { (record, ids) ->
                source(record, ids, ids.associateWith(carLengthsMeters::getValue)) }
            return combine(values, memberSets, outputCarIds, carLengthsMeters)
        }

        /** Id-free classification algebra for already bound split/coupled candidates. */
        fun combine(sources: List<FormationOutputMetadata>, sourceCarIds: List<List<String>>,
            outputCarIds: List<String>, carLengthsMeters: Map<String, Double>): FormationOutputMetadata {
            require(sources.size == 2 && sourceCarIds.size == 2) { "coupling requires two exact classifications" }
            require(carLengthsMeters.keys == sourceCarIds.flatten().toSet()) { "coupling requires exact configured car lengths" }
            sources.zip(sourceCarIds).forEach { (value, ids) ->
                requireConfigured(value.carCount, value.lengthMeters, ids, ids.associateWith(carLengthsMeters::getValue)) }
            val all = sourceCarIds.flatten()
            require(all.distinct().size == all.size && carLengthsMeters.keys == all.toSet() &&
                outputCarIds.size == all.size && outputCarIds.toSet() == all.toSet()) {
                "coupling output metadata must conserve every source car exactly once" }
            val length = outputCarIds.sumOf(carLengthsMeters::getValue)
            require(length.isFinite() && length > 0.0)
            return FormationOutputMetadata(sources.first().homeDepotId,
                sources.map { it.modelPackageId }.distinct().singleOrNull(),
                sources.first().tags.intersect(sources.last().tags), outputCarIds.size, length,
                sources.minOf { it.maximumSpeedMetersPerSecond },
                sources.first().capabilities.intersect(sources.last().capabilities))
        }

        fun split(source: RollingStockRecord, sourceCarIds: List<String>, outputCarIds: List<String>,
            carLengthsMeters: Map<String, Double>): FormationOutputMetadata =
            split(FormationOutputMetadata.source(source, sourceCarIds, carLengthsMeters), sourceCarIds, outputCarIds, carLengthsMeters)

        fun split(source: FormationOutputMetadata, sourceCarIds: List<String>, outputCarIds: List<String>,
            carLengthsMeters: Map<String, Double>): FormationOutputMetadata {
            requireConfigured(source.carCount, source.lengthMeters, sourceCarIds, carLengthsMeters)
            val start = outputCarIds.firstOrNull()?.let(sourceCarIds::indexOf) ?: -1
            require(outputCarIds.isNotEmpty() && outputCarIds.size < sourceCarIds.size && start >= 0 &&
                start + outputCarIds.size <= sourceCarIds.size &&
                sourceCarIds.subList(start, start + outputCarIds.size) == outputCarIds) {
                "split output must be a proper contiguous stable-car subset" }
            val length = outputCarIds.sumOf(carLengthsMeters::getValue)
            require(length.isFinite() && length > 0.0)
            return FormationOutputMetadata(source.homeDepotId, source.modelPackageId, source.tags,
                outputCarIds.size, length, source.maximumSpeedMetersPerSecond, source.capabilities)
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
