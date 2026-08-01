package work.art1st.javarailways.basic.route

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.util.annotation.MainThread

/** World-local owner of one route allocation. */
data class RouteSectionOwner(
    val serviceRunId: String,
    val trainId: String
) {
    init {
        require(serviceRunId.isNotBlank() && trainId.isNotBlank())
    }
}

data class RouteSectionAllocationKey(
    val owner: RouteSectionOwner,
    val routeId: String,
    val routeRevision: Long,
    val sectionIndex: Int
) {
    init {
        require(routeId.isNotBlank())
        require(routeRevision >= 0L)
        require(sectionIndex >= 0)
    }
}

/** Immutable section that has been locked, applied, and confirmed against live turnout state. */
data class ConfirmedRouteWindow(
    val key: RouteSectionAllocationKey,
    val section: RouteSection
)

sealed interface RouteSectionAllocationResult {
    data class Confirmed(val window: ConfirmedRouteWindow) : RouteSectionAllocationResult
    data class Conflict(
        val resource: RailViewRef,
        val heldBy: RouteSectionAllocationKey
    ) : RouteSectionAllocationResult
    data class PhysicallyLocked(
        val resource: RailViewRef,
        val occupyingTrainIds: Set<String>
    ) : RouteSectionAllocationResult
    data class Invalid(val reason: String) : RouteSectionAllocationResult
}

/**
 * Atomically locks and applies every turnout selection in one RouteSection.
 *
 * Calls belong on the main thread after the current physics epoch has completed. Timetable priority
 * is handled by the caller: this allocator grants requests in call order and never preempts a lock.
 */
class RouteSectionAllocator(private val world: World) {
    private val lock = Any()
    private val allocations = LinkedHashMap<RouteSectionAllocationKey, ConfirmedRouteWindow>()
    private val resourceOwners = HashMap<RailViewRef, RouteSectionAllocationKey>()

    @MainThread
    fun allocate(
        owner: RouteSectionOwner,
        route: PlannedRoute,
        sectionIndex: Int
    ): RouteSectionAllocationResult = synchronized(lock) {
        val section = route.sections.getOrNull(sectionIndex)
            ?: return@synchronized RouteSectionAllocationResult.Invalid(
                "RouteSection does not exist: ${route.routeId}#$sectionIndex"
            )
        val key = RouteSectionAllocationKey(owner, route.routeId, route.revision, sectionIndex)
        allocations[key]?.let { return@synchronized RouteSectionAllocationResult.Confirmed(it) }

        val resolved = ArrayList<ResolvedRequirement>(section.turnoutRequirements.size)
        val selectedByResource = HashMap<RailViewRef, RailViewRef>()
        for (requirement in section.turnoutRequirements) {
            val existingSelection = selectedByResource.putIfAbsent(
                requirement.from,
                requirement.selectedNext
            )
            if (existingSelection != null) {
                if (existingSelection != requirement.selectedNext) {
                    return@synchronized RouteSectionAllocationResult.Invalid(
                        "RouteSection selects turnout ${requirement.from} more than once"
                    )
                }
                continue
            }
            val from = resolve(requirement.from)
                ?: return@synchronized RouteSectionAllocationResult.Invalid(
                    "turnout rail view no longer exists: ${requirement.from}"
                )
            val selectedNext = resolve(requirement.selectedNext)
                ?: return@synchronized RouteSectionAllocationResult.Invalid(
                    "selected branch no longer exists: ${requirement.selectedNext}"
                )
            if (from.nextRails.none { it === selectedNext }) {
                return@synchronized RouteSectionAllocationResult.Invalid(
                    "selected branch is no longer connected: ${requirement.from} -> ${requirement.selectedNext}"
                )
            }
            resourceOwners[requirement.from]?.let { currentOwner ->
                return@synchronized RouteSectionAllocationResult.Conflict(requirement.from, currentOwner)
            }
            val previousSelection = from.nextActiveRail
            if (previousSelection !== selectedNext) {
                val lockIntervals = requirement.lockingIntervals(world)
                val occupyingTrainIds = world.allTrains.asSequence()
                    .filter { train ->
                        train.occupiedRailIntervals.any { occupied ->
                            lockIntervals.any { locked -> locked.overlaps(occupied) }
                        }
                    }
                    .map { it.id }
                    .toSet()
                if (occupyingTrainIds.isNotEmpty()) {
                    return@synchronized RouteSectionAllocationResult.PhysicallyLocked(
                        requirement.from,
                        occupyingTrainIds
                    )
                }
            }
            resolved += ResolvedRequirement(requirement.from, from, selectedNext, previousSelection)
        }

        try {
            resolved.forEach { requirement ->
                requirement.from.selectNextRail(requirement.selectedNext)
            }
        } catch (failure: Exception) {
            resolved.forEach { requirement ->
                runCatching { requirement.from.selectNextRail(requirement.previousSelection) }
            }
            return@synchronized RouteSectionAllocationResult.Invalid(
                "failed to apply RouteSection ${route.routeId}#$sectionIndex: " +
                    "${failure::class.simpleName}: ${failure.message.orEmpty()}"
            )
        }

        val window = ConfirmedRouteWindow(key, section)
        allocations[key] = window
        resolved.forEach { requirement -> resourceOwners[requirement.ref] = key }
        RouteSectionAllocationResult.Confirmed(window)
    }

    @MainThread
    fun release(key: RouteSectionAllocationKey): Boolean = synchronized(lock) {
        if (allocations.remove(key) == null) return@synchronized false
        resourceOwners.entries.removeIf { (_, owner) -> owner == key }
        true
    }

    @MainThread
    fun releaseOwner(owner: RouteSectionOwner): Int = synchronized(lock) {
        val keys = allocations.keys.filter { it.owner == owner }
        keys.forEach { key ->
            allocations.remove(key)
            resourceOwners.entries.removeIf { (_, currentOwner) -> currentOwner == key }
        }
        keys.size
    }

    fun confirmedWindows(): List<ConfirmedRouteWindow> = synchronized(lock) {
        allocations.values.toList()
    }

    private fun resolve(ref: RailViewRef): RailView? =
        world.getRail(ref.railId)?.getView(ref.direction)

    private data class ResolvedRequirement(
        val ref: RailViewRef,
        val from: RailView,
        val selectedNext: RailView,
        val previousSelection: RailView?
    )
}
