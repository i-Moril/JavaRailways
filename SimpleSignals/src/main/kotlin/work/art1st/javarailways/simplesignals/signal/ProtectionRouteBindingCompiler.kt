package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.simplesignals.block.FixedBlockAuthorityMatchResult
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteMatcher
import work.art1st.javarailways.simplesignals.region.ProtectionRegionRouteMatchResult
import work.art1st.javarailways.simplesignals.region.ProtectionRegionRouteWindow
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.region.protectionRegionAuthorityFloor
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjectionResult
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjector
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import kotlin.math.abs

internal data class ProtectionRouteBindingWindow(
    val region: ProtectionRegionRouteWindow,
    val floorMeters: Double,
    val tableRoute: CompiledProtectionRoute?
)

internal data class ProtectionRouteBindingMatch(
    val windows: List<ProtectionRouteBindingWindow>,
    val invalidEntries: Set<String>,
    val diagnostics: List<String>
)

/** Pure selected-route matching, shared by preparation, inspection and live selector binding. */
internal class ProtectionRouteBindingCompiler(private val publication: SimpleSignalsRuntimePublication) {
    private val tableRegions = publication.definitions.routeTables.mapTo(hashSetOf()) { it.regionId }
    private val routesByEntry = publication.routeSelection.routesById.values.groupBy { it.definition.entryBoundaryId }

    fun compile(route: RouteSnapshot): ProtectionRouteBindingMatch {
        publication.unavailableTerritory.requireUsable(route)
        val regions = checkNotNull(publication.regions.compilationSnapshot())
        val blocks = publication.fixedBlocks.snapshot(publication.topology)
        check(regions.topology === publication.topology && blocks.topology === publication.topology &&
            publication.routeSelection.topology === publication.topology) {
            "protection regions and fixed blocks were captured from different topology roots"
        }
        check(regions.definitionRevision == publication.definitions.definitionRevision &&
            blocks.definitionRevision == publication.definitions.definitionRevision) {
            "protection regions and fixed blocks were captured from different definition revisions"
        }
        val windows = when (val match = regions.match(route)) {
            is ProtectionRegionRouteMatchResult.Matched -> match.windows
            is ProtectionRegionRouteMatchResult.Rejected -> error(match.diagnostics.joinToString { it.detail })
        }
        val blockWindows = when (val match = FixedBlockRouteMatcher.matchRoute(blocks, route)) {
            is FixedBlockAuthorityMatchResult.Matched -> match.windows
            is FixedBlockAuthorityMatchResult.Rejected -> error(match.detail)
        }
        val projection = if (windows.any { it.regionId in tableRegions }) {
            when (val projected = DirectedRouteProjector.project(route, regions.derivedTopology)) {
                is DirectedRouteProjectionResult.Projected -> projected.route
                is DirectedRouteProjectionResult.Rejected -> error(projected.failure.detail)
            }
        } else null
        val invalid = linkedSetOf<String>()
        val diagnostics = arrayListOf<String>()
        val matched = windows.map { window ->
            val floor = protectionRegionAuthorityFloor(window, blockWindows)
            var selected: CompiledProtectionRoute? = null
            if (window.regionId in tableRegions) {
                val occurrences = checkNotNull(projection).occurrences
                var low = 0
                var high = occurrences.size
                while (low < high) {
                    val middle = (low + high) ushr 1
                    if (occurrences[middle].routeEndMeters <= window.entry.routeDistanceMeters) low = middle + 1
                    else high = middle
                }
                val path = arrayListOf<ProtectionRouteTraversal>()
                while (low < occurrences.size && occurrences[low].routeStartMeters < window.exit.routeDistanceMeters) {
                    val occurrence = occurrences[low++]
                    val start = maxOf(occurrence.routeStartMeters, window.entry.routeDistanceMeters)
                    val end = minOf(occurrence.routeEndMeters, window.exit.routeDistanceMeters)
                    if (end - start > Curve.TOLERANCE) {
                        val first = occurrence.canonicalAt(start)
                        val last = occurrence.canonicalAt(end)
                        path += ProtectionRouteTraversal(occurrence.railId, occurrence.facing, minOf(first, last), maxOf(first, last))
                    }
                }
                val candidates = routesByEntry[window.entry.boundaryId].orEmpty().filter { candidate ->
                    candidate.regionId == window.regionId && candidate.definition.exitBoundaryId == window.exit.boundaryId &&
                        samePath(candidate.definition.traversals, path)
                }
                selected = candidates.singleOrNull()
                if (selected == null) {
                    invalid += window.entry.boundaryId
                    diagnostics += "protection region ${window.regionId} entry ${window.entry.boundaryId} " +
                        "exit ${window.exit.boundaryId} has ${candidates.size} matching authored table routes"
                }
            }
            ProtectionRouteBindingWindow(window, floor, selected)
        }
        return ProtectionRouteBindingMatch(java.util.List.copyOf(matched), java.util.Set.copyOf(invalid),
            java.util.List.copyOf(diagnostics))
    }

    private fun samePath(first: List<ProtectionRouteTraversal>, second: List<ProtectionRouteTraversal>): Boolean {
        fun merge(path: List<ProtectionRouteTraversal>): List<ProtectionRouteTraversal> {
            val result = arrayListOf<ProtectionRouteTraversal>()
            path.forEach { part ->
                val previous = result.lastOrNull()
                if (previous != null && previous.railId == part.railId && previous.facing == part.facing &&
                    abs(previous.travelEndAbMeters - part.travelStartAbMeters) <= Curve.TOLERANCE
                ) result[result.lastIndex] = previous.copy(lowerAbMeters = minOf(previous.lowerAbMeters, part.lowerAbMeters),
                    upperAbMeters = maxOf(previous.upperAbMeters, part.upperAbMeters))
                else result += part
            }
            return result
        }
        val a = merge(first)
        val b = merge(second)
        return a.size == b.size && a.zip(b).all { (x, y) -> x.railId == y.railId && x.facing == y.facing &&
            abs(x.lowerAbMeters - y.lowerAbMeters) <= Curve.TOLERANCE && abs(x.upperAbMeters - y.upperAbMeters) <= Curve.TOLERANCE }
    }
}
