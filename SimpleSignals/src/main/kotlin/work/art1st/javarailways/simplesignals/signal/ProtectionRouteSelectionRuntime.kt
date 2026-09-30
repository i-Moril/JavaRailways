package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.simpleato.api.signal.SignalEpochInput
import work.art1st.javarailways.simpleato.api.signal.SignalEpochResult
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simplesignals.block.FixedBlockAuthorityMatchResult
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteMatcher
import work.art1st.javarailways.simplesignals.region.ProtectionRegionRouteMatchResult
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.region.protectionRegionAuthorityFloor
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjectionResult
import work.art1st.javarailways.simplesignals.route.DirectedRouteProjector
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsRuntimePublication
import kotlin.math.abs

internal const val PROTECTION_ROUTE_SELECTION_SOURCE_ID = "simplesignals:protection-route-selection"

internal enum class ProtectionRouteSelectionSource { REQUEST, DEFAULT, NONE }

/** Shared by the current signal and all route indicators bound to this directed entrance. */
internal data class SelectedProtectionRoute(
    val entryBoundaryId: String,
    val source: ProtectionRouteSelectionSource,
    val routeId: String? = null,
    val requestState: ActiveRouteAdmissionState? = null,
    val routeReady: Boolean = false,
    /** False means the source could not prove a unique, current state. */
    val available: Boolean = true
)

internal class ProtectionRouteSelectionSnapshot(
    val stamp: RailwayStateStamp,
    selections: Map<String, SelectedProtectionRoute>,
    changedEntries: Collection<String>
) {
    val selections: Map<String, SelectedProtectionRoute> = java.util.Map.copyOf(selections)
    val changedEntries: Set<String> = java.util.Set.copyOf(changedEntries)
}

/** One shared selection source; complete path matching occurs only when the frozen route changes. */
internal class ProtectionRouteSelectionRuntime(private val publication: SimpleSignalsRuntimePublication) {
    private val index = publication.routeSelection
    private val entries = index.routesById.values.mapTo(sortedSetOf()) { it.definition.entryBoundaryId }
    private val routesByEntry = index.routesById.values.groupBy { it.definition.entryBoundaryId }
    private val entriesByRail = index.routesById.values.flatMap { route ->
        route.definition.traversals.map { it.railId to route.definition.entryBoundaryId }
    }.groupBy({ it.first }, { it.second }).mapValues { java.util.Set.copyOf(it.value) }
    private val entriesByContinuation = linkedMapOf<RailEndpointRef, MutableSet<String>>().also { reverse ->
        index.routesById.values.forEach { route ->
            route.requiredContinuations.forEach { requirement ->
                reverse.getOrPut(requirement.entry, ::linkedSetOf) += route.definition.entryBoundaryId
            }
        }
    }

    private data class Match(
        val route: CompiledProtectionRoute,
        val entryMeters: Double,
        val exitMeters: Double,
        val floorMeters: Double
    )
    private data class Binding(
        val route: RouteSnapshot,
        val matches: List<Match>,
        val invalidEntries: Set<String>
    )
    private data class Request(val snapshot: ActiveRouteAdmissionSnapshot, val binding: Binding)
    private val requests = linkedMapOf<InfrastructureOwner, Request>()
    private var liveTrainIds: Set<String> = emptySet()
    // Direct entry memberships keep an unrelated admission change from scanning every live request.
    private val ownersByEntry = entries.associateWith { linkedSetOf<InfrastructureOwner>() }

    @Volatile private var latest: ProtectionRouteSelectionSnapshot? = null

    /** Callback-local rule seeds; ownership can alter fixed readiness without changing selection. */
    var evaluatedEntryIds: Set<String> = emptySet()
        private set

    val source: SignalEpochSource? = if (entries.isEmpty()) null else SignalEpochSource(
        PROTECTION_ROUTE_SELECTION_SOURCE_ID, emptyList(), entriesByContinuation.keys, entriesByRail.keys
    ) { update(it) }

    fun snapshot(): ProtectionRouteSelectionSnapshot? = latest

    fun update(input: SignalEpochInput): SignalEpochResult {
        val previous = latest
        val dirty = linkedSetOf<String>()
        if (previous == null || input.topologyBaseline) dirty += entries
        input.continuationUpdates.forEach { dirty += entriesByContinuation[it.entry].orEmpty() }
        input.admissionUpdates.forEach { update ->
            val old = requests.remove(update.owner)
            old?.let { removeMembership(update.owner, it, dirty) }
            update.current?.let { snapshot ->
                val binding = if (old?.binding?.route === snapshot.route) old.binding else compileBinding(snapshot.route)
                if (binding.matches.isEmpty() && binding.invalidEntries.isEmpty()) return@let
                val request = Request(snapshot, binding)
                requests[update.owner] = request
                activeEntries(request).forEach { entry ->
                    ownersByEntry.getValue(entry) += update.owner
                    dirty += entry
                }
            }
        }
        var selections: MutableMap<String, SelectedProtectionRoute>? = if (previous == null) linkedMapOf() else null
        val changed = linkedSetOf<String>()
        val liveTrains = input.observation.trains.mapTo(hashSetOf()) { it.trainId }
        liveTrainIds = liveTrains
        dirty.forEach { entry ->
            val selected = select(entry, input, liveTrains)
            if (selected != (selections?.get(entry) ?: previous?.selections?.get(entry))) {
                val updated = selections ?: previous!!.selections.toMutableMap().also { selections = it }
                updated[entry] = selected
                changed += entry
            }
        }
        evaluatedEntryIds = java.util.Set.copyOf(dirty)
        // No new presentation revision when only an unrelated suffix changes.
        if (changed.isNotEmpty() || previous == null) {
            latest = ProtectionRouteSelectionSnapshot(input.observation.stamp, checkNotNull(selections), changed)
        }
        return SignalEpochResult.empty()
    }

    val watchedContinuationEntries get() = entriesByContinuation.keys
    val watchedAdmissionRailIds get() = entriesByRail.keys

    /** Fixed-route devices never substitute the current-entry route for their authored route. */
    fun fixedRouteReady(routeId: String, input: SignalEpochInput): Boolean {
        val route = index.routesById.getValue(routeId)
        if (input.observation.stamp.worldId != publication.definitions.worldId.toString() ||
            input.observation.stamp.topologyRevision != index.topology.revision ||
            !route.isPhysicallyOpen(input.observation.activeContinuations)) return false
        val owners = ownersByEntry.getValue(route.definition.entryBoundaryId)
        if (owners.size > 1) return false
        val request = owners.singleOrNull()?.let(requests::get) ?: return true
        if (route.definition.entryBoundaryId in request.binding.invalidEntries) return false
        val match = request.binding.matches.singleOrNull { it.route.definition.routeId == routeId }
            ?: return true
        return request.snapshot.trainId in liveTrainIds &&
            (request.snapshot.committedAuthority?.distanceFromRouteStartMeters ?: -1.0) >=
            match.floorMeters - Curve.TOLERANCE
    }

    internal data class DeviceRouteWindow(val routeId: String, val entryMeters: Double, val floorMeters: Double)

    /** Low-frequency frozen-route binding uses the same exact matcher as the entry selector. */
    fun deviceRouteWindows(route: RouteSnapshot): List<DeviceRouteWindow> {
        val binding = compileBinding(route)
        require(binding.invalidEntries.isEmpty()) { "protection device route does not match an authored route" }
        return binding.matches.map { DeviceRouteWindow(it.route.definition.routeId, it.entryMeters, it.floorMeters) }
    }

    private fun removeMembership(owner: InfrastructureOwner, request: Request, dirty: MutableSet<String>) {
        activeEntries(request).forEach { entry -> ownersByEntry.getValue(entry).remove(owner); dirty += entry }
    }

    private fun activeEntries(request: Request): Set<String> = request.binding.invalidEntries +
        request.binding.matches.filter { match ->
            request.snapshot.requestedAuthority.distanceFromRouteStartMeters > match.entryMeters + Curve.TOLERANCE &&
                request.snapshot.releasedThroughRouteMeters < match.exitMeters - Curve.TOLERANCE
        }.map { it.route.definition.entryBoundaryId }

    private fun select(entry: String, input: SignalEpochInput, liveTrains: Set<String>): SelectedProtectionRoute {
        if (input.observation.stamp.worldId != publication.definitions.worldId.toString() ||
            input.observation.stamp.topologyRevision != index.topology.revision ||
            input.observation.activeContinuations.topologyRevision != index.topology.revision
        ) return SelectedProtectionRoute(entry, ProtectionRouteSelectionSource.NONE, available = false)
        val owners = ownersByEntry.getValue(entry)
        if (owners.size > 1) return SelectedProtectionRoute(entry, ProtectionRouteSelectionSource.REQUEST, available = false)
        owners.singleOrNull()?.let { owner ->
            val request = requests.getValue(owner)
            val match = request.binding.matches.singleOrNull { it.route.definition.entryBoundaryId == entry }
            if (entry in request.binding.invalidEntries || match == null || request.snapshot.trainId !in liveTrains) {
                return SelectedProtectionRoute(entry, ProtectionRouteSelectionSource.REQUEST, available = false)
            }
            val granted = (request.snapshot.committedAuthority?.distanceFromRouteStartMeters ?: -1.0) >=
                match.floorMeters - Curve.TOLERANCE
            return SelectedProtectionRoute(
                entry, ProtectionRouteSelectionSource.REQUEST, match.route.definition.routeId,
                if (granted) ActiveRouteAdmissionState.GRANTED else ActiveRouteAdmissionState.PREPARING,
                granted && match.route.isPhysicallyOpen(input.observation.activeContinuations)
            )
        }
        val default = index.defaultRoute(entry, input.observation.activeContinuations)
        return if (default == null) SelectedProtectionRoute(entry, ProtectionRouteSelectionSource.NONE)
        else SelectedProtectionRoute(entry, ProtectionRouteSelectionSource.DEFAULT, default.definition.routeId, routeReady = true)
    }

    private fun compileBinding(route: RouteSnapshot): Binding {
        val relatedEntries = route.segments.flatMapTo(linkedSetOf()) { entriesByRail[it.railId].orEmpty() }
        fun invalid() = Binding(route, emptyList(), java.util.Set.copyOf(relatedEntries))
        val regions = checkNotNull(publication.regions.compilationSnapshot())
        val windows = when (val match = regions.match(route)) {
            is ProtectionRegionRouteMatchResult.Matched -> match.windows
            is ProtectionRegionRouteMatchResult.Rejected -> return invalid()
        }
        val blocks = when (val match = FixedBlockRouteMatcher.matchRoute(publication.fixedBlocks.snapshot(publication.topology), route)) {
            is FixedBlockAuthorityMatchResult.Matched -> match.windows
            is FixedBlockAuthorityMatchResult.Rejected -> return invalid()
        }
        val projection = when (val projected = DirectedRouteProjector.project(route, regions.derivedTopology)) {
            is DirectedRouteProjectionResult.Projected -> projected.route
            is DirectedRouteProjectionResult.Rejected -> return invalid()
        }
        val matched = arrayListOf<Match>()
        val invalid = linkedSetOf<String>()
        windows.forEach { window ->
            if (window.entry.boundaryId !in entries) return@forEach
            val occurrences = projection.occurrences
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
                    val first = occurrence.canonicalAt(start); val last = occurrence.canonicalAt(end)
                    path += ProtectionRouteTraversal(occurrence.railId, occurrence.facing, minOf(first, last), maxOf(first, last))
                }
            }
            val candidates = routesByEntry[window.entry.boundaryId].orEmpty().filter { candidate ->
                candidate.regionId == window.regionId && candidate.definition.exitBoundaryId == window.exit.boundaryId &&
                    samePath(candidate.definition.traversals, path)
            }
            val selected = candidates.singleOrNull()
            if (selected == null) invalid += window.entry.boundaryId
            else matched += Match(selected, window.entry.routeDistanceMeters, window.exit.routeDistanceMeters,
                protectionRegionAuthorityFloor(window, blocks))
        }
        return Binding(route, java.util.List.copyOf(matched), java.util.Set.copyOf(invalid))
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
        val a = merge(first); val b = merge(second)
        return a.size == b.size && a.zip(b).all { (x, y) -> x.railId == y.railId && x.facing == y.facing &&
            abs(x.lowerAbMeters - y.lowerAbMeters) <= Curve.TOLERANCE && abs(x.upperAbMeters - y.upperAbMeters) <= Curve.TOLERANCE }
    }
}
