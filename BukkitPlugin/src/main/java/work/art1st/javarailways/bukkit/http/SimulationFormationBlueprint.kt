package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.api.depot.FixedEmuUnit
import work.art1st.javarailways.simpleato.api.depot.RollingStockRecord
import work.art1st.javarailways.simpleato.simpleAtoWorldData

/** Detached permanent membership/classification only; no live assignment, occupancy or ticket. */
internal class SimulationFormationBlueprint private constructor(
    val stock: RollingStockRecord,
    sourceCarIds: List<String>,
    units: List<FixedEmuUnit>
) {
    val sourceCarIds: List<String> = java.util.List.copyOf(sourceCarIds)
    val units: List<FixedEmuUnit> = java.util.List.copyOf(units)

    fun unitsFor(trainId: String, targetCarIds: List<String>): List<FixedEmuUnit> {
        require(targetCarIds.size == sourceCarIds.size && targetCarIds.distinct().size == targetCarIds.size)
        val mapping = sourceCarIds.zip(targetCarIds).toMap()
        return units.mapIndexed { index, unit -> FixedEmuUnit("$trainId/unit-$index", unit.homeDepotId,
            unit.carIds.map { mapping.getValue(it) }, unit.controlCompatibilityKey) }
    }

    companion object {
        fun capture(world: CoreWorld, selection: TimetableProfileSelection,
            profile: SimulationControlProfile): Pair<SimulationFormationBlueprint, SimulationControlProfile> {
            require(selection.profileType == SimulationProfileType.WORLD_TRAIN) {
                "structural simulation source ${selection.serviceId} requires a world train with registered complete units"
            }
            val train = requireNotNull(world.getTrain(selection.profileId)) { "structural simulation source train is missing" }
            val depots = world.simpleAtoWorldData.depots
            val stock = requireNotNull(depots.rollingStock(train.id)) { "structural simulation source requires registered rolling stock" }.record
            val originalIds = train.cars.map { it.id }
            val units = depots.fixedUnitsForFormation(originalIds)
            require(units.isNotEmpty() && profile.cars.size == originalIds.size && stock.carCount == originalIds.size) {
                "structural simulation source must contain complete registered units and exact profile membership"
            }
            require(train.cars.zip(profile.cars).all { (car, blueprint) -> car.length == blueprint.length }) {
                "structural simulation profile differs from captured physical composition"
            }
            require(kotlin.math.abs(stock.lengthMeters - profile.cars.sumOf { it.length }) <= 1e-7) {
                "structural simulation stock length differs from physical composition"
            }
            // Simulation profiles normalize car installation to operating direction. Preserve the
            // source's operating unit order when the original Core stable container was reversed.
            val ids = if (train.isReversed) originalIds.reversed() else originalIds
            val normalized = if (train.isReversed) profile.copy(carControls = profile.carControls.reversed(), cars = profile.cars.reversed()) else profile
            return SimulationFormationBlueprint(stock.copy(tags = java.util.Set.copyOf(stock.tags),
                capabilities = java.util.Set.copyOf(stock.capabilities)), ids,
                units) to normalized
        }
    }
}
