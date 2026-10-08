package work.art1st.javarailways.simplesignals.authoring

import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCompilationSnapshot

/** Resolves one directed authored protection entrance without graph search or a world-wide region scan. */
internal object ProtectionSignalEntranceResolver {
    data class Resolved(val regionId: String, val boundary: ProtectionBoundaryDefinition)

    fun resolve(snapshot: ProtectionRegionCompilationSnapshot, point: RailPositionSnapshot): Resolved {
        val candidates = snapshot.regionIdsByRail[point.railId].orEmpty()
            .mapNotNull(snapshot.regionsById::get)
            .flatMap { region ->
                region.definition.effectiveBoundaries.mapNotNull { boundary ->
                    Resolved(region.definition.regionId, boundary).takeIf {
                        boundary.insideFacing == point.facing &&
                            samePoint(boundary.position, point, SectionAuthoringActions.SNAP_METERS)
                    }
                }
            }
            .distinctBy { it.boundary.boundaryId }
        require(candidates.isNotEmpty()) {
            "Click a protection-region entrance while looking into the region"
        }
        require(candidates.size == 1) {
            "The selected boundary is shared by multiple protection-region entrances"
        }
        return candidates.single()
    }
}
