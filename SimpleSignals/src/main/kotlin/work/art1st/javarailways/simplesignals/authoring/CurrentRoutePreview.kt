package work.art1st.javarailways.simplesignals.authoring

import org.bukkit.plugin.Plugin
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionInteriorSlice
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.simplesignals.runtime.PreviewRegionState
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class RoutePreviewSelection(val worldId: UUID, val runtime: SimpleSignalsWorldRuntime,
    val regionId: String, val entry: ProtectionBoundaryDefinition, val exit: ProtectionBoundaryDefinition? = null,
    val regionRailIds: Set<UUID> = emptySet(), val warnings: RegionRouteWarnings = RegionRouteWarnings())

/** One derived value per player selection, not a world cache or signal watcher. */
internal class RegionRouteWarnings {
    private data class Prepared(val topology: TopologyState, val region: ProtectionRegionDefinition,
        val directions: Map<UUID, ViewDirection?>, val drawing: RoutePreviewDrawing)
    @Volatile private var prepared: Prepared? = null
    fun drawing(topology: TopologyState, region: ProtectionRegionDefinition,
        interior: List<ProtectionRegionInteriorSlice>, directions: Map<UUID, ViewDirection?>,
        curve: (UUID) -> work.art1st.javarailways.core.api.physics.curve.Curve?): RoutePreviewDrawing {
        prepared?.takeIf { it.topology === topology && it.region == region && it.directions == directions }?.let { return it.drawing }
        val found = ProtectionRouteDiscovery.discover(topology, region, interior, directions)
        val drawing = RoutePreviewDrawing.warnings(region.boundaries.filter { it.boundaryId in found.warningBoundaryIds }, curve)
        prepared = Prepared(topology, region, java.util.Collections.unmodifiableMap(LinkedHashMap(directions)), drawing)
        return drawing
    }
}

internal class RoutePreviewComputation(val path: CurrentRoutePath, val regionState: PreviewRegionState,
    val regionRailIds: Set<UUID>, val drawing: RoutePreviewDrawing,
    val active: ActiveRailContinuationsSnapshot? = null)

/** Worker-only reads/geometry plus closeable read-only subscriptions; never authoring or admission. */
internal interface RoutePreviewBackend {
    fun select(worldId: UUID, point: RailPositionSnapshot, entry: RoutePreviewSelection?): RoutePreviewSelection
    fun compute(selection: RoutePreviewSelection): RoutePreviewComputation
    fun current(selection: RoutePreviewSelection, computation: RoutePreviewComputation): Boolean
    fun subscribe(selection: RoutePreviewSelection, observation: (ActiveRailContinuationsSnapshot) -> Unit,
        topology: (RailTopologyChange) -> Unit, region: (PreviewRegionState) -> Unit): List<AutoCloseable>
}

internal class RuntimeRoutePreviewBackend(private val plugin: Plugin,
    private val runtimeForWorld: (UUID) -> SimpleSignalsWorldRuntime?, private val infrastructure: InfrastructureResourceService,
    private val observations: RailwayObservationService, private val targets: RailTargetService) : RoutePreviewBackend {
    override fun select(worldId: UUID, point: RailPositionSnapshot, entry: RoutePreviewSelection?): RoutePreviewSelection {
        val runtime = runtimeForWorld(worldId) ?: error("SimpleSignals world is unavailable")
        require(entry == null || entry.runtime === runtime) { "Selected entry belongs to a retired world; select again" }
        val snapshot = checkNotNull(runtime.authoringPublication().regions.compilationSnapshot())
        // Authoring requires interior cuts: snap only on the clicked rail, never across a joint.
        // Existing rail reverse index narrows candidates; pending markers are not region gates.
        val candidates = snapshot.regionIdsByRail[point.railId].orEmpty()
            .mapNotNull { snapshot.regionsById[it] }
            .filter { entry == null || it.definition.regionId == entry.regionId }
            .flatMap { region -> region.definition.effectiveBoundaries.filter {
                samePoint(it.position, point, 0.75)
            }.map { region to it } }
        require(candidates.size == 1) { "Select one existing ${if (entry == null) "protection-region" else "same-region exit"} boundary" }
        val (region, boundary) = candidates.single()
        require(entry == null || entry.entry.boundaryId != boundary.boundaryId) { "Choose a different exit boundary" }
        val railIds = java.util.Set.copyOf(region.interior.map { it.railId } +
            region.definition.boundaries.map { it.position.railId })
        return if (entry == null) RoutePreviewSelection(worldId, runtime, region.definition.regionId, boundary,
            regionRailIds = railIds) else entry.copy(exit = boundary, regionRailIds = railIds)
    }

    override fun compute(selection: RoutePreviewSelection): RoutePreviewComputation {
        check(runtimeForWorld(selection.worldId) === selection.runtime && !selection.runtime.closed) {
            "Selected world runtime was unloaded"
        }
        val topology = infrastructure.currentTopology(selection.worldId)
        val state = selection.runtime.previewRegionState(selection.regionId)
        val region = state.definition
        val exit = checkNotNull(selection.exit)
        val entryExists = region?.boundaries?.any { it.boundaryId == selection.entry.boundaryId } == true
        val exitExists = region?.boundaries?.any { it.boundaryId == exit.boundaryId } == true
        val active = observations.latest(selection.worldId).orElse(null)?.activeContinuations
        val path = if (!entryExists || !exitExists) CurrentRoutePath(selection.entry, exit, emptyList(),
            selection.entry.position, CurrentRouteEnd.UNAVAILABLE, "Selected region or boundary was removed")
        else CurrentRouteTracer.trace(topology, active,
            checkNotNull(region), selection.entry.boundaryId, exit.boundaryId, !state.unavailable)
        val compiled = selection.runtime.authoringPublication().regions.compilationSnapshot()
            ?.regionsById?.get(selection.regionId)
        val regionRails = compiled?.interior?.map { it.railId }.orEmpty() +
            region?.boundaries?.map { it.position.railId }.orEmpty()
        val pathDrawing = RoutePreviewDrawing.prepare(path) { railId ->
            targets.findCurve(selection.worldId, railId).orElse(null)
        }
        val warnings = if (compiled != null && region != null && !state.unavailable && region.boundaryDraft == null) {
            val directions = infrastructure.operatingDirections(selection.worldId, topology, compiled.interior.map { it.railId }.toSet())
            selection.warnings.drawing(topology, region, compiled.interior, directions.directions) {
                targets.findCurve(selection.worldId, it).orElse(null) }
        } else RoutePreviewDrawing(emptyList(), false)
        val drawing = RoutePreviewDrawing(pathDrawing.points + warnings.points, pathDrawing.connected,
            pathDrawing.unavailableGeometry + warnings.unavailableGeometry)
        return RoutePreviewComputation(path, state, java.util.Set.copyOf(regionRails), drawing, active)
    }

    override fun current(selection: RoutePreviewSelection, computation: RoutePreviewComputation): Boolean {
        if (selection.runtime.closed || runtimeForWorld(selection.worldId) !== selection.runtime) return false
        if (selection.runtime.previewRegionState(selection.regionId) != computation.regionState) return false
        val active = observations.latest(selection.worldId).orElse(null)?.activeContinuations
        return computation.path.watchedContinuations.all { (port, expected) -> active?.selectedContinuation(port) == expected }
    }

    override fun subscribe(selection: RoutePreviewSelection, observation: (ActiveRailContinuationsSnapshot) -> Unit,
        topology: (RailTopologyChange) -> Unit, region: (PreviewRegionState) -> Unit): List<AutoCloseable> {
        val handles = arrayListOf<AutoCloseable>()
        try {
            handles += observations.subscribe(plugin, selection.worldId) { _, value -> observation(value.activeContinuations) }
            handles += infrastructure.observeTopologyChanges(plugin, selection.worldId, topology)
            handles += selection.runtime.observePreviewRegion(selection.regionId, region)
            observations.latest(selection.worldId).ifPresent { observation(it.activeContinuations) }
            return handles
        } catch (failure: Exception) { handles.forEach { it.close() }; throw failure }
    }
}

/** One in-flight worker, with actual input references as stale checks and no platform work in callbacks. */
internal class CurrentRoutePreviewSession(val selection: RoutePreviewSelection,
    private val backend: RoutePreviewBackend, private val worker: (() -> Unit) -> Unit,
    private val deliver: (RoutePreviewComputation) -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val request = AtomicReference<Any>(selection)
    private val completed = AtomicReference<Any?>()
    private val observed = AtomicReference<ActiveRailContinuationsSnapshot?>()
    private val latest = AtomicReference<RoutePreviewComputation?>()
    private data class Visible(val cause: Any, val value: RoutePreviewComputation)
    private val display = AtomicReference<Visible?>()
    private val handles = CopyOnWriteArrayList<AutoCloseable>()

    fun start() {
        if (closed.get()) return
        try {
            backend.subscribe(selection, ::onObservation, ::onTopology, ::onRegion).forEach { handle ->
                handles += handle
                if (closed.get() && handles.remove(handle)) handle.close()
            }
            schedule()
        } catch (failure: Exception) { close(); throw failure }
    }

    fun visible(): RoutePreviewComputation? = display.get()?.takeIf {
        !closed.get() && request.get() === it.cause
    }?.value

    private fun onObservation(active: ActiveRailContinuationsSnapshot) {
        if (closed.get()) return
        val previous = observed.getAndSet(active)
        if (previous === active) return
        val path = latest.get()?.path
        if (path == null || path.waitingForObservation ||
            path.watchedContinuations.any { (port, selected) -> active.selectedContinuation(port) != selected })
            changed(active)
    }

    private fun onTopology(change: RailTopologyChange) {
        if (closed.get()) return
        val value = latest.get()
        val rails = value?.regionRailIds ?: selection.regionRailIds
        if (change.affectedRailIds.any { it in rails || it in value?.path?.railIds.orEmpty() }) changed(change)
    }

    private fun onRegion(state: PreviewRegionState) {
        if (selection.runtime.closed) { close(); return }
        changed(state)
    }

    private fun changed(cause: Any) {
        if (closed.get()) return
        request.set(cause)
        schedule()
    }

    private fun schedule() {
        if (closed.get() || !scheduled.compareAndSet(false, true)) return
        try { worker(::runWorker) } catch (_: Exception) { close() }
    }

    private fun runWorker() {
        var recapturedChoice = false
        try {
            while (!closed.get()) {
                val cause = request.get()
                val value = try { backend.compute(selection) } catch (_: Exception) { close(); return }
                if (closed.get()) return
                if (request.get() !== cause) continue
                // New interests can arise along a newly selected branch while geometry is prepared.
                // Retain attempted interests even if its result is rejected below.
                latest.set(value)
                if (!backend.current(selection, value)) {
                    // Do not spin on a retired/stale capture. Its actual input notification will
                    // wake us; a new cause already received is handled in the next iteration.
                    completed.set(cause)
                    if (request.get() !== cause) continue
                    val currentChoice = observed.get()
                    if (!recapturedChoice && currentChoice !== value.active &&
                        value.path.watchedContinuations.any { (port, selected) -> currentChoice?.selectedContinuation(port) != selected }) {
                        recapturedChoice = true
                        continue
                    }
                    break
                }
                display.set(Visible(cause, value))
                completed.set(cause)
                if (request.get() === cause) deliver(value)
                if (request.get() === cause) break
            }
        } finally {
            scheduled.set(false)
            if (!closed.get() && request.get() !== completed.get()) schedule()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        display.set(null)
        handles.forEach { runCatching { it.close() } }
        handles.clear()
    }
}
