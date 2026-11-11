package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.render.Vector3Snapshot
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.SweptRailSpan
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView

/** Physical contact comparison epsilon, independent of the 1mm rigid geometry solve residual. */
internal const val COUPLER_CONTACT_EPSILON_METERS = 1e-5

/** Current contact geometry and finite captured dependencies; not another world index. */
internal data class CollisionEnvelope(
    val frame: TrainFrameSnapshot,
    val ends: Map<TrainEndSide, End>,
    val sweptSpans: List<SweptRailSpan>,
    val activeContinuations: ActiveRailContinuationsSnapshot,
    val dependencies: CollisionDependencies
) {
    internal data class End(val position: Vector3Snapshot, val outward: Vector3Snapshot,
                            val connector: Double, val path: List<RailTraversalSegmentSnapshot>) {
        fun gap(other: End): Double {
            val dx = position.x - other.position.x
            val dy = position.y - other.position.y
            val dz = position.z - other.position.z
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) - connector - other.connector
        }
        fun faces(other: End): Boolean = outward.x * other.outward.x + outward.y * other.outward.y + outward.z * other.outward.z < 0.0
        fun touches(other: End): Boolean {
            val delta = other.position.toVector3d().sub(position.toVector3d())
            return faces(other) && gap(other) <= COUPLER_CONTACT_EPSILON_METERS &&
                delta.dot(outward.toVector3d()) >= -COUPLER_CONTACT_EPSILON_METERS &&
                delta.negate().dot(other.outward.toVector3d()) >= -COUPLER_CONTACT_EPSILON_METERS
        }
    }

    fun nearestGap(other: CollisionEnvelope): Double = ends.values.minOf { own ->
        other.ends.values.minOf { incoming -> if (own.faces(incoming)) own.gap(incoming) else Double.POSITIVE_INFINITY }
    }

    /** A short bridge must be present in a captured selected continuation, never a spatial jump. */
    fun bridge(ownEnd: TrainEndSide, other: CollisionEnvelope, otherEnd: TrainEndSide): List<RailTraversalSegmentSnapshot>? {
        val own = ends.getValue(ownEnd)
        val incoming = other.ends.getValue(otherEnd)
        fun prefix(path: List<RailTraversalSegmentSnapshot>, target: RailPositionSnapshot): List<RailTraversalSegmentSnapshot>? {
            val matches = path.indices.filter { index ->
                val part = path[index]
                part.railId == target.railId && part.facing == target.facing && target.arcLengthInAbMeters >=
                    minOf(part.startArcLengthInAbMeters, part.endArcLengthInAbMeters) - 1e-8 &&
                    target.arcLengthInAbMeters <= maxOf(part.startArcLengthInAbMeters, part.endArcLengthInAbMeters) + 1e-8
            }
            if (matches.size != 1) return null
            val index = matches.single()
            return path.take(index) + path[index].copy(endArcLengthInAbMeters = target.arcLengthInAbMeters)
        }
        fun endpoint(end: TrainEndSide, envelope: CollisionEnvelope): RailPositionSnapshot =
            if ((end == TrainEndSide.A) != envelope.frame.reversed) envelope.frame.head else envelope.frame.tail.reversed()
        fun reverse(path: List<RailTraversalSegmentSnapshot>) = path.asReversed().map {
            RailTraversalSegmentSnapshot(it.railId, it.facing.reversed(), it.endArcLengthInAbMeters, it.startArcLengthInAbMeters)
        }
        // An absent passive reverse choice is not a new route; a captured competing selection
        // is positive evidence that this is the wrong branch and must not claim a collision.
        fun compatible(selected: List<RailTraversalSegmentSnapshot>, bridge: List<RailTraversalSegmentSnapshot>): Boolean =
            selected.zip(bridge).all { (a, b) -> a.railId == b.railId && a.facing == b.facing }
        prefix(own.path, endpoint(otherEnd, other).reversed())?.let {
            if (compatible(incoming.path, reverse(it))) return it
        }
        return prefix(incoming.path, endpoint(ownEnd, this).reversed())?.let(::reverse)
            ?.takeIf { compatible(own.path, it) }
    }

    companion object {
        /** Caller owns the topology read/structural boundary; identity proofs are checked only on the owner. */
        fun capture(world: CoreWorld, frame: TrainFrameSnapshot, cars: List<CoreCar>,
                    activeContinuations: ActiveRailContinuationsSnapshot,
                    reversedCars: Set<CoreCar> = emptySet(), previous: CollisionEnvelope? = null): CollisionEnvelope {
            val ends = TrainEndSide.entries.associateWith { side ->
                val index = if (side == TrainEndSide.A) 0 else cars.lastIndex
                val car = cars[index]
                val state = frame.cars[index].state
                val desiredReversed = car.isReversed xor (car in reversedCars)
                val physicalA = (side == TrainEndSide.A) != (desiredReversed xor frame.reversed)
                val outward = state.directionVector().mul(if (physicalA) 1.0 else -1.0)
                val position = state.positionVector().add(Vector3d(outward).mul(car.length / 2.0))
                val connector = if (physicalA) car.connectorLengthAtA else car.connectorLengthAtB
                val endpoint = if ((side == TrainEndSide.A) != frame.reversed) frame.head else frame.tail.reversed()
                val rail = world.getRail(endpoint.railId)
                if (rail == null) return@associateWith End(
                    Vector3Snapshot.capture(position), Vector3Snapshot.capture(outward), connector, emptyList()
                )
                var view = if (endpoint.facing == ViewDirection.AB) rail.viewAB else rail.viewBA
                var start = if (endpoint.facing == ViewDirection.AB) endpoint.arcLengthInAbMeters
                    else rail.curve.totalArcLength - endpoint.arcLengthInAbMeters
                // Same finite support domain as the existing rigid-car search, not a physical
                // arc-length interpretation of the connector. Exact contact is checked later.
                var remaining = 2.0 * (car.bogieAOffset - car.bogieBOffset) + CoreCar.FORWARD_SEARCH_RADIUS_METERS + connector +
                    if (physicalA) car.bogieAToEndPointOffset else car.bogieBToEndPointOffset
                val path = mutableListOf<RailTraversalSegmentSnapshot>()
                val visited = hashSetOf<Pair<java.util.UUID, ViewDirection>>()
                while (remaining > 0.0 && visited.add(view.rail.id to view.viewDirection)) {
                    val length = view.curve.totalArcLength
                    val end = minOf(length, start + remaining)
                    fun canonical(arc: Double): Double = if (view.viewDirection == ViewDirection.AB) arc else length - arc
                    path += RailTraversalSegmentSnapshot(view.rail.id, view.viewDirection, canonical(start), canonical(end))
                    remaining -= end - start
                    if (end < length) break
                    view = world.collisionContinuation(view, activeContinuations) ?: break
                    start = 0.0
                }
                End(Vector3Snapshot.capture(position), Vector3Snapshot.capture(outward), connector, java.util.List.copyOf(path))
            }
            val paths = ends.values.filter { it.connector > 0.0 }.flatMap { it.path }.toMutableList()
            if (previous != null && (frame.headTraversal.isNotEmpty() || frame.tailTraversal.isNotEmpty())) {
                // Reuse only the prior publication's current ends, never a chain of prior sweeps.
                paths += previous.ends.values.filter { it.connector > 0.0 }.flatMap { it.path }
            }
            return CollisionEnvelope(frame, java.util.Map.copyOf(ends), java.util.List.copyOf(paths.map {
                SweptRailSpan(frame.trainId, it.railId, minOf(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters),
                    maxOf(it.startArcLengthInAbMeters, it.endArcLengthInAbMeters), collisionOnly = true)
            }), activeContinuations, CollisionDependencies.capture(world, frame, cars,
                ends.values.map { it.path } + listOf(frame.headTraversal, frame.tailTraversal, frame.bodyTraversal),
                cars.map { it.isReversed xor (it in reversedCars) }))
        }
    }
}

private fun ViewDirection.reversed(): ViewDirection = if (this == ViewDirection.AB) ViewDirection.BA else ViewDirection.AB
private fun RailPositionSnapshot.reversed(): RailPositionSnapshot = copy(facing = facing.reversed())

/** Exact objects and required directed links used by one captured collision observation. */
internal class CollisionDependencies private constructor(
    private val rails: List<CoreRail>,
    private val connections: List<Pair<RailView, RailView>>,
    private val cars: List<CoreCar>,
    private val directions: List<Boolean>,
    private val reversed: Boolean
) {
    fun matches(train: CoreTrain): Boolean =
        train.isReversed == reversed && train.cars.size == cars.size &&
            cars.indices.all { train.cars[it] === cars[it] && cars[it].train === train &&
                cars[it].isReversed == directions[it] } &&
            rails.all { train.world.getRail(it.id) === it } &&
            connections.all { (from, to) -> from.nextRails.any { it === to } }

    companion object {
        fun capture(world: CoreWorld, frame: TrainFrameSnapshot, cars: List<CoreCar>,
                    paths: List<List<RailTraversalSegmentSnapshot>>, directions: List<Boolean>): CollisionDependencies {
            val railIds = paths.flatten().map { it.railId }.toSet() +
                setOf(frame.head.railId, frame.tail.railId)
            val rails = railIds.associateWith { id -> checkNotNull(world.getRail(id)) {
                "Collision dependency rail is missing: $id"
            } }
            val connections = paths.flatMap { path -> path.zipWithNext().mapNotNull { (a, b) ->
                if (a.railId == b.railId && a.facing == b.facing &&
                    kotlin.math.abs(a.endArcLengthInAbMeters - b.startArcLengthInAbMeters) <= 1e-8) null
                else rails.getValue(a.railId).getView(a.facing) to rails.getValue(b.railId).getView(b.facing)
            } }.distinct()
            return CollisionDependencies(rails.values.toList(), connections, cars.toList(),
                directions.toList(), frame.reversed)
        }
    }
}
