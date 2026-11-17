package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.basic.api.infrastructure.PhysicalRouteDependencyCompilation
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanner
import work.art1st.javarailways.basic.api.infrastructure.PhysicalRouteDependencyCompiler
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.train.StoppedBodyGeometry
import work.art1st.javarailways.core.physics.train.StoppedBodyEnvelope
import kotlin.math.abs

/** Route coordinates on the oriented corridor, including both possible extreme head positions. */
private data class StructuralOriginInterval(val firstMeters: Double, val lastMeters: Double) {
    init {
        require(firstMeters.isFinite() && firstMeters >= 0.0)
        require(lastMeters.isFinite() && lastMeters >= firstMeters)
    }
}

/** Derived geometry only: callers must still acquire output protections before releasing holds. */
internal data class ResolvedStructuralOutputRoute(
    val movement: RouteSnapshot,
    val admission: RouteSnapshot,
    val headMeters: Double,
    val tailMeters: Double
)

/**
 * Opaque full-body envelope rooted in a real initial publication or an already proved parent
 * route. Ancestry retains geometry and membership; no future TrainFrameSnapshot is fabricated.
 */
internal class CertifiedStructuralCorridor private constructor(
    val topology: TopologyState,
    val retainedPath: RouteSnapshot,
    memberCarIds: List<String>,
    val reversed: Boolean,
    initialFrames: Collection<TrainFrameSnapshot>,
    private val parentProof: StructuralOutputCorridor?,
    private val parentEnvelope: CertifiedStructuralCorridor? = null,
    internal val geometry: StoppedBodyGeometry? = null,
    private val bodyBound: StoppedBodyEnvelope? = null
) {
    val initialFrames: List<TrainFrameSnapshot> = java.util.List.copyOf(initialFrames)
    val memberCarIds: List<String> = java.util.Collections.unmodifiableList(ArrayList(memberCarIds))
    private val path = CorridorPath(normalize(canonical(retainedPath, topology)))

    /** Caller validates the whole-unit partition; Core split outputs retain subpaths of this body. */
    fun splitOutput(expectedChildCarIds: List<String>): CertifiedStructuralCorridor {
        requireMemberSlice(memberCarIds, expectedChildCarIds)
        require(expectedChildCarIds.size < memberCarIds.size) { "split output must be a proper source partition" }
        return CertifiedStructuralCorridor(topology, retainedPath, expectedChildCarIds, reversed,
            initialFrames, parentProof, this, geometry, bodyBound)
    }

    /** Reorients the entire possible body path; it neither moves nor narrows any physical range. */
    fun reoriented(reverse: Boolean): CertifiedStructuralCorridor = if (!reverse) this else
        CertifiedStructuralCorridor(topology,
            snapshot(reverseTraversal(canonical(retainedPath, topology)), retainedPath, topology),
            memberCarIds, !reversed, initialFrames, parentProof, this, geometry, bodyBound)

    /** Event-window eligibility remains a separate, narrower coordinator check. */
    fun requireContains(frame: TrainFrameSnapshot, currentTopology: TopologyState,
        expectedCarIds: List<String>, expectedReversed: Boolean) {
        require(expectedCarIds == memberCarIds && expectedReversed == reversed) {
            "source no longer matches certified membership or direction"
        }
        requireGeometry(frame, currentTopology, expectedCarIds)
        projectBody(frame, currentTopology, topology, path, expectedCarIds, expectedReversed)
    }

    internal fun requireGeometry(frame: TrainFrameSnapshot, currentTopology: TopologyState, ids: List<String>) {
        geometry?.requireConfiguration(frame, ids)
        bodyBound?.requireCurrent(frame, currentTopology, ids)
    }

    /** Only a Core-issued conservative bound at an explicit stopped window may remove history. */
    fun narrowedAt(window: StructuralEventWindow): CertifiedStructuralCorridor {
        val witness = requireNotNull(geometry) { "stopped-window narrowing has no configured Core geometry witness" }
        val original = canonical(retainedPath, topology)
        val bound = witness.enclose(topology, original, window.marker.railId, window.marker.facing,
            window.lowerAbMeters, window.upperAbMeters, memberCarIds)
        require(bound.originalTraversal == original) { "stopped-window bound belongs to another ancestor path" }
        val narrowed = snapshot(bound.bodyHull, retainedPath, topology)
        validateGeometry(topology, listOf(narrowed))
        return CertifiedStructuralCorridor(topology, narrowed, memberCarIds, reversed, initialFrames,
            parentProof, this, witness, bound)
    }

    companion object {
        /** Incoming must explicitly cover the caller-proven full stop range from this initial head. */
        fun fromInitial(initial: TrainFrameSnapshot, incoming: RouteSnapshot,
            topology: TopologyState, geometry: StoppedBodyGeometry? = null): CertifiedStructuralCorridor {
            require(geometry == null || geometry.initialFrames.size == 1 && geometry.initialFrames.single() === initial) { "initial Core geometry publication changed" }
            require(incoming.topologyRevision == topology.revision) { "stale structural corridor input" }
            val source = normalize(initial.bodyTraversal)
            require(source.isNotEmpty()) { "initial full body prefix is absent" }
            val sourcePath = CorridorPath(source)
            val memberIds = initial.cars.map { it.carId }
            val (head, tail) = projectBody(initial, topology, topology, sourcePath, memberIds, initial.reversed)
            require(tail <= EPS && abs(head - sourcePath.length) <= EPS) {
                "initial full body prefix does not match published endpoints"
            }
            val incomingPath = canonical(incoming, topology)
            if (incomingPath.isNotEmpty()) require(end(source.last()) == start(incomingPath.first())) {
                "incoming traversal omits the body head prefix"
            }
            val retained = snapshot(normalize(source + incomingPath), incoming, topology)
            CorridorPath(normalize(canonical(retained, topology)))
            validateGeometry(topology, listOf(retained) + if (incoming.segments.isNotEmpty()) listOf(incoming) else emptyList())
            return CertifiedStructuralCorridor(topology, retained, memberIds, initial.reversed, listOf(initial), null, geometry = geometry)
        }

        /** Only a complete existing proof can introduce a derived envelope. */
        internal fun fromProof(proof: StructuralOutputCorridor, memberIds: List<String>): CertifiedStructuralCorridor {
            requireMemberSlice(proof.source.memberCarIds, memberIds)
            return CertifiedStructuralCorridor(proof.source.topology, proof.onward, memberIds,
                proof.reversed, proof.source.initialFrames, proof, geometry = proof.source.geometry, bodyBound = proof.source.bodyBound)
        }
    }
}

/**
 * Conservative split-output proof. No geometry is extrapolated and no method searches for a route.
 * This is transient geometry evidence, not static coupling coverage or a movement grant.
 */
internal class StructuralOutputCorridor private constructor(
    internal val source: CertifiedStructuralCorridor,
    internal val onward: RouteSnapshot,
    internal val reversed: Boolean,
    private val corridorLength: Double,
    private val path: CorridorPath
) {
    /** Carries the entire selected path forward; never trims an uncertain rear from guessed lengths. */
    fun outputEnvelope(expectedOutputCarIds: List<String>): CertifiedStructuralCorridor =
        CertifiedStructuralCorridor.fromProof(this, expectedOutputCarIds)

    /** Uniquely binds a stopped physical output, retaining the actual body in the admission route. */
    fun resolve(frame: TrainFrameSnapshot, currentTopology: TopologyState,
        expectedCarIds: List<String>, expectedReversed: Boolean): ResolvedStructuralOutputRoute {
        requireMemberSlice(source.memberCarIds, expectedCarIds)
        require(expectedReversed == reversed) { "output direction differs from proved direction" }
        source.requireGeometry(frame, currentTopology, expectedCarIds)
        val (head, tail) = projectBody(frame, currentTopology, source.topology, path, expectedCarIds, expectedReversed)
        require(head <= corridorLength) { "output head is outside proved origin interval" }
        return ResolvedStructuralOutputRoute(trimSnapshot(onward, head), trimSnapshot(onward, tail), head, tail)
    }

    companion object {
        /** Convenience for the concrete initial-frame caller; later events use opaque envelopes. */
        fun prove(initial: TrainFrameSnapshot, incoming: RouteSnapshot,
            onward: RouteSnapshot, topology: TopologyState, restrictions: RoutePlanningRestrictions,
            reverse: Boolean, stoppingOffsetMeters: Double = 0.0): StructuralOutputCorridor =
            prove(CertifiedStructuralCorridor.fromInitial(initial, incoming, topology), onward,
                restrictions, reverse, stoppingOffsetMeters)

        /**
         * Onward must preserve the entire oriented source envelope, including the uncertain rear,
         * then reach an effective target strictly beyond it. It includes the explicitly proved
         * next stop-range bound when outputEnvelope is used for a later event.
         */
        fun prove(source: CertifiedStructuralCorridor, onward: RouteSnapshot,
            restrictions: RoutePlanningRestrictions, reverse: Boolean,
            stoppingOffsetMeters: Double = 0.0): StructuralOutputCorridor {
            val topology = source.topology
            require(onward.topologyRevision == topology.revision && restrictions.topologyRevision == topology.revision) {
                "stale structural corridor input"
            }
            require(stoppingOffsetMeters.isFinite() && stoppingOffsetMeters >= 0.0)
            TopologyRoutePlanner.invalidRestrictionsReason(topology, restrictions)?.let { throw IllegalArgumentException(it) }
            val corridor = normalize(canonical(source.retainedPath, topology)).let { if (reverse) reverseTraversal(it) else it }
            val corridorPath = CorridorPath(corridor)
            val selected = CorridorPath(normalize(canonical(onward, topology)))
            val origins = StructuralOriginInterval(0.0, corridorPath.length)
            require(selected.length - stoppingOffsetMeters > origins.lastMeters + EPS) {
                "effective target is not beyond the entire origin interval"
            }
            require(selected.length + EPS >= corridorPath.length &&
                samePath(selected.slice(0.0, corridorPath.length), corridor)) {
                "onward route does not preserve the complete mandatory corridor"
            }
            validateGeometry(topology, listOf(snapshot(corridor, onward, topology), onward))
            validateRestrictions(selected, restrictions, origins)
            return StructuralOutputCorridor(source, onward, source.reversed xor reverse, corridorPath.length, selected)
        }
    }
}

private fun requireMemberSlice(source: List<String>, selected: List<String>) {
    require(selected.isNotEmpty() && selected.distinct().size == selected.size) { "output membership is absent or ambiguous" }
    val first = source.indexOf(selected.first())
    require(first >= 0 && first + selected.size <= source.size && source.subList(first, first + selected.size) == selected) {
        "output membership is not a contiguous stable-order source partition"
    }
}

private fun validateGeometry(topology: TopologyState, routes: List<RouteSnapshot>) {
    for (route in routes) {
        val physical = PhysicalRouteDependencyCompiler.compile(topology, listOf(route))
        require(physical is PhysicalRouteDependencyCompilation.Compiled) {
            (physical as? PhysicalRouteDependencyCompilation.Rejected)?.detail ?: "corridor has no dependencies"
        }
    }
}

private fun projectBody(frame: TrainFrameSnapshot, currentTopology: TopologyState, topology: TopologyState,
    path: CorridorPath, expectedCarIds: List<String>, expectedReversed: Boolean): Pair<Double, Double> {
    require(currentTopology === topology) { "structural corridor topology changed" }
    require(!frame.derailed && frame.speedMetersPerSecond == 0.0) { "train must be stopped and aligned" }
    require(frame.reversed == expectedReversed && expectedCarIds.isNotEmpty() &&
        expectedCarIds.distinct().size == expectedCarIds.size && frame.cars.map { it.carId } == expectedCarIds) {
        "structural identity changed"
    }
    require(frame.bodyTraversal.isNotEmpty()) { "full body prefix is absent" }
    val head = path.uniquePosition(frame.head)
    val tail = path.uniquePosition(frame.tail)
    require(tail < head) { "body has no positive corridor extent" }
    require(samePath(path.slice(tail, head), normalize(frame.bodyTraversal))) {
        "complete body does not match proved corridor"
    }
    return head to tail
}

private const val EPS = 1e-7
private typealias Span = RailTraversalSegmentSnapshot
private fun start(span: Span) = RailPositionSnapshot(span.railId, span.facing, span.startArcLengthInAbMeters)
private fun end(span: Span) = RailPositionSnapshot(span.railId, span.facing, span.endArcLengthInAbMeters)
private fun spanLength(span: Span) = abs(span.endArcLengthInAbMeters - span.startArcLengthInAbMeters)
private fun reverseTraversal(spans: List<Span>) = spans.asReversed().map {
    Span(it.railId, if (it.facing == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB,
        it.endArcLengthInAbMeters, it.startArcLengthInAbMeters)
}
private fun normalize(spans: List<Span>): List<Span> = buildList {
    for (next in spans) {
        val previous = lastOrNull()
        // Edge-coordinate conversion can round the shared endpoint by a few ULPs.
        // Normalize only an adjacent, equally oriented physical rail seam, using the same
        // geometric EPS as path equality; unrelated/repeated occurrences remain ambiguous.
        if (previous != null && previous.railId == next.railId && previous.facing == next.facing &&
            abs(previous.endArcLengthInAbMeters - next.startArcLengthInAbMeters) <= EPS) {
            removeAt(lastIndex)
            add(previous.copy(endArcLengthInAbMeters = next.endArcLengthInAbMeters))
        } else add(next)
    }
}
private fun canonical(route: RouteSnapshot, topology: TopologyState): List<Span> = route.segments.map {
    val projection = topology.railProjection[it.railId]?.singleOrNull()
        ?: throw IllegalArgumentException("rail has no unique topology projection")
    val length = abs(projection.railEndAbMeters - projection.railStartAbMeters)
    require(it.endArcLengthMeters <= length + EPS) { "route exceeds physical rail" }
    Span(it.railId, it.facing,
        if (it.facing == ViewDirection.AB) it.startArcLengthMeters else length - it.startArcLengthMeters,
        if (it.facing == ViewDirection.AB) it.endArcLengthMeters else length - it.endArcLengthMeters)
}
private fun snapshot(spans: List<Span>, identity: RouteSnapshot, topology: TopologyState): RouteSnapshot =
    RouteSnapshot(identity.routeId, identity.revision, topology.revision, spans.map {
        val projection = topology.railProjection[it.railId]?.singleOrNull()
            ?: throw IllegalArgumentException("rail has no unique topology projection")
        val length = abs(projection.railEndAbMeters - projection.railStartAbMeters)
        RouteSegmentSnapshot(it.railId, it.facing,
            if (it.facing == ViewDirection.AB) it.startArcLengthInAbMeters else length - it.startArcLengthInAbMeters,
            if (it.facing == ViewDirection.AB) it.endArcLengthInAbMeters else length - it.endArcLengthInAbMeters)
    })
/** Slice the original view-coordinate route so explicit zero-length endpoint ports survive. */
private fun trimSnapshot(route: RouteSnapshot, from: Double): RouteSnapshot {
    var preceding = 0.0
    val segments = route.segments.mapNotNull { segment ->
        val end = preceding + segment.lengthMeters
        val result = when {
            segment.lengthMeters == 0.0 && preceding >= from -> segment
            end > from -> segment.copy(startArcLengthMeters = segment.startArcLengthMeters + maxOf(0.0, from - preceding))
            else -> null
        }
        preceding = end
        result
    }
    return RouteSnapshot(route.routeId, route.revision, route.topologyRevision, segments)
}
private fun samePath(first: List<Span>, second: List<Span>): Boolean {
    val a = normalize(first.filter { spanLength(it) > 0.0 })
    val b = normalize(second.filter { spanLength(it) > 0.0 })
    return a.size == b.size && a.zip(b).all { (x, y) -> x.railId == y.railId && x.facing == y.facing &&
        abs(x.startArcLengthInAbMeters - y.startArcLengthInAbMeters) <= EPS &&
        abs(x.endArcLengthInAbMeters - y.endArcLengthInAbMeters) <= EPS }
}

/** No coordinate may identify two different occurrences, even after a reversal. */
private class CorridorPath(val spans: List<Span>) {
    val length = spans.sumOf(::spanLength)
    init {
        require(spans.isNotEmpty() && length.isFinite() && length > 0.0) { "empty corridor" }
        for (i in spans.indices) for (j in 0 until i) {
            val a = spans[i]
            val b = spans[j]
            if (a.railId == b.railId) require(
                maxOf(minOf(a.startArcLengthInAbMeters, a.endArcLengthInAbMeters),
                    minOf(b.startArcLengthInAbMeters, b.endArcLengthInAbMeters)) >
                    minOf(maxOf(a.startArcLengthInAbMeters, a.endArcLengthInAbMeters),
                        maxOf(b.startArcLengthInAbMeters, b.endArcLengthInAbMeters)) + EPS
            ) { "ambiguous repeated physical corridor traversal" }
        }
    }
    fun uniquePosition(position: RailPositionSnapshot): Double {
        var preceding = 0.0
        val matches = mutableListOf<Double>()
        for (span in spans) {
            if (span.railId == position.railId && span.facing == position.facing &&
                position.arcLengthInAbMeters >= minOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters) &&
                position.arcLengthInAbMeters <= maxOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters)) {
                matches += preceding + abs(position.arcLengthInAbMeters - span.startArcLengthInAbMeters)
            }
            preceding += spanLength(span)
        }
        require(matches.isNotEmpty() && matches.all { abs(it - matches.first()) <= EPS }) {
            "position has no unique corridor occurrence"
        }
        return matches.first()
    }
    fun slice(from: Double, to: Double): List<Span> {
        require(from >= 0.0 && to <= length + EPS && to >= from)
        var preceding = 0.0
        return spans.mapNotNull { span ->
            val lo = maxOf(from, preceding)
            val hi = minOf(to, preceding + spanLength(span))
            val result = if (hi > lo) {
                val sign = if (span.facing == ViewDirection.AB) 1.0 else -1.0
                span.copy(startArcLengthInAbMeters = span.startArcLengthInAbMeters + sign * (lo - preceding),
                    endArcLengthInAbMeters = span.startArcLengthInAbMeters + sign * (hi - preceding))
            } else null
            preceding += spanLength(span)
            result
        }
    }
    fun overlaps(railId: java.util.UUID, facing: ViewDirection, lower: Double, upper: Double): List<Pair<Double, Double>> {
        var preceding = 0.0
        return spans.mapNotNull { span ->
            val lo = maxOf(lower, minOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters))
            val hi = minOf(upper, maxOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters))
            val result = if (span.railId == railId && span.facing == facing && hi > lo) {
                val a = preceding + abs(lo - span.startArcLengthInAbMeters)
                val b = preceding + abs(hi - span.startArcLengthInAbMeters)
                minOf(a, b) to maxOf(a, b)
            } else null
            preceding += spanLength(span)
            result
        }
    }
}

private fun validateRestrictions(path: CorridorPath, restrictions: RoutePlanningRestrictions,
    origins: StructuralOriginInterval) {
    for (forbidden in restrictions.forbiddenTraversals) require(path.spans.none { span ->
        span.railId == forbidden.railId && span.facing == forbidden.facing &&
            maxOf(minOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters), forbidden.lowerAbMeters) <=
                minOf(maxOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters), forbidden.upperAbMeters)
    }) { "corridor crosses forbidden traversal" }
    for (replacement in restrictions.pathReplacements) {
        val permitted = replacement.permittedPaths.map { candidate ->
            normalize(candidate.spans.map { span -> Span(span.railId, span.facing,
                if (span.facing == ViewDirection.AB) span.lowerAbMeters else span.upperAbMeters,
                if (span.facing == ViewDirection.AB) span.upperAbMeters else span.lowerAbMeters) })
        }.mapNotNull { candidate ->
            val first = try { path.uniquePosition(start(candidate.first())) } catch (_: IllegalArgumentException) { return@mapNotNull null }
            val last = first + candidate.sumOf(::spanLength)
            if (last <= path.length && samePath(path.slice(first, last), candidate)) first to last else null
        }
        for (span in replacement.replacedTraversals) for ((first, last) in path.overlaps(span.railId,
            span.facing, span.lowerAbMeters, span.upperAbMeters)) {
            require(permitted.any { it.first <= first + EPS && it.second >= last - EPS }) {
                "corridor does not preserve an atomic permitted path"
            }
        }
        for ((first, last) in permitted) require(origins.lastMeters <= first || origins.firstMeters >= last) {
            "uncertain origin intersects atomic path interior"
        }
    }
}
