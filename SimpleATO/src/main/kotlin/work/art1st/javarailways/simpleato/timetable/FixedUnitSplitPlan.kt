package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.FixedFormationChangeIntent
import work.art1st.javarailways.basic.api.route.FormationTrainIdentity
import work.art1st.javarailways.basic.api.route.RouteGrantSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.depot.*

/** One resolved HEAD partition. Logical FIRST is independent of Core's retained stable prefix. */
internal class FixedUnitSplitPlan private constructor(
    val intent: FixedFormationChangeIntent.Split,
    val ledgerChange: FixedFormationChange,
    outputs: Map<String, FixedFormationOwner>
) {
    val outputs: Map<String, FixedFormationOwner> = java.util.Map.copyOf(outputs)

    companion object {
        fun prepare(event: TimetableFormationEvent.Split, instanceId: String,
            source: TrainFrameSnapshot, sourceAssignmentId: String, grant: RouteGrantSnapshot,
            depots: DepotStore, separatedTrainId: String, outputAssignments: Map<String, String>,
            outputDirections: Map<String, Boolean> = event.outputs.associate { it.id to source.reversed }
        ): FixedUnitSplitPlan {
            require(!source.derailed && source.speedMetersPerSecond == 0.0) { "split source must be stopped and aligned" }
            require(grant.trainId == source.trainId) { "split grant belongs to another train" }
            require(separatedTrainId.isNotBlank() && separatedTrainId != source.trainId)
            require(outputAssignments.keys == event.outputs.map { it.id }.toSet() &&
                outputAssignments.values.distinct().size == 2) { "every split output needs one distinct assignment" }
            require(outputDirections.keys == event.outputs.map { it.id }.toSet()) {
                "every split output needs one explicit operating direction"
            }
            val cars = source.cars.map { it.carId }
            val stableUnits = depots.fixedUnitsForFormation(cars)
            require(event.firstUnitCount < stableUnits.size) { "HEAD partition must leave at least one complete unit" }
            val prefixUnitCount = if (source.reversed) stableUnits.size - event.firstUnitCount else event.firstUnitCount
            val cut = stableUnits.take(prefixUnitCount).sumOf { it.carIds.size }
            depots.validateFixedUnitSplit(cars, cars[cut])
            val retainedOutputId = if (source.reversed) event.rest.id else event.first.id
            val separatedOutputId = if (source.reversed) event.first.id else event.rest.id
            val retained = FormationTrainIdentity(source.trainId, cars.take(cut), outputDirections.getValue(retainedOutputId))
            val separated = FormationTrainIdentity(separatedTrainId, cars.drop(cut), outputDirections.getValue(separatedOutputId))
            val first = if (source.reversed) separated else retained
            val rest = if (source.reversed) retained else separated
            val identities = mapOf(event.first.id to first, event.rest.id to rest)
            val owners = identities.mapValues { (id, identity) -> FixedFormationOwner(identity.trainId,
                outputAssignments.getValue(id), identity.carIds, identity.reversed) }
            val ledgerOutputs = listOf(retained, separated).map { identity ->
                val definition = event.outputs.single { identities.getValue(it.id) === identity }
                FixedFormationOutput(owners.getValue(definition.id), when (definition.disposition) {
                    is FormationOutputDisposition.Duty -> FixedFormationDisposition.CONTINUE_DUTY
                    FormationOutputDisposition.Hold -> FixedFormationDisposition.HOLD
                })
            }
            return FixedUnitSplitPlan(FixedFormationChangeIntent.Split(instanceId, grant.grantId,
                FormationTrainIdentity(source.trainId, cars, source.reversed), cars[cut], retained, separated),
                FixedFormationChange(instanceId, FixedFormationChangeKind.SPLIT,
                    listOf(FixedFormationOwner(source.trainId, sourceAssignmentId, cars, source.reversed)), ledgerOutputs),
                owners)
        }
    }
}
