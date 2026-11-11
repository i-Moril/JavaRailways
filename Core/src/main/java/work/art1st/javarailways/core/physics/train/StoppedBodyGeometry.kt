package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.rail.Rail
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.render.Vector3Snapshot
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveImpl
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Immutable configured-car witness for a conservative stopped-body bound. This proves neither a
 * route nor a hypothetical placement: the caller must already prove that the complete body is
 * constrained to the supplied ordered traversal. Only an explicit stopped head window narrows it.
 * Calls that bind a frame/configuration run on the serialized owner between physics frames.
 */
class StoppedBodyGeometry private constructor(private val world: World,
    private val roots: List<Root>, private val members: List<Member>) {
    val initialFrames: List<TrainFrameSnapshot> = java.util.List.copyOf(roots.map { it.frame })
    private data class Root(val frame: TrainFrameSnapshot, val members: List<Member>)
    private data class Member(val car: CoreCar, val id: String, val length: Double, val floor: Double,
        val a: Double, val b: Double, val connectorA: Double, val connectorB: Double, val orientationParity: Boolean, val bogies: List<CoreBogie>) {
        fun unchanged() = car.id == id && car.length == length && car.floorHeight == floor &&
            car.bogieAOffset == a && car.bogieBOffset == b && car.connectorLengthAtA == connectorA &&
            car.connectorLengthAtB == connectorB && car.bogies.size == 2 &&
            car.bogies.zip(bogies).all { (current, original) -> current === original }
        val endOffset get() = max(length / 2 - a, length / 2 + b)
    }

    /** Validate actual car objects/configuration; a new physical container is allowed, reused IDs are not. */
    internal fun requireTopology(topology: TopologyState) {
        require(world.topologyState === topology) { "stopped-body topology is no longer current" }
    }

    fun requireConfiguration(frame: TrainFrameSnapshot, expectedCarIds: List<String>) {
        requireFrameConfiguration(frame, selection(expectedCarIds))
    }

    private fun requireFrameConfiguration(frame: TrainFrameSnapshot, selected: List<Member>) {
        val expectedCarIds = selected.map { it.id }
        val current = requireNotNull(world.getTrain(frame.trainId)) { "stopped-body geometry train disappeared" }
        require(frame === current.publishedFrame && frame.cars.map { it.carId } == expectedCarIds &&
            stoppedBody(frame) && current.cars.size == selected.size &&
            current.cars.zip(selected).all { (car, witness) -> car === witness.car && witness.unchanged() &&
                    (car.isReversed xor frame.reversed) == witness.orientationParity }) {
            "stopped-body car configuration or identity changed" }
    }

    /** No expected future frame or nominal rail-length train model is accepted at this boundary. */
    fun enclose(topology: TopologyState, retained: List<RailTraversalSegmentSnapshot>,
        railId: UUID, facing: ViewDirection, lowerAbMeters: Double, upperAbMeters: Double,
        expectedCarIds: List<String>): StoppedBodyEnvelope {
        require(world.topologyState === topology) { "stopped-body topology changed" }
        val selected = selection(expectedCarIds)
        require(selected.all(Member::unchanged)) { "stopped-body configured geometry changed" }
        roots.forEach { requireFrameConfiguration(it.frame, it.members) }
        require(retained.isNotEmpty() && lowerAbMeters.isFinite() && upperAbMeters.isFinite() &&
            lowerAbMeters <= upperAbMeters) { "invalid stopped head window" }
        val curves = retained.map { it.railId }.distinct().associateWith { id ->
            val rail = requireNotNull(world.getRail(id)) { "stopped-body rail disappeared" }
            require(topology.railProjection.containsKey(id)) { "stopped-body rail is not in captured topology" }
            FrozenCurve.capture(rail)
        }
        val markerCurve = requireNotNull(curves[railId]) { "head window is outside retained traversal" }
        require(lowerAbMeters >= 0 && upperAbMeters <= markerCurve.length)
        val windowSpans = ArrayList<RailTraversalSegmentSnapshot>()
        for (span in retained) {
            val previous = windowSpans.lastOrNull()
            if (previous != null && previous.railId == span.railId && previous.facing == span.facing &&
                previous.endArcLengthInAbMeters == span.startArcLengthInAbMeters) {
                windowSpans[windowSpans.lastIndex] = previous.copy(endArcLengthInAbMeters = span.endArcLengthInAbMeters)
            } else windowSpans += span
        }
        require(windowSpans.any { it.railId == railId && it.facing == facing &&
            min(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters) <= lowerAbMeters &&
            max(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters) >= upperAbMeters }) {
            "complete stopped head window is outside retained traversal" }
        val pieces = retained.map { span -> curves.getValue(span.railId).pieces(span) }
        val all = pieces.flatten()
        require(all.isNotEmpty())
        val lipschitz = all.maxOf { it.slopeNorm }
        val normal = curves.values.maxOf { it.normalBound }
        val interpolation = all.fold(0.0) { sum, piece -> up(sum + piece.errorNorm) }
        var jumps = 0.0
        for (i in 1 until retained.size) {
            val before = retained[i - 1]; val next = retained[i]
            val a = curves.getValue(before.railId).position(before.endArcLengthInAbMeters)
            val b = curves.getValue(next.railId).position(next.startArcLengthInAbMeters)
            jumps = up(jumps + distance(a, b))
        }
        val coordinate = curves.values.maxOf { it.coordinateMagnitude }
        val originalMembers = roots.flatMap { it.members }
        val maxLength = originalMembers.maxOf { it.length }
        val maxFloor = selected.maxOf { abs(it.floor) }
        // The original rigid witness can contain removed cars or distant historical coordinates.
        // Its arithmetic bound must cover the complete original frame, not only this child path.
        val originalMagnitude = roots.flatMap { root -> root.frame.cars.zip(root.members) }.maxOf { (car, member) ->
            (listOf(car.state) + car.bogies).maxOf { state ->
                up(max(abs(state.positionX), max(abs(state.positionY), abs(state.positionZ))) +
                    up(abs(member.floor) * up(Math.hypot(Math.hypot(abs(state.normalX), abs(state.normalY)), abs(state.normalZ)))))
            }
        }
        val offset = selected.maxOf { it.endOffset }
        // Both orientation choices are covered. Car centres and tips lie within this magnitude
        // whenever their bogies lie on the ancestor traversal (the caller's independent premise).
        val scale = up(max(up(coordinate + up(normal * maxFloor)), originalMagnitude) + up(4.0 * maxLength))
        require(scale.isFinite() && scale > 0 && scale < sqrt(Double.MAX_VALUE) / 16.0) {
            "stopped-body coordinate magnitude cannot be bounded safely" }
        val delta = up(2.0 * scale)
        // JOML length: three products, two additions, square root. Delta-coordinate subtraction
        // contributes its own absolute ULP bound; this is per residual evaluation, not per solver trial.
        val normError = up(up(sqrt(3.0) * Math.ulp(delta)) + up(gamma(6) * up(sqrt(3.0) * delta)))
        require(originalMembers.all { it.a - it.b > up(2.0 * up(GEOMETRY_RESIDUAL + normError)) }) {
            "stopped-body axle scale is numerically unresolved" }
        // A normalized vector uses five norm-square operations, sqrt, reciprocal and multiplication.
        // With normal inputs its Euclidean magnitude is no greater than this directed upper bound.
        val directionBound = up(up((1.0 + UNIT_ROUNDOFF) * (1.0 + UNIT_ROUNDOFF)) /
            Math.nextDown((1.0 - UNIT_ROUNDOFF) * sqrt(1.0 - gamma(5))))
        // One affine anchor and one rigid tip: multiplication+addition for each of two stages.
        val tipError = up(sqrt(3.0) * up(2.0 * up(Math.ulp(maxLength) + Math.ulp(scale))))
        val residualError = up(normError + up(2.0 * tipError) + Math.ulp(delta))
        roots.forEach { requireInitialRigidWitness(it.frame, it.members, directionBound, residualError) }
        var body = 0.0
        var connectors = 0.0
        selected.forEach { body = up(body + up(it.length * directionBound))
            connectors = up(connectors + up(it.connectorA + it.connectorB)) }
        val gaps = up((selected.size - 1).toDouble() * up(GEOMETRY_RESIDUAL + residualError))
        val rigidEnds = up(2.0 * up(offset * directionBound))
        val railEnds = up(2.0 * up(up(lipschitz * up(offset + Curve.TOLERANCE)) + jumps + up(2.0 * interpolation)))
        val floors = up(2.0 * up(normal * maxFloor))
        val radius = up(body + connectors + gaps + rigidEnds + railEnds + floors + up(2.0 * up(GEOMETRY_RESIDUAL + residualError)))
        require(radius.isFinite()) { "stopped-body reach overflow" }
        val headBox = markerCurve.box(lowerAbMeters, upperAbMeters)
        val box = headBox.expanded(radius)
        // Keep every matching physical occurrence, then its full ordered hull. A loop can leave
        // the spatial box between reachable endpoints; the intervening path is never discarded.
        var firstIndex = -1; var firstArc = 0.0; var lastIndex = -1; var lastArc = 0.0
        retained.forEachIndexed { index, span ->
            val matches = pieces[index].mapNotNull { it.intersection(box) }
            if (matches.isNotEmpty()) {
                val lower = matches.minOf { it.first }; val upper = matches.maxOf { it.second }
                val start = if (span.facing == ViewDirection.AB) lower else upper
                val end = if (span.facing == ViewDirection.AB) upper else lower
                if (firstIndex < 0) { firstIndex = index; firstArc = start }
                lastIndex = index; lastArc = end
            }
        }
        require(firstIndex >= 0 && lastIndex >= firstIndex) { "stopped-body reach contains no retained occurrence" }
        val hull = retained.subList(firstIndex, lastIndex + 1).mapIndexed { index, span ->
            span.copy(startArcLengthInAbMeters = if (index == 0) firstArc else span.startArcLengthInAbMeters,
                endArcLengthInAbMeters = if (index == lastIndex - firstIndex) lastArc else span.endArcLengthInAbMeters)
        }
        return StoppedBodyEnvelope(this, topology, retained, hull, curves.values.toList(), expectedCarIds, radius)
    }

    private fun requireInitialRigidWitness(initialFrame: TrainFrameSnapshot, members: List<Member>, directionBound: Double, error: Double) {
        val frames = initialFrame.cars.associateBy { it.carId }
        fun point(x: Double, y: Double, z: Double) = Vector3Snapshot(x, y, z)
        fun tip(member: Member, head: Boolean): Vector3Snapshot {
            val state = frames.getValue(member.id).state
            val reversed = member.orientationParity xor initialFrame.reversed
            val sign = if (head xor reversed) 1.0 else -1.0
            val offset = sign * member.length / 2
            return point(state.positionX + state.directionX * offset,
                state.positionY + state.directionY * offset, state.positionZ + state.directionZ * offset)
        }
        for (member in members) {
            val frame = frames.getValue(member.id)
            require(frame.bogies.size == 2 && frame.state.directionVector().length() in (1.0 / directionBound)..directionBound) {
                "initial stopped-body car axis is not normalized" }
            fun connector(index: Int): Vector3Snapshot {
                val state = frame.bogies[index]
                return point(state.positionX + state.normalX * member.floor,
                    state.positionY + state.normalY * member.floor, state.positionZ + state.normalZ * member.floor)
            }
            val a = connector(0); val b = connector(1)
            require(abs(distance(a, b) - (member.a - member.b)) <= GEOMETRY_RESIDUAL + error) {
                "initial stopped-body axle geometry has no rigid residual witness" }
            val centre = frame.state
            val anchored = point(a.x - centre.directionX * member.a,
                a.y - centre.directionY * member.a, a.z - centre.directionZ * member.a)
            require(distance(anchored, point(centre.positionX, centre.positionY, centre.positionZ)) <= GEOMETRY_RESIDUAL + error) {
                "initial stopped-body centre is not rigidly anchored" }
        }
        val ordered = if (initialFrame.reversed) members.asReversed() else members
        ordered.zipWithNext().forEach { (before, after) ->
            val beforeReversed = before.orientationParity xor initialFrame.reversed
            val afterReversed = after.orientationParity xor initialFrame.reversed
            val expected = (if (beforeReversed) before.connectorA else before.connectorB) +
                (if (afterReversed) after.connectorB else after.connectorA)
            require(abs(distance(tip(before, false), tip(after, true)) - expected) <= GEOMETRY_RESIDUAL + error) {
                "initial stopped-body coupler geometry has no rigid residual witness" }
        }
    }

    fun configuredLengths(ids: List<String>): Map<String, Double> = java.util.Map.copyOf(
        selection(ids).associate { it.id to it.length })

    private fun selection(ids: List<String>): List<Member> {
        val start = ids.firstOrNull()?.let { id -> members.indexOfFirst { it.id == id } } ?: -1
        require(start >= 0 && start + ids.size <= members.size &&
            members.subList(start, start + ids.size).map { it.id } == ids) { "stopped-body members are not an original contiguous subset" }
        return members.subList(start, start + ids.size)
    }

    companion object {
        /**
         * Universal contact bound for cardinal, unit-arc straight rails with one constant floor
         * plane. This deliberately excludes curved/non-unit parametrizations: head endpoint
         * offsets are rail offsets in Core, so treating them as physical metres is not general.
         */
        fun straightContact(topology: TopologyState,
            approaching: StoppedBodyGeometry, approachingIds: List<String>, approachingReversed: Boolean,
            approachingTraversal: List<RailTraversalSegmentSnapshot>, approachingWindow: StoppedContactHeadWindow,
            stationary: StoppedBodyGeometry, stationaryIds: List<String>, stationaryReversed: Boolean,
            stationaryTraversal: List<RailTraversalSegmentSnapshot>, stationaryWindow: StoppedContactHeadWindow,
            stationaryAtHead: Boolean): StraightStoppedContact {
            require(approaching.world === stationary.world && approachingWindow.railId == stationaryWindow.railId)
            approaching.requireTopology(topology)
            val a = approaching.selection(approachingIds)
            val b = stationary.selection(stationaryIds)
            require(a.all(Member::unchanged) && b.all(Member::unchanged))
            require((a + b).map { it.id }.distinct().size == a.size + b.size)
            val railId = approachingWindow.railId
            val rail = requireNotNull(approaching.world.getRail(railId))
            require(rail.curve.javaClass == PrimeCurveImpl::class.java) { "straight contact requires certified PrimeCurve geometry" }
            val curve = rail.curve as PrimeCurveImpl
            val points = curve.geometryBreakpoints()
            require(points.size >= 2)
            val first = points.first()
            val last = points.last()
            val displacement = doubleArrayOf(last.position.x() - first.position.x(),
                last.position.y() - first.position.y(), last.position.z() - first.position.z())
            val axis = displacement.indices.singleOrNull { displacement[it] != 0.0 }
                ?: error("future contact requires one cardinal straight rail")
            val sign = if (displacement[axis] > 0.0) 1.0 else -1.0
            require(abs(displacement[axis]) == last.arcLength && first.arcLength == 0.0) {
                "future contact requires unit physical distance per rail metre" }
            fun component(v: org.joml.Vector3dc, i: Int) = when(i) { 0 -> v.x(); 1 -> v.y(); else -> v.z() }
            require(points.all { p ->
                (0..2).all { i -> component(p.position, i) == component(first.position, i) +
                    if (i == axis) sign * p.arcLength else 0.0 } &&
                    p.normal == first.normal && p.tangent == first.tangent
            }) { "future contact rail frame is not constant affine geometry" }
            require(component(first.normal, axis) == 0.0 && first.normal.lengthSquared() == 1.0 &&
                component(first.tangent, axis) == sign && first.tangent.lengthSquared() == 1.0) {
                "future contact requires a constant orthonormal rail frame" }
            require((a + b).map { it.floor }.distinct().size == 1) { "future contact floors differ" }
            val facing = approachingWindow.facing
            val stationaryFacing = if (stationaryAtHead) (if (facing == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB) else facing
            require(stationaryWindow.facing == stationaryFacing) { "future contact ends do not face each other" }
            val allSpans = approachingTraversal + stationaryTraversal
            require(allSpans.isNotEmpty() && allSpans.all { it.railId == railId }) {
                "future contact complete bodies must remain on the same straight rail" }
            fun ordered(members: List<Member>, reversed: Boolean) = if (reversed) members.asReversed() else members
            fun headConnector(m: Member, reversed: Boolean) = if (m.orientationParity xor reversed) m.connectorB else m.connectorA
            fun tailConnector(m: Member, reversed: Boolean) = if (m.orientationParity xor reversed) m.connectorA else m.connectorB
            val ao = ordered(a, approachingReversed)
            val bo = ordered(b, stationaryReversed)
            val bodyLength = bo.sumOf { it.length } + bo.zipWithNext().sumOf { (before, after) ->
                tailConnector(before, stationaryReversed) + headConnector(after, stationaryReversed) }
            val connector = headConnector(ao.first(), approachingReversed) +
                if (stationaryAtHead) headConnector(bo.first(), stationaryReversed) else tailConnector(bo.last(), stationaryReversed)
            val direction = if (facing == ViewDirection.AB) 1.0 else -1.0
            val stationaryDirection = if (stationaryWindow.facing == ViewDirection.AB) 1.0 else -1.0
            val shift = (if (stationaryAtHead) 0.0 else -stationaryDirection * bodyLength) - direction * connector
            // Each rigid axle/coupler solve can contribute its declared residual. The arithmetic
            // margin additionally bounds the finite sums and absolute-coordinate interpolation.
            val scale = maxOf(last.arcLength, abs(first.position.x()), abs(first.position.y()), abs(first.position.z()),
                abs(last.position.x()), abs(last.position.y()), abs(last.position.z()), bodyLength, abs(a.first().floor), 1.0)
            val error = up((a.size + b.size + 2) * (GEOMETRY_RESIDUAL + GEOMETRY_RESIDUAL) +
                gamma(128 * (a.size + b.size + 2)) * scale)
            val leading = ao.first()
            val leadingReversed = leading.orientationParity xor approachingReversed
            val endOffset = leading.length / 2.0 - if (leadingReversed) -leading.b else leading.a
            val selectedBridgeReach = 2.0 * (leading.a - leading.b) + CoreCar.FORWARD_SEARCH_RADIUS_METERS +
                headConnector(leading, approachingReversed) + endOffset
            val stationaryNearShift = if (stationaryAtHead) 0.0 else -stationaryDirection * bodyLength
            val maximumBridge = if (direction > 0.0)
                stationaryWindow.upperAbMeters + stationaryNearShift - approachingWindow.lowerAbMeters
            else approachingWindow.upperAbMeters - (stationaryWindow.lowerAbMeters + stationaryNearShift)
            require(maximumBridge + error < selectedBridgeReach) {
                "future stopped-window pair exceeds the captured Core contact continuation support" }
            val lower = down(stationaryWindow.lowerAbMeters + shift - error)
            val upper = up(stationaryWindow.upperAbMeters + shift + error)
            require(lower >= 0.0 && upper <= last.arcLength &&
                if (direction > 0.0) lower > approachingWindow.upperAbMeters + error
                else upper < approachingWindow.lowerAbMeters - error) {
                "not every future stopped-window pair has a forward separated contact" }
            val lo = minOf(allSpans.minOf { min(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters) }, lower)
            val hi = maxOf(allSpans.maxOf { max(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters) }, upper)
            require(lo >= 0.0 && hi <= last.arcLength)
            return StraightStoppedContact(rail, curve, topology, facing, lower, upper,
                RailTraversalSegmentSnapshot(railId, facing, if (direction > 0.0) lo else hi, if (direction > 0.0) hi else lo))
        }

        /** Configuration algebra only; the caller separately proves actual contact and full route ancestry. */
        fun combine(approaching: StoppedBodyGeometry, approachingCarIds: List<String>,
            stationary: StoppedBodyGeometry, stationaryCarIds: List<String>, outputCarIds: List<String>,
            flipStationary: Boolean): StoppedBodyGeometry {
            require(approaching.world === stationary.world) { "coupling geometry roots belong to different worlds" }
            val moving = approaching.selection(approachingCarIds)
            val fixed = stationary.selection(stationaryCarIds).map { it.copy(orientationParity = it.orientationParity xor flipStationary) }
            val all = moving + fixed
            require(all.map { it.id }.distinct().size == all.size && outputCarIds.size == all.size &&
                outputCarIds.toSet() == all.map { it.id }.toSet() && all.all(Member::unchanged)) {
                "combined geometry must conserve disjoint actual configured cars" }
            val byId = all.associateBy { it.id }
            val roots = (approaching.roots + stationary.roots).distinctBy { it.frame }
            roots.forEach { root -> approaching.requireFrameConfiguration(root.frame, root.members) }
            return StoppedBodyGeometry(approaching.world, java.util.List.copyOf(roots),
                java.util.List.copyOf(outputCarIds.map(byId::getValue)))
        }

        fun capture(train: Train): StoppedBodyGeometry {
            require(train is CoreTrain) { "stopped-body geometry requires Core cars" }
            val frame = train.publishedFrame
            require(!frame.derailed && frame.speedMetersPerSecond == 0.0 && stoppedBody(frame) && frame.bodyTraversal.isNotEmpty())
            // Inclusive body-end bogies are supported. In either operating orientation,
            // tailOffset >= 0 and length-tailOffset > 0 (A>B); nonnegative connectors therefore
            // make every following-car search distance positive, excluding its unchecked fallback.
            val members = train.cars.map { car ->
                require(car.bogies.size == 2 && listOf(car.length, car.floorHeight, car.bogieAOffset,
                    car.bogieBOffset, car.connectorLengthAtA, car.connectorLengthAtB).all(Double::isFinite) &&
                    car.length > 0 && car.bogieAOffset > car.bogieBOffset &&
                    car.bogieAOffset <= car.length / 2 && car.bogieBOffset >= -car.length / 2 &&
                    car.connectorLengthAtA >= 0 && car.connectorLengthAtB >= 0) {
                    "unsupported configured geometry for stopped-body bound" }
                Member(car, car.id, car.length, car.floorHeight, car.bogieAOffset, car.bogieBOffset,
                    car.connectorLengthAtA, car.connectorLengthAtB, car.isReversed xor frame.reversed, java.util.List.copyOf(car.bogies))
            }
            require(members.map { it.id } == frame.cars.map { it.carId } && members.map { it.id }.distinct().size == members.size)
            require(train.publishedFrame === frame) { "stopped-body publication changed during capture" }
            val immutable = java.util.List.copyOf(members)
            return StoppedBodyGeometry(train.world, listOf(Root(frame, immutable)), immutable)
        }
    }
}

/** Conditional spatial bound; only an already proved ancestor traversal may consume it. */
class StoppedBodyEnvelope internal constructor(private val geometry: StoppedBodyGeometry,
    private val topology: TopologyState, original: List<RailTraversalSegmentSnapshot>,
    hull: List<RailTraversalSegmentSnapshot>, private val curves: List<FrozenCurve>,
    members: List<String>, val maximumEndpointReachMeters: Double) {
    val originalTraversal: List<RailTraversalSegmentSnapshot> = java.util.List.copyOf(original)
    val bodyHull: List<RailTraversalSegmentSnapshot> = java.util.List.copyOf(hull)
    val memberCarIds: List<String> = java.util.List.copyOf(members)
    fun requireCurrent(frame: TrainFrameSnapshot, currentTopology: TopologyState, expectedCarIds: List<String>) {
        require(currentTopology === topology) { "stopped-body topology changed" }
        geometry.requireTopology(currentTopology)
        require(expectedCarIds.isNotEmpty() && memberCarIds.windowed(expectedCarIds.size).any { it == expectedCarIds })
        geometry.requireConfiguration(frame, expectedCarIds)
        require(curves.all(FrozenCurve::unchanged)) { "stopped-body rail geometry changed" }
    }
}

private fun stoppedBody(frame: TrainFrameSnapshot): Boolean = frame.cars.all { car ->
    (listOf(car.state) + car.bogies).all { state ->
        state.velocityX == 0.0 && state.velocityY == 0.0 && state.velocityZ == 0.0
    }
}

private const val GEOMETRY_RESIDUAL = CoreCar.GEOMETRY_SOLVE_TOLERANCE_METERS
private const val UNIT_ROUNDOFF = 1.1102230246251565e-16
private fun gamma(n: Int) = Math.nextUp(n * UNIT_ROUNDOFF / (1.0 - n * UNIT_ROUNDOFF))
private fun up(x: Double): Double { require(x.isFinite()) { "stopped-body arithmetic overflow" }; return Math.nextUp(x).also { require(it.isFinite()) { "stopped-body outward bound overflow" } } }
private fun down(x: Double): Double { require(x.isFinite()) { "stopped-body arithmetic overflow" }; return Math.nextDown(x).also { require(it.isFinite()) { "stopped-body outward bound overflow" } } }
private fun distance(a: Vector3Snapshot, b: Vector3Snapshot): Double =
    up(Math.hypot(Math.hypot(up(abs(a.x - b.x)), up(abs(a.y - b.y))), up(abs(a.z - b.z))))

internal class FrozenCurve private constructor(private val rail: Rail, private val curve: PrimeCurveImpl,
    private val points: List<Vertex>) {
    private data class Vertex(val arc: Double, val p: Vector3Snapshot, val normalLength: Double)
    val length = points.last().arc
    val normalBound = points.maxOf { max(up(1.0 / (1.0 - gamma(8))), it.normalLength) }
    val coordinateMagnitude = points.maxOf { max(abs(it.p.x), max(abs(it.p.y), abs(it.p.z))) }
    fun unchanged() = rail.world.getRail(rail.id) === rail && rail.curve === curve
    fun position(arc: Double): Vector3Snapshot = Vector3Snapshot.capture(curve.getPointAt(arc).position)
    fun pieces(span: RailTraversalSegmentSnapshot): List<LinearPiece> {
        val lo = min(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters)
        val hi = max(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters)
        require(lo >= 0 && hi <= length)
        return points.zipWithNext().mapNotNull { (a, b) ->
            if (b.arc < lo || a.arc > hi) null else LinearPiece(a.arc, b.arc, a.p, b.p, max(lo, a.arc), min(hi, b.arc))
        }
    }
    fun box(lo: Double, hi: Double): Box {
        val pieces = pieces(RailTraversalSegmentSnapshot(rail.id, ViewDirection.AB, lo, hi))
        return pieces.map { it.box() }.reduce(Box::union)
    }
    companion object {
        fun capture(rail: Rail): FrozenCurve {
            val curve = rail.curve
            // Exact PrimeCurveImpl construction certifies nonzero scaled T(t) x N(t) on every
            // DISCRETE interval by its Bernstein hull (margin 1e-12). Thus both interpolated
            // vectors and Gram-Schmidt normal stay far above underflow; LINE mode uses raw frames.
            // No arbitrary Curve.stream() samples or subclass interpolation are accepted.
            require(curve.javaClass == PrimeCurveImpl::class.java) { "unsupported non-affine curve implementation" }
            curve as PrimeCurveImpl
            val points = curve.geometryBreakpoints().map { p ->
                val normalLength = up(Math.hypot(Math.hypot(abs(p.normal.x()), abs(p.normal.y())), abs(p.normal.z())))
                require(normalLength.isFinite() && p.normal.lengthSquared().isFinite() && p.normal.lengthSquared() >= java.lang.Double.MIN_NORMAL &&
                    p.tangent.lengthSquared().isFinite() && p.tangent.lengthSquared() >= java.lang.Double.MIN_NORMAL) {
                    "stopped-body frame scale is numerically unresolved" }
                Vertex(p.arcLength, Vector3Snapshot.capture(p.position), up(normalLength))
            }
            require(points.size >= 2)
            return FrozenCurve(rail, curve, java.util.List.copyOf(points))
        }
    }
}

internal class Box(val lo: DoubleArray, val hi: DoubleArray) {
    fun expanded(r: Double) = Box(DoubleArray(3) { down(lo[it] - r) }, DoubleArray(3) { up(hi[it] + r) })
    fun union(other: Box) = Box(DoubleArray(3) { min(lo[it], other.lo[it]) }, DoubleArray(3) { max(hi[it], other.hi[it]) })
}
internal class LinearPiece(private val start: Double, private val end: Double,
    a: Vector3Snapshot, b: Vector3Snapshot, private val lo: Double, private val hi: Double) {
    private val p = doubleArrayOf(a.x, a.y, a.z)
    private val d = doubleArrayOf(b.x - a.x, b.y - a.y, b.z - a.z)
    private val gap = end - start
    private val error = DoubleArray(3) { axis ->
        val tError = up(Math.ulp(max(abs(start), abs(end))) / gap + Math.ulp(1.0))
        up(up(abs(d[axis]) * tError) + Math.ulp(abs(d[axis])) + Math.ulp(up(abs(p[axis]) + abs(d[axis]))))
    }
    val errorNorm = up(Math.hypot(Math.hypot(error[0], error[1]), error[2]))
    val slopeNorm = up(Math.hypot(Math.hypot(up(abs(d[0]) / gap), up(abs(d[1]) / gap)), up(abs(d[2]) / gap)))
    private fun value(arc: Double, axis: Int) = p[axis] + d[axis] * ((arc - start) / gap)
    fun box(): Box = Box(DoubleArray(3) { down(min(value(lo, it), value(hi, it)) - 2 * error[it]) },
        DoubleArray(3) { up(max(value(lo, it), value(hi, it)) + 2 * error[it]) })
    fun intersection(box: Box): Pair<Double, Double>? {
        var from = 0.0; var to = 1.0
        for (axis in 0..2) {
            val lower = down(box.lo[axis] - error[axis]); val upper = up(box.hi[axis] + error[axis])
            if (d[axis] == 0.0) { if (p[axis] < lower || p[axis] > upper) return null }
            else {
                val numeratorLo = down(lower - p[axis]); val numeratorHi = up(upper - p[axis])
                val x = if (d[axis] > 0) down(numeratorLo / d[axis]) else down(numeratorHi / d[axis])
                val y = if (d[axis] > 0) up(numeratorHi / d[axis]) else up(numeratorLo / d[axis])
                from = max(from, x); to = min(to, y)
                if (from > to) return null
            }
        }
        val lower = max(lo, down(start + down(from * gap)))
        val upper = min(hi, up(start + up(to * gap)))
        return if (lower <= upper) lower to upper else null
    }
}

/** Static input range; no physical train frame is synthesized. */
data class StoppedContactHeadWindow(val railId: UUID, val facing: ViewDirection,
    val lowerAbMeters: Double, val upperAbMeters: Double) {
    init { require(lowerAbMeters.isFinite() && upperAbMeters.isFinite() && lowerAbMeters >= 0.0 && lowerAbMeters <= upperAbMeters) }
}

/** Geometric existence over the entire input-window product, never a live contact admission. */
class StraightStoppedContact internal constructor(private val rail: Rail, private val curve: Curve,
    private val topology: TopologyState, val facing: ViewDirection,
    val lowerContactAbMeters: Double, val upperContactAbMeters: Double,
    val protectedTraversal: RailTraversalSegmentSnapshot) {
    fun requireCurrentContact(proof: work.art1st.javarailways.core.api.physics.train.CouplingApproachProof,
        currentTopology: TopologyState) {
        require(currentTopology === topology && rail.world.topologyState === topology &&
            rail.world.getRail(rail.id) === rail && rail.curve === curve) { "straight contact topology/geometry changed" }
        require(proof.contactHead.railId == rail.id && proof.contactHead.facing == facing &&
            proof.contactHead.arcLengthInAbMeters in lowerContactAbMeters..upperContactAbMeters) {
            "fresh contact lies outside the universal straight window" }
        val lo = min(protectedTraversal.startArcLengthInAbMeters, protectedTraversal.endArcLengthInAbMeters)
        val hi = max(protectedTraversal.startArcLengthInAbMeters, protectedTraversal.endArcLengthInAbMeters)
        require(proof.protectedTraversal.all { it.railId == rail.id && it.facing == facing &&
            min(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters) >= lo &&
            max(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters) <= hi }) {
            "fresh contact protection leaves the universal straight corridor" }
    }
}
