package work.art1st.javarailways.basic.api.ato

import work.art1st.javarailways.basic.api.rail.RailObjectStore
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailObjectSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailView
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailSegment
import work.art1st.javarailways.core.physics.train.TrainEnd

/**
 * One traversal of currently selected track, for first-party physics consumers; independent
 * third-party API compatibility is not promised. Segment values and their ordering are fixed,
 * but rail views remain live world handles. This is not a cross-thread perception snapshot or
 * a movement authority. Read it in the world's simulation/physics context; the caller owns
 * currentness and late selected-continuation checks before movement.
 *
 * @property topologyRevision topology revision captured before resolving and traversing the head.
 * @property segments ordered physical intervals, including repeated rails when the path loops.
 * @property coveredLengthMeters sum of the traversed interval lengths in metres.
 */
class SelectedTrackAhead internal constructor(
    /** Topology revision captured before resolving and traversing the head. */
    val topologyRevision: Long,
    physicalSegments: List<RailSegment>
) {
    internal val physicalSegments: List<RailSegment> = java.util.List.copyOf(physicalSegments)
    /** Read-only ordered intervals, including repeated rails; their views remain live. */
    val segments: List<PhysicalTrackSegment> = java.util.List.copyOf(physicalSegments.map {
        PhysicalTrackSegment(it.railView, it.startArcLength, it.endArcLength)
    })
    /** Sum of the traced interval lengths in metres; does not re-traverse the live route. */
    val coveredLengthMeters: Double get() = segments.sumOf { it.totalArcLength }
}

/**
 * Fixed interval on a live, travel-oriented rail view. First-party physical query value;
 * construction adds no validation and does not capture the view's selected continuation.
 *
 * @property railView original Core API view handle, retaining live identity and geometry access.
 * @property startArcLength interval start in metres along the oriented view, not canonical AB.
 * @property endArcLength interval end in metres along the oriented view.
 * @property totalArcLength interval length in metres, exactly end minus start.
 */
data class PhysicalTrackSegment(
    /** Original Core API view handle with live identity, geometry and selected continuation. */
    val railView: RailView,
    /** Start in metres along the oriented view, not canonical AB. */
    val startArcLength: Double,
    /** End in metres along the oriented view. */
    val endArcLength: Double
) {
    /** Interval length in metres, exactly end minus start. */
    val totalArcLength: Double get() = endArcLength - startArcLength
}

/**
 * First-party physical observation without perception-epoch or control-policy decisions.
 * The snapshot is detached immutable evidence; the original object remains a live handle for
 * late operational reads in the world's simulation/physics context. In particular, a train end's
 * owner may become unavailable after this observation. This value does not freeze that owner.
 *
 * @property railObject original object instance, retaining identity across observations.
 * @property snapshot immutable object position captured in the traversed view's facing.
 * @property distanceMeters non-negative distance from the traced head along the selected path.
 * @property relativeSpeedMetersPerSecond other train speed along the view minus observer speed,
 * or null for objects without a live Core train-end owner. Opposite-facing speed contributes zero.
 */
data class PhysicalTrackObservation(
    /** Original live object instance; preserves identity for later lifecycle and policy reads. */
    val railObject: RailObject,
    /** Immutable position and lifecycle captured in the traversed view facing. */
    val snapshot: RailObjectSnapshot,
    /** Non-negative distance in metres from the traced head, including the lower-bound tolerance clamp. */
    val distanceMeters: Double,
    /** Other train speed along the view minus observer speed in m/s; opposite-facing speed contributes zero, missing train-end owner gives null. */
    val relativeSpeedMetersPerSecond: Double?
)

/**
 * Traces selected physical track once using Core's existing head traversal. First-party query,
 * without independent third-party API compatibility guarantees. Capture the topology revision,
 * resolve the head, then traverse; object getters are deferred to [observeTrackObjects], allowing
 * the caller to construct and validate its own route identity before collecting objects.
 *
 * Call in the world's simulation/physics context. Views remain live, so the captured topology
 * revision does not prove that selected continuations will remain unchanged. No path search,
 * object registration, movement, policy limit or additional scan budget is introduced.
 *
 * @param world world providing current head geometry and selected continuations.
 * @param head captured position with canonical AB metres and travel facing.
 * @param horizonMeters traversal distance in metres, passed unchanged to Core traversal.
 * @return ordered intervals and the topology revision captured before traversal.
 * @throws IllegalArgumentException if the head rail is missing or Core rejects the distance.
 * @throws IllegalStateException if traversal yields no segments; Core traversal failures propagate.
 */
fun traceSelectedTrackAhead(world: World, head: RailPositionSnapshot, horizonMeters: Double): SelectedTrackAhead {
    val topology = world.topologyState
    val position = head
    val rail = requireNotNull(world.getRail(position.railId)) { "ATO frame references a missing head rail" }
    val location = RailLocationWithFacing(rail.getView(position.facing) as work.art1st.javarailways.core.physics.rail.RailView,
        if (position.facing == ViewDirection.AB) position.arcLengthInAbMeters
        else rail.curve.totalArcLength - position.arcLengthInAbMeters)
    val segments = location.peekCurve(horizonMeters).toList()
    check(segments.isNotEmpty()) { "SimpleATO cannot build a route from the train head" }
    return SelectedTrackAhead(topology.revision, segments)
}

/**
 * Collects current objects on an already traced path, without tracing it again. First-party query;
 * independent third-party API compatibility is not promised. For each interval, visits Core static,
 * Core dynamic, then the supplied Basic store objects. Only ACTIVE objects are considered, and
 * train ends owned by [train]'s ID are excluded. The lower bound permits [lowerToleranceMeters];
 * the upper bound is inclusive with no tolerance beyond it. Repeated references keep the nearest occurrence and the first tie.
 * Distinct live instances sharing a reference fail instead of silently replacing one another.
 *
 * Call in the world's simulation/physics context after any caller-owned route construction.
 * Captures positions here, retains original live handles, and does not read operational signal
 * policies or assign perception transitions. No new input validation or numeric normalization is
 * added; underlying object/getter failures and floating-point behavior propagate unchanged.
 * Work follows traversed intervals and their indexed objects, including repeated loop visits.
 *
 * @param train observer used for own-train ID exclusion, not for reading a fresh published frame.
 * @param path previously traced physical intervals, retaining their original live source views.
 * @param railObjects caller's existing world-local Basic object store.
 * @param lowerToleranceMeters allowed distance below each interval start, in metres.
 * @param observerSpeedMetersPerSecond observer's active speed for relative-speed calculation.
 * @return detached, read-only insertion-ordered map of nearest observations.
 * @throws IllegalStateException if distinct live object instances share a reference in this scan.
 */
fun observeTrackObjects(
    train: Train,
    path: SelectedTrackAhead,
    railObjects: RailObjectStore,
    lowerToleranceMeters: Double,
    observerSpeedMetersPerSecond: Double
): Map<RailObjectRef, PhysicalTrackObservation> {
    val result = LinkedHashMap<RailObjectRef, PhysicalTrackObservation>()
    var distanceToSegment = 0.0
    path.physicalSegments.forEach { segment ->
        val view = segment.railView
        val objects = buildList<RailObject> {
            addAll(view.staticRailObjects)
            addAll(view.dynamicRailObjects)
            addAll(railObjects.objectsOnRail(view.rail.id))
        }
        objects.forEach objectLoop@{ railObject ->
            if (railObject.lifecycleState != RailObjectLifecycleState.ACTIVE) return@objectLoop
            val trainEnd = railObject as? TrainEnd
            val otherTrain = trainEnd?.trainOrNull
            if (otherTrain?.id == train.id) return@objectLoop
            val objectArc = railObject.location.arcLengthIn(view.viewDirection)
            if (objectArc + lowerToleranceMeters < segment.startArcLength ||
                objectArc > segment.endArcLength
            ) return@objectLoop
            val distance = distanceToSegment + (objectArc - segment.startArcLength).coerceAtLeast(0.0)
            val observed = PhysicalTrackObservation(
                railObject = railObject,
                snapshot = RailObjectSnapshot.capture(railObject, view.viewDirection),
                distanceMeters = distance,
                relativeSpeedMetersPerSecond = otherTrain?.let {
                    val speedAlongRoute = if (trainEnd.location.facing == view.viewDirection) it.speed else 0.0
                    speedAlongRoute - observerSpeedMetersPerSecond
                }
            )
            val existing = result[railObject.ref]
            check(existing == null || existing.railObject === railObject) {
                "duplicate RailObjectRef ${railObject.ref} refers to distinct live objects"
            }
            if (existing == null || observed.distanceMeters < existing.distanceMeters) {
                result[railObject.ref] = observed
            }
        }
        distanceToSegment += segment.totalArcLength
    }
    return java.util.Collections.unmodifiableMap(result)
}
