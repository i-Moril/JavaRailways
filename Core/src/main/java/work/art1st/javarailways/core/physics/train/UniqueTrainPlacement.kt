package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.exception.BogieException
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailSegment
import work.art1st.javarailways.core.physics.rail.RailSegments
import work.art1st.javarailways.core.physics.rail.RailView
import java.util.ArrayDeque
import kotlin.math.abs

/** Result of a bounded, owner-only rigid placement search. No candidate changes world state. */
sealed interface TrainPlacementResult {
    class Unique(val placement: PreparedTrainPlacement) : TrainPlacementResult
    class Unplaceable(val reason: String) : TrainPlacementResult
    class Ambiguous : TrainPlacementResult
    class SearchLimit(val reason: String) : TrainPlacementResult
}

/**
 * Single-use placement of exact newly created cars in one world's unchanged physical topology.
 * Produced only by [TrainCreation.prepareUniquePlacement], consumed by [TrainCreation.train].
 * It retains solved geometry and the clipped occupied path, never a selected-path re-solve.
 */
class PreparedTrainPlacement internal constructor(
    internal val world: CoreWorld,
    private val topologyIdentity: Any,
    internal val cars: List<CoreCar>,
    private val geometry: List<CoreCar.PreparedCarGeometry>,
    internal val head: RailLocationWithFacing,
    internal val tail: RailLocationWithFacing,
    private val body: List<RailSegment>
) {
    private var consumed = false

    internal fun validateInstalled(train: CoreTrain) {
        check(consumed && world.topologyState === topologyIdentity && train.world === world) { "prepared placement is stale" }
        check(train.cars.size == cars.size && train.cars.zip(cars).all { (a, b) -> a === b } && !train.isReversed &&
            train.head.activeLocation == head && train.tail.activeLocation == tail) { "prepared placement endpoints or consist changed" }
        check(cars.zip(geometry).all { (car, prepared) -> car.isReversed == prepared.desiredReversed &&
            car.bogieA.activeLocation == prepared.bogieALocation && car.bogieB.activeLocation == prepared.bogieBLocation &&
            car.captureActivePlacementFrame() == car.prepareStationaryFrame(prepared)
        }) { "prepared placement car geometry changed before publication" }
        check(body.zipWithNext().all { (from, to) ->
            from.railView.activeRail === to.railView && to.railView.reversed.activeRail === from.railView.reversed }) {
            "prepared placement path changed before publication"
        }
    }

    internal fun install(selected: List<CoreCar>, rollbackAfterResources: (() -> Unit) -> Unit) {
        world.physicsCoordinator.ensureBetweenFrames("PreparedTrainPlacement.install")
        check(!consumed) { "prepared train placement has already been consumed" }
        check(world.topologyState === topologyIdentity) { "prepared train placement topology changed" }
        require(selected.size == cars.size && selected.zip(cars).all { (a, b) -> a === b }) {
            "prepared train placement belongs to different cars"
        }
        check(cars.zip(geometry).all { (car, prepared) -> car.train == null && car.isReversed == prepared.desiredReversed }) {
            "prepared train placement car ownership or physical facing changed"
        }
        val selections = linkedMapOf<RailView, RailView>()
        body.zipWithNext().forEach { (from, to) ->
            for ((entry, exit) in listOf(from.railView to to.railView, to.railView.reversed to from.railView.reversed)) {
                check(exit in entry.nextRails) { "prepared train placement connection changed" }
                check(selections.put(entry, exit)?.let { it === exit } != false) { "placement requires inconsistent turnout states" }
            }
        }
        val previous = selections.keys.associateWith { it.activeRail }
        // Only the accepted path is selected; rejected candidates never write switches or cars.
        rollbackAfterResources { previous.forEach { (entry, exit) -> entry.activeRail = exit } }
        selections.forEach { (entry, exit) -> entry.activeRail = exit }
        cars.zip(geometry).forEach { (car, prepared) -> car.applyPreparedStructuralGeometry(prepared) }
        consumed = true
    }
}

private class PlacementSupportRequired : RuntimeException()
private class UnsupportedPlacementSearch(message: String) : RuntimeException(message)

/** A backward prefix from the anchored head; locations returned to cars face toward that head. */
private class PlacementPath(val segments: List<RailSegment>, val terminal: Boolean) {
    val length = segments.sumOf { it.totalArcLength }

    fun supportedDistance(requested: Double): Double {
        if (!requested.isFinite() || requested < 0.0) throw UnsupportedPlacementSearch("invalid rigid solver support distance")
        if (requested > length + 1e-9 && !terminal) throw PlacementSupportRequired()
        return minOf(requested, length)
    }

    fun location(distance: Double): RailLocationWithFacing {
        if (distance > supportedDistance(distance) + 1e-9) throw InsufficientPlacementSupport()
        var remaining = distance.coerceIn(0.0, length)
        for ((index, segment) in segments.withIndex()) {
            if (remaining <= segment.totalArcLength + 1e-9 || index == segments.lastIndex) {
                return segment.railView.getRailLocationAt(segment.startArcLength + remaining.coerceIn(0.0, segment.totalArcLength)).reversed
            }
            remaining -= segment.totalArcLength
        }
        throw UnsupportedPlacementSearch("empty placement support path")
    }

    fun backwards(from: Double, to: Double): RailSegments {
        val limit = supportedDistance(to)
        if (from > limit + 1e-9) throw InsufficientPlacementSupport()
        val result = arrayListOf<RailSegment>()
        var origin = 0.0
        for (segment in segments) {
            val end = origin + segment.totalArcLength
            val low = maxOf(from, origin)
            val high = minOf(limit, end)
            if (high > low || (result.isEmpty() && high == low && low == from)) {
                result += RailSegment(segment.railView, segment.startArcLength + low - origin,
                    segment.startArcLength + high - origin)
            }
            if (end >= limit) break
            origin = end
        }
        return RailSegments(result)
    }

    fun forwards(from: Double, to: Double): RailSegments = RailSegments(backwards(to, from).asReversed().map { segment ->
        RailSegment(segment.railView.reversed,
            segment.railView.curve.totalArcLength - segment.endArcLength,
            segment.railView.curve.totalArcLength - segment.startArcLength)
    })
}

/** Missing physical support is different from a numerical BogieException or an unexplored prefix. */
private class InsufficientPlacementSupport : RuntimeException()

internal fun prepareUniqueTrainPlacement(
    world: CoreWorld,
    cars: List<CoreCar>,
    head: RailLocationWithFacing,
    maximumPathExpansions: Int
): TrainPlacementResult {
    world.physicsCoordinator.ensureBetweenFrames("TrainCreation.prepareUniquePlacement")
    require(maximumPathExpansions > 0) { "placement search budget must be positive" }
    require(cars.isNotEmpty() && cars.all { it.world === world && it.train == null }) { "placement requires new cars in this world" }
    require(head.rail.world === world && world.getRail(head.rail.id) === head.rail) { "placement head belongs to another world" }
    val topology = world.topologyState
    if (cars.any { car -> !listOf(car.length, car.floorHeight, car.bogieAOffset, car.bogieBOffset,
            car.connectorLengthAtA, car.connectorLengthAtB).all(Double::isFinite) ||
            car.length <= 0 || car.bogieAOffset <= car.bogieBOffset ||
            car.headBogieToEndPointOffset < 0 || car.tailBogieToEndPointOffset < 0 ||
            car.connectorLengthAtA < 0 || car.connectorLengthAtB < 0 }) {
        return TrainPlacementResult.Unplaceable("invalid rigid car geometry")
    }
    val origin = head.reversed
    val first = RailSegment(origin.railView, origin.arcLength, origin.railView.curve.totalArcLength)
    val pending = ArrayDeque<PlacementPath>()
    pending.add(PlacementPath(listOf(first), origin.railView.nextRails.isEmpty()))
    val solutions = arrayListOf<Pair<List<RailSegment>, PreparedTrainPlacement>>()
    var expanded = 0
    var unproven: String? = null
    while (pending.isNotEmpty()) {
        if (Thread.currentThread().isInterrupted) return TrainPlacementResult.SearchLimit("placement search cancelled")
        if (expanded++ >= maximumPathExpansions) return TrainPlacementResult.SearchLimit("placement path search budget exceeded")
        val path = pending.removeLast()
        try {
            val prepared = arrayListOf<CoreCar.PreparedCarGeometry>()
            val lead = cars.first()
            val leadOffset = lead.headBogieToEndPointOffset
            val leadSupport = path.backwards(leadOffset, leadOffset + lead.placementAxleSearchDistance)
            val leading = lead.prepareLeadingPlacement(path.location(leadOffset), leadSupport)
            prepared += leading.geometry
            var previousTailDistance = leadOffset + leading.tailDistance
            for (index in 1 until cars.size) {
                val car = cars[index]
                val predecessor = cars[index - 1]
                val prediction = car.placementFollowingPrediction(predecessor)
                if (!prediction.isFinite() || prediction <= 0.0) throw UnsupportedPlacementSearch("unsupported nonpositive following-car search window")
                val end = path.supportedDistance(previousTailDistance + prediction * 2)
                if (end <= previousTailDistance) throw InsufficientPlacementSupport()
                val following = car.prepareFollowingPlacement(path.forwards(end, 0.0), predecessor, prepared.last(),
                    maximumTailDistance = end - previousTailDistance,
                    predictedTailDistance = end - previousTailDistance - prediction)
                prepared += following.geometry
                previousTailDistance = end - following.tailDistance
            }
            val tailDistance = previousTailDistance + cars.last().tailBogieToEndPointOffset
            val tail = path.location(tailDistance)
            val body = normalizeBody(path.forwards(tailDistance, 0.0))
            if (overlapsItself(body)) continue
            // Geometry windows may see forks beyond the final tail; they are one body placement.
            if (solutions.none { (existing, _) -> sameBody(existing, body) }) {
                solutions += body to PreparedTrainPlacement(world, topology, cars.toList(), prepared.toList(), head, tail, body)
                if (solutions.size >= 2) return TrainPlacementResult.Ambiguous()
            }
        } catch (_: PlacementSupportRequired) {
            val last = path.segments.last().railView
            val next = last.nextRails
            if (next.isEmpty()) {
                pending.add(PlacementPath(path.segments, terminal = true))
            } else next.forEach { view ->
                if (view.rail.world !== world || world.getRail(view.rail.id) !== view.rail || view.curve.totalArcLength <= 0.0) {
                    unproven = "placement contains unsupported physical continuation"
                } else pending.add(PlacementPath(path.segments + RailSegment(view), view.nextRails.isEmpty()))
            }
        } catch (_: BogieException) {
            // All requested support was present (or the physical path ended); real geometry failed.
        } catch (_: InsufficientPlacementSupport) {
            // A terminal path cannot support the actual solved bogie or body end.
        } catch (unsupported: UnsupportedPlacementSearch) {
            unproven = unsupported.message
        }
    }
    check(world.topologyState === topology) { "placement topology changed during owner preparation" }
    if (unproven != null) return TrainPlacementResult.SearchLimit(unproven)
    return solutions.singleOrNull()?.let { TrainPlacementResult.Unique(it.second) }
        ?: TrainPlacementResult.Unplaceable("no complete rigid placement exists within the supported solver windows")
}

private fun normalizeBody(segments: List<RailSegment>): List<RailSegment> {
    val result = arrayListOf<RailSegment>()
    segments.filter { it.totalArcLength > 1e-9 }.forEach { segment ->
        val previous = result.lastOrNull()
        if (previous?.railView === segment.railView && abs(previous.endArcLength - segment.startArcLength) <= 1e-9) {
            result[result.lastIndex] = RailSegment(segment.railView, previous.startArcLength, segment.endArcLength)
        } else result += segment
    }
    return result.toList()
}

private fun sameBody(a: List<RailSegment>, b: List<RailSegment>): Boolean = a.size == b.size && a.zip(b).all { (x, y) ->
    x.railView === y.railView && abs(x.startArcLength - y.startArcLength) <= Curve.TOLERANCE &&
        abs(x.endArcLength - y.endArcLength) <= Curve.TOLERANCE
}

private fun overlapsItself(body: List<RailSegment>): Boolean = body.indices.any { first ->
    val a = body[first]
    val length = a.railView.curve.totalArcLength
    val aLow = if (a.railView.viewDirection == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) a.startArcLength else length - a.endArcLength
    val aHigh = aLow + a.totalArcLength
    (first + 1 until body.size).any { second ->
        val b = body[second]
        if (a.railView.rail !== b.railView.rail) false else {
            val bLow = if (b.railView.viewDirection == work.art1st.javarailways.core.api.physics.rail.ViewDirection.AB) b.startArcLength else length - b.endArcLength
            minOf(aHigh, bLow + b.totalArcLength) - maxOf(aLow, bLow) > Curve.TOLERANCE
        }
    }
}
