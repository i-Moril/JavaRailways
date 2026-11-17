package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.basic.api.route.operatingDirection
import work.art1st.javarailways.basic.api.route.wayPoints
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.simpleato.api.ato.AtoStallReport
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.PhysicsMaintenance
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainTeleportInspection
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import kotlin.math.abs

sealed interface TimetableInitialPositionResult {
    data class Applied(val placements: Int) : TimetableInitialPositionResult
    data class Failure(val reason: String) : TimetableInitialPositionResult
}

/** Selects and checks all initial positions before teleporting, within the caller's physics stall. */
class TimetableInitialPositioner(
    private val world: World,
    private val maintenance: PhysicsMaintenance
) {
    private data class Target(val wayPoint: WayPoint, val location: RailPositionSnapshot)
    private data class Placement(val train: Train, val target: Target)

    fun apply(timetable: Timetable, tick: Long): TimetableInitialPositionResult =
        apply(listOf(timetable), tick)

    fun apply(timetables: Collection<Timetable>, tick: Long): TimetableInitialPositionResult {
        check(maintenance.world === world) { "initial positioning requires this world's active physics stall" }
        if (timetables.all { it.initialPositions.isEmpty() }) return TimetableInitialPositionResult.Applied(0)
        val usedTrainIds = HashSet<String>()
        val usedWayPointIds = HashSet<String>()
        val placements = ArrayList<Placement>()
        return try {
            timetables.sortedBy(Timetable::id).forEach { timetable ->
                timetable.initialPositions.forEachIndexed { index, constraint ->
                    val label = "timetable ${timetable.id} initial position #$index"
                    val matchingTrains = world.allTrains.asSequence()
                        .filter { it.id !in usedTrainIds && constraint.trainSelector.matches(it.name) }
                        .sortedWith(compareBy<Train>({ it.name }, { it.id }))
                        .toList()
                    require(matchingTrains.isNotEmpty()) {
                        "$label has no unused train matching its selector"
                    }
                    val trains = matchingTrains.filter { it.controller == null }
                    require(trains.isNotEmpty()) {
                        val controlled = matchingTrains.joinToString { train ->
                            "${train.name}(${train.controller!!::class.java.simpleName})"
                        }
                        "$label cannot move controlled train(s): $controlled; " +
                            "release manual/ATO control before resetting the timetable"
                    }
                    val matchingWayPoints = world.wayPoints.all().asSequence()
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
                        "$label has no unused waypoint matching its selector"
                    }
                    val alreadySatisfied = trains.asSequence().flatMap { train ->
                        targets.asSequence().map { train to it }
                    }.firstOrNull { (train, target) -> isAt(train, target.location) }
                    val (train, target) = alreadySatisfied ?: (trains.first() to targets.first())
                    usedTrainIds += train.id
                    usedWayPointIds += target.wayPoint.id
                    placements += Placement(train, target)
                }
            }
            applyChecked(placements)
            TimetableInitialPositionResult.Applied(placements.size)
        } catch (failure: Exception) {
            val reason = failure.message ?: failure::class.java.simpleName
            world.simpleAtoWorldData.atoCoordinator.stall(
                AtoStallReport(
                    worldId = world.simpleAtoWorldData.atoCoordinator.worldId,
                    trainId = "<timetables:${timetables.map(Timetable::id).sorted().joinToString(",")}>",
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

    private fun resolveLocation(wayPoint: WayPoint): RailPositionSnapshot {
        val rail = world.getRail(wayPoint.railId)
            ?: error("waypoint ${wayPoint.id} references missing rail ${wayPoint.railId}")
        val direction = world.simpleAtoWorldData.destinations[wayPoint.id]?.approachDirection
            ?: world.operatingDirection(rail.id)
            ?: ViewDirection.AB
        require(wayPoint.arcLength in 0.0..rail.curve.totalArcLength) {
            "waypoint ${wayPoint.id} arc ${wayPoint.arcLength} is outside rail ${rail.id}"
        }
        return RailPositionSnapshot(rail.id, direction, wayPoint.arcLength)
    }

    private fun isAt(train: Train, target: RailPositionSnapshot): Boolean {
        val head = requireNotNull(train.publishedFrame) {
            "train ${train.id} has no published physical frame"
        }.head
        return head.railId == target.railId && head.facing == target.facing &&
            abs(head.arcLengthInAbMeters - target.arcLengthInAbMeters) <= POSITION_TOLERANCE_METERS
    }

    private fun applyChecked(placements: List<Placement>) {
        val prepared = placements.filter { !isAt(it.train, it.target.location) }.associate { placement ->
            val inspected = maintenance.inspectTeleport(placement.train, placement.target.location)
            placement.train.id to when (inspected) {
                is TrainTeleportInspection.Ready -> inspected.placement
                is TrainTeleportInspection.Rejected -> error(
                    "cannot place train ${placement.train.name} at ${placement.target.wayPoint.id}: ${inspected.reason}"
                )
            }
        }
        // Compare the final layout: participating trains vacate their old positions together.
        val trains = world.allTrains.toList()
        val selectedIds = placements.mapTo(HashSet()) { it.train.id }
        val intervals = trains.associate { train ->
            train.id to (prepared[train.id]?.occupiedRailIntervals ?: requireNotNull(train.publishedFrame) {
                "train ${train.id} has no published physical frame"
            }.occupiedIntervals)
        }
        for (i in trains.indices) for (j in i + 1 until trains.size) {
            val first = trains[i]
            val second = trains[j]
            if (first.id !in selectedIds && second.id !in selectedIds) continue
            check(intervals.getValue(first.id).none { a ->
                intervals.getValue(second.id).any { b -> a.overlaps(b) }
            }) { "initial placement overlaps trains ${first.name} and ${second.name}" }
        }
        prepared.values.forEach(maintenance::teleport)
    }

    private companion object {
        const val POSITION_TOLERANCE_METERS = work.art1st.javarailways.basic.api.ato.AtoSpatialPolicy.POSITION_TOLERANCE_METERS
    }
}
