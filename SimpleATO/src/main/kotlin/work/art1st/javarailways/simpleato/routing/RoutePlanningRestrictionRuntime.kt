package work.art1st.javarailways.simpleato.routing

import work.art1st.javarailways.basic.api.route.planning.OperatingDirectionOverlay

import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simpleato.api.route.RoutePlanningRestrictionBinding

/** World-local immutable source snapshot used by SimpleATO operational route selection. */
class RoutePlanningRestrictionRuntime {
    @Volatile
    private var sources: List<RoutePlanningRestrictionBinding> = emptyList()

    /**
     * Atomically replaces the complete installed source set.
     *
     * Validation and stable ordering happen before publication, so a rejected replacement leaves
     * the previous set installed and an in-progress route query keeps its captured old snapshot.
     */
    fun replaceSources(replacement: Collection<RoutePlanningRestrictionBinding>) {
        val ordered = java.util.List.copyOf(replacement.sortedBy { it.sourceId })
        require(ordered.map { it.sourceId }.distinct().size == ordered.size) {
            "route-planning restriction source IDs must be unique"
        }
        sources = ordered
    }

    /**
     * Compiles every captured source exactly once and combines it with operating directions.
     *
     * This method runs before graph construction. It never exposes the mutable registry to Basic
     * or lets Dijkstra invoke plugin code.
     */
    internal fun compile(
        topology: TopologyState,
        operatingDirections: OperatingDirectionOverlay
    ): RoutePlanningRestrictions {
        val directionRestrictions = operatingDirections.toRestrictions(topology)
            ?: throw IllegalArgumentException(
                "operating directions reference rails absent from topology revision ${topology.revision}"
            )
        val captured = sources
        val contributions = ArrayList<RoutePlanningRestrictions>(captured.size + 1)
        contributions += directionRestrictions
        captured.forEach { binding ->
            val contribution = try {
                binding.source.restrictions(topology).also {
                    val invalid = work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanner
                        .invalidRestrictionsReason(topology, it)
                    require(invalid == null) { invalid.orEmpty() }
                }
            } catch (failure: Exception) {
                throw IllegalArgumentException(
                    "route-planning restriction source ${binding.sourceId} failed for topology " +
                        "revision ${topology.revision}: ${failure.message ?: failure.javaClass.name}",
                    failure
                )
            }
            contributions += contribution
        }
        return RoutePlanningRestrictions.combine(topology, contributions)
    }
}
