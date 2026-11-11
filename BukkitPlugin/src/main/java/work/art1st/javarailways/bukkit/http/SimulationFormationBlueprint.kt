package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.api.physics.train.TrainComposition
import work.art1st.javarailways.core.api.physics.train.TrainUnitComposition
import work.art1st.javarailways.simpleato.api.depot.TrainCandidateFacts
import work.art1st.javarailways.simpleato.simpleAtoWorldData

/** Detached permanent membership/classification only; no live assignment, occupancy or ticket. */
internal class SimulationFormationBlueprint private constructor(
    val candidateFacts: TrainCandidateFacts,
    sourceCarIds: List<String>,
    val composition: TrainComposition,
    controlCompatibility: Map<String, String>
) {
    val sourceCarIds: List<String> = java.util.List.copyOf(sourceCarIds)
    private val controlCompatibility = java.util.Map.copyOf(controlCompatibility)
    /** Map canonical membership once; normalization may reverse physical order, never canonical order. */
    fun compositionFor(trainId: String, targetCarIds: List<String>): TrainComposition {
        require(targetCarIds.size == sourceCarIds.size && targetCarIds.distinct().size == targetCarIds.size)
        val mapping = sourceCarIds.zip(targetCarIds).toMap()
        val units = composition.units.mapIndexed { index, unit ->
            TrainUnitComposition("$trainId/unit-$index", unit.carIds.map(mapping::getValue))
        }
        return TrainComposition.create(units, targetCarIds)
    }

    fun candidateFactsFor(targetCarIds: List<String>): TrainCandidateFacts {
        require(targetCarIds.size == sourceCarIds.size && targetCarIds.distinct().size == targetCarIds.size)
        require(candidateFacts.carFacts.isEmpty() || candidateFacts.carFacts.keys == sourceCarIds.toSet())
        val uniform = TrainCandidateFacts(candidateFacts.modelPackageId, candidateFacts.tags,
            candidateFacts.maximumSpeedMetersPerSecond, candidateFacts.capabilities)
        return TrainCandidateFacts.fromCars(sourceCarIds.zip(targetCarIds).associate { (source, target) ->
            target to (candidateFacts.carFacts[source] ?: uniform)
        })
    }

    fun controlCompatibilityFor(trainId: String): Map<String, String> =
        composition.units.mapIndexedNotNull { index, unit ->
            controlCompatibility[unit.unitId]?.let { "$trainId/unit-$index" to it }
        }.toMap()

    companion object {
        fun capture(world: CoreWorld, selection: TimetableProfileSelection,
            profile: SimulationControlProfile): Pair<SimulationFormationBlueprint, SimulationControlProfile> {
            require(selection.profileType == SimulationProfileType.WORLD_TRAIN) {
                "structural simulation source ${selection.serviceId} requires a world train with complete Core units"
            }
            val train = requireNotNull(world.getTrain(selection.profileId)) { "structural simulation source train is missing" }
            val depots = world.simpleAtoWorldData.depots
            val facts = world.simpleAtoWorldData.candidateFacts(train) ?: TrainCandidateFacts(
                null, emptySet(), profile.trainControl.maxSpeed, emptySet())
            val originalIds = train.cars.map { it.id }
            val composition = train.composition
            val units = composition.unitsFor(originalIds)
            require(units.isNotEmpty() && profile.cars.size == originalIds.size) {
                "structural simulation source must contain complete Core units and exact profile membership"
            }
            require(train.cars.zip(profile.cars).all { (car, blueprint) -> car.length == blueprint.length }) {
                "structural simulation profile differs from captured physical composition"
            }
            // Simulation profiles normalize car installation to operating direction. Preserve the
            // source's operating unit order when the original Core stable container was reversed.
            val ids = if (train.isReversed) originalIds.reversed() else originalIds
            val normalized = if (train.isReversed) profile.copy(carControls = profile.carControls.reversed(), cars = profile.cars.reversed()) else profile
            return SimulationFormationBlueprint(facts, ids,
                composition, depots.fixedUnitsForFormation(originalIds).mapNotNull { unit ->
                    unit.controlCompatibilityKey?.let { unit.id to it }
                }.toMap()) to normalized
        }
    }
}
