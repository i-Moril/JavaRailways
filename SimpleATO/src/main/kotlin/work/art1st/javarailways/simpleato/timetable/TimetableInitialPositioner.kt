package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import kotlin.math.abs

sealed interface TimetableInitialPositionResult {
    data class Applied(val placements: Int) : TimetableInitialPositionResult
    data class Failure(val reason: String) : TimetableInitialPositionResult
}

/** Main-thread, rollback-safe placement of one train/waypoint pair per declared constraint. */
class TimetableInitialPositioner(private val world: World) {
    private data class Target(val wayPoint: WayPoint, val location: RailLocationWithFacing)
    private data class Placement(val train: CoreTrain, val target: Target)

    fun apply(timetable: Timetable, tick: Long): TimetableInitialPositionResult {
        if (timetable.initialPositions.isEmpty()) return TimetableInitialPositionResult.Applied(0)
        val usedTrainIds = HashSet<String>()
        val usedWayPointIds = HashSet<String>()
        val placements = ArrayList<Placement>()
        return try {
            timetable.initialPositions.forEachIndexed { index, constraint ->
                val matchingTrains = world.allTrains.asSequence()
                    .filterIsInstance<CoreTrain>()
                    .filter { it.id !in usedTrainIds && constraint.trainSelector.matches(it.name) }
                    .sortedWith(compareBy<CoreTrain>({ it.name }, { it.id }))
                    .toList()
                require(matchingTrains.isNotEmpty()) {
                    "initial position #$index has no unused train matching its selector"
                }
                val trains = matchingTrains.filter { it.controller == null }
                require(trains.isNotEmpty()) {
                    val controlled = matchingTrains.joinToString { train ->
                        "${train.name}(${train.controller!!::class.java.simpleName})"
                    }
                    "initial position #$index cannot move controlled train(s): $controlled; " +
                        "release manual/ATO control before resetting the timetable"
                }
                val matchingWayPoints = world.basicWorldData.wayPoints.all().asSequence()
                    .filter { constraint.wayPointSelector.matches(it.id) }
                val depotWayPoints = world.simpleAtoWorldData.depots.allDepots().asSequence()
                    .filter { constraint.wayPointSelector.matches(it.id.value) }
                    .flatMap { depot ->
                        world.simpleAtoWorldData.depots.stablingPoints(depot.id).asSequence()
                    }
                    .map { point ->
                        val destination = world.simpleAtoWorldData.destinations[point.point.destinationId]
                            ?: error(
                                "depot ${point.point.ref.depotId} point ${point.point.ref.pointId} " +
                                    "references missing destination ${point.point.destinationId}"
                            )
                        WayPoint(
                            destination.id,
                            destination.displayName,
                            destination.railId,
                            destination.arcLengthInAbMeters
                        )
                    }
                val targets = (matchingWayPoints + depotWayPoints)
                    .distinctBy { it.id }
                    .filter { it.id !in usedWayPointIds }
                    .sortedBy { it.id }
                    .map { Target(it, resolveLocation(it)) }
                    .toList()
                require(targets.isNotEmpty()) {
                    "initial position #$index has no unused waypoint matching its selector"
                }
                val alreadySatisfied = trains.asSequence().flatMap { train ->
                    targets.asSequence().map { train to it }
                }.firstOrNull { (train, target) -> isAt(train, target.location) }
                val (train, target) = alreadySatisfied ?: (trains.first() to targets.first())
                usedTrainIds += train.id
                usedWayPointIds += target.wayPoint.id
                placements += Placement(train, target)
            }
            applyAtomically(placements)
            TimetableInitialPositionResult.Applied(placements.size)
        } catch (failure: Exception) {
            val reason = failure.message ?: failure::class.java.simpleName
            world.simpleAtoWorldData.atoCoordinator.stall(
                AtoStallReport(
                    worldId = world.simpleAtoWorldData.atoCoordinator.worldId,
                    trainId = "<timetable:${timetable.id}>",
                    tick = tick,
                    errorType = "TimetableInitialPositionFailure",
                    message = reason,
                    stackTrace = failure.stackTraceToString(),
                    initialTrain = null,
                    currentTrain = null,
                    route = null,
                    perception = null,
                    constraints = emptyMap()
                )
            )
            TimetableInitialPositionResult.Failure(reason)
        }
    }

    private fun resolveLocation(wayPoint: WayPoint): RailLocationWithFacing {
        val rail = world.getRail(wayPoint.railId) as? CoreRail
            ?: error("waypoint ${wayPoint.id} references missing rail ${wayPoint.railId}")
        val direction = world.simpleAtoWorldData.destinations[wayPoint.id]?.approachDirection
            ?: rail.infrastructureMetadata.operating.allowedDirection
            ?: error("waypoint ${wayPoint.id} and rail ${rail.id} define no allowed direction")
        val arcInView = when (direction) {
            ViewDirection.AB -> wayPoint.arcLength
            ViewDirection.BA -> rail.curve.totalArcLength - wayPoint.arcLength
        }
        require(arcInView in 0.0..rail.curve.totalArcLength) {
            "waypoint ${wayPoint.id} arc ${wayPoint.arcLength} is outside rail ${rail.id}"
        }
        return RailLocationWithFacing(rail.getView(direction), arcInView)
    }

    private fun isAt(train: CoreTrain, target: RailLocationWithFacing): Boolean =
        train.headLocation.rail.id == target.rail.id &&
            (train.headLocation as? RailLocationWithFacing)?.facing == target.facing &&
            abs(train.headLocation.arcLengthIn(ViewDirection.AB) -
                target.arcLengthIn(ViewDirection.AB)) <= POSITION_TOLERANCE_METERS

    private fun applyAtomically(placements: List<Placement>) {
        val snapshots = placements.associate { it.train to it.train.capturePlacementSnapshot() }
        try {
            placements.forEach { placement ->
                if (!isAt(placement.train, placement.target.location)) {
                    placement.train.setLocation(placement.target.location)
                    placement.train.commitMaintenancePlacement()
                    placement.train.world.publishCurrentTrainFrame(placement.train.id)
                }
            }
            val spans = world.allTrains.filterIsInstance<CoreTrain>().associateWith {
                it.activePlacementEnvelope()
            }
            val trains = spans.keys.toList()
            val selectedIds = placements.mapTo(HashSet()) { it.train.id }
            for (i in trains.indices) for (j in i + 1 until trains.size) {
                val first = trains[i]
                val second = trains[j]
                if (first.id !in selectedIds && second.id !in selectedIds) continue
                check(spans.getValue(first).none { a ->
                    spans.getValue(second).any { b -> a.overlaps(b) }
                }) { "initial placement overlaps trains ${first.name} and ${second.name}" }
            }
        } catch (failure: Exception) {
            snapshots.forEach { (train, snapshot) ->
                runCatching { train.restorePlacementSnapshot(snapshot) }
                    .exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private companion object {
        const val POSITION_TOLERANCE_METERS = 0.25
    }
}
