package work.art1st.javarailways.basic.infrastructure.admission

import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.RailIntervalResource
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource

/**
 * Exact uncovered identities of a compiled physical floor. Original closed interval claims remain
 * held: their boundary is not reacquired under a different owner. Adjacent representable AB values
 * are used for delta bounds, so no requested representable point falls between the two owners.
 */
internal fun uncoveredResources(required: Collection<InfrastructureResource>,
                                retained: Collection<InfrastructureResource>): List<InfrastructureResource> {
    val stable = retained.filterIsInstance<StableInfrastructureResource>().toSet()
    val rails = retained.filterIsInstance<RailIntervalResource>().groupBy { it.railId }
    return required.flatMap { resource ->
        when (resource) {
            is StableInfrastructureResource -> if (resource in stable) emptyList() else listOf(resource)
            is RailIntervalResource -> {
                var pieces = listOf(resource)
                for (held in rails[resource.railId].orEmpty().sortedBy { it.startAbMeters }) {
                    pieces = pieces.flatMap { piece ->
                        if (held.endAbMeters < piece.startAbMeters || held.startAbMeters > piece.endAbMeters) listOf(piece)
                        else buildList {
                            if (piece.startAbMeters < held.startAbMeters) add(piece.copy(endAbMeters = Math.nextDown(held.startAbMeters)))
                            if (piece.endAbMeters > held.endAbMeters) add(piece.copy(startAbMeters = Math.nextUp(held.endAbMeters)))
                        }
                    }
                }
                pieces
            }
        }
    }
}
