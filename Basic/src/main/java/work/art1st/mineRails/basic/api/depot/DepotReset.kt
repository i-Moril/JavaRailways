package work.art1st.mineRails.basic.api.depot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import work.art1st.mineRails.basic.api.destination.DestinationRole
import work.art1st.mineRails.basic.pathFinding.basicWorldData
import work.art1st.mineRails.basic.timetable.TimetableDepotResetSummary
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.ViewDirection
import work.art1st.mineRails.core.api.util.annotation.MainThread
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import work.art1st.mineRails.core.physics.train.CoreTrain

private const val RESET_POSITION_EPSILON_METERS = 1.0e-6

enum class DepotResetStatus {
    SUCCEEDED,
    FAILED,
    PARTIALLY_COMMITTED
}

data class DepotResetReport(
    val resetId: String,
    val worldId: String,
    val status: DepotResetStatus,
    val depotIds: Set<DepotId>,
    val assignments: List<DepotResetAssignment>,
    val removedRunCount: Int = 0,
    val releasedRouteSectionCount: Int = 0,
    val releasedBerthCount: Int = 0,
    val stoppedTimetableIds: Set<String> = emptySet(),
    val failureStage: String? = null,
    val message: String,
    val occurredAtEpochMillis: Long = System.currentTimeMillis()
)

sealed interface DepotResetResult {
    val report: DepotResetReport

    data class Succeeded(override val report: DepotResetReport) : DepotResetResult
    data class Failed(override val report: DepotResetReport) : DepotResetResult
    data class PartiallyCommitted(override val report: DepotResetReport) : DepotResetResult
}

/**
 * Administrator-only Depot Reset transaction.
 *
 * All configuration, train, route, berth, and placement checks run before the first mutation. A
 * failed preflight returns a report without moving a train or changing any resource ledger. Train
 * relocation is committed and, on failure, rolled back before any Dispatcher or Depot ledger is
 * changed. An unexpected failure after ledger commit starts is reported as partially committed and
 * keeps the World Stall latched for another administrator reset.
 */
class DepotResetCoordinator(private val world: World) {
    private data class PreparedPlacement(
        val assignment: DepotResetAssignment,
        val train: CoreTrain,
        val target: RailLocationWithFacing,
        val previous: RailLocationWithFacing
    )

    @MainThread
    fun reset(depotIds: Set<DepotId>? = null): DepotResetResult {
        val worldData = world.basicWorldData
        val resetId = "depot-reset-${System.currentTimeMillis()}"
        if (!worldData.atoCoordinator.isStalled) {
            return failed(
                resetId,
                depotIds.orEmpty(),
                emptyList(),
                "WORLD_STATE",
                "Depot Reset is only legal while this World is stalled"
            )
        }
        val plan = when (val result = worldData.depots.planReset(depotIds)) {
            is DepotResetPlanningResult.Planned -> result.plan
            is DepotResetPlanningResult.Failure -> return failed(
                resetId,
                depotIds.orEmpty(),
                emptyList(),
                "DEPOT_CAPACITY",
                result.reason
            )
        }
        val prepared = ArrayList<PreparedPlacement>(plan.assignments.size)
        for (assignment in plan.assignments) {
            val train = world.getTrain(assignment.trainId) as? CoreTrain
                ?: return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "TRAIN_RESOLUTION",
                    "train not found or not movable: ${assignment.trainId}"
                )
            val rolling = worldData.depots.rollingStock(assignment.trainId)
                ?: return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "ROLLING_STOCK",
                    "RollingStock disappeared: ${assignment.trainId}"
                )
            val destination = worldData.destinations[assignment.destinationId]
                ?: return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "DESTINATION",
                    "StablingPoint Destination not found: ${assignment.destinationId}"
                )
            val role = destination.role as? DestinationRole.DepotStablingPointRole
            if (role?.pointRef != assignment.stablingPointRef) {
                return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "DESTINATION",
                    "Destination role no longer matches ${assignment.stablingPointRef}"
                )
            }
            val rail = world.getRail(destination.railId)
                ?: return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "INFRASTRUCTURE",
                    "Destination rail disappeared: ${destination.railId}"
                )
            val railLength = rail.getView(ViewDirection.AB).curve.totalArcLength
            val arcInView = when (destination.approachDirection) {
                ViewDirection.AB -> destination.arcLengthInAbMeters
                ViewDirection.BA -> railLength - destination.arcLengthInAbMeters
            }
            val target = rail.getView(destination.approachDirection)
                .getRailLocationAt(arcInView) as? RailLocationWithFacing
                ?: return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "INFRASTRUCTURE",
                    "cannot resolve directed location: ${assignment.destinationId}"
                )
            if (target.forward(-rolling.record.lengthMeters + RESET_POSITION_EPSILON_METERS) == null) {
                return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "TRAIN_ENVELOPE",
                    "full train ${assignment.trainId} does not fit behind ${assignment.destinationId}"
                )
            }
            val previous = train.headLocation as? RailLocationWithFacing
                ?: return failed(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "TRAIN_RESOLUTION",
                    "train has no directed head location: ${assignment.trainId}"
                )
            prepared += PreparedPlacement(
                assignment,
                train,
                target,
                previous
            )
        }
        for (firstIndex in prepared.indices) {
            val first = prepared[firstIndex]
            val firstArc = first.target.arcLengthIn(ViewDirection.AB)
            val firstLength = worldData.depots.rollingStock(first.assignment.trainId)!!
                .record.lengthMeters
            val firstInterval = when (first.target.facing) {
                ViewDirection.AB -> (firstArc - firstLength) to firstArc
                ViewDirection.BA -> firstArc to (firstArc + firstLength)
            }
            for (secondIndex in firstIndex + 1 until prepared.size) {
                val second = prepared[secondIndex]
                if (first.target.rail.id != second.target.rail.id) continue
                val secondArc = second.target.arcLengthIn(ViewDirection.AB)
                val secondLength = worldData.depots.rollingStock(second.assignment.trainId)!!
                    .record.lengthMeters
                val secondInterval = when (second.target.facing) {
                    ViewDirection.AB -> (secondArc - secondLength) to secondArc
                    ViewDirection.BA -> secondArc to (secondArc + secondLength)
                }
                val overlap = minOf(firstInterval.second, secondInterval.second) -
                    maxOf(firstInterval.first, secondInterval.first)
                if (overlap > RESET_POSITION_EPSILON_METERS) {
                    return failed(
                        resetId,
                        plan.depotIds,
                        plan.assignments,
                        "TRAIN_ENVELOPE",
                        "reset train envelopes overlap on rail ${first.target.rail.id}: " +
                            "${first.assignment.trainId} and ${second.assignment.trainId}"
                    )
                }
            }
        }
        worldData.depots.validateResetPlan(plan)?.let { reason ->
            return failed(
                resetId,
                plan.depotIds,
                plan.assignments,
                "DEPOT_REVALIDATION",
                reason
            )
        }
        worldData.dispatcher.validateDepotReset(
            world,
            plan.assignments.mapTo(LinkedHashSet()) { it.trainId }
        )?.let { reason ->
            return failed(
                resetId,
                plan.depotIds,
                plan.assignments,
                "DISPATCHER_REVALIDATION",
                reason
            )
        }

        val relocated = ArrayList<PreparedPlacement>(prepared.size)
        try {
            prepared.forEach { placement ->
                placement.train.setLocation(placement.target)
                relocated += placement
            }
            relocated.forEach { placement -> placement.train.commit() }
        } catch (failure: Exception) {
            val rollbackFailures = rollbackPlacements(relocated)
            if (rollbackFailures.isNotEmpty()) {
                return partiallyCommitted(
                    resetId,
                    plan.depotIds,
                    plan.assignments,
                    "TRAIN_PLACEMENT_ROLLBACK",
                    "placement failed and rollback was incomplete: " +
                        (failure.message ?: failure::class.java.simpleName) +
                        "; rollback=${rollbackFailures.joinToString()}"
                )
            }
            return failed(
                resetId,
                plan.depotIds,
                plan.assignments,
                "TRAIN_PLACEMENT",
                "placement failed; all previous locations were restored: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        }

        val trainIds = plan.assignments.mapTo(LinkedHashSet()) { it.trainId }
        var dispatcherSummary: TimetableDepotResetSummary? = null
        try {
            dispatcherSummary = worldData.dispatcher.applyDepotReset(world, trainIds)
            check(worldData.depots.applyReset(plan)) {
                "Depot reset plan changed after successful preflight"
            }
            prepared.forEach { placement ->
                if (placement.train.controller != null) placement.train.setController(null)
            }
        } catch (failure: Exception) {
            return partiallyCommitted(
                resetId,
                plan.depotIds,
                plan.assignments,
                "COMMIT_INVARIANT",
                "reset commit started but did not finish: " +
                    (failure.message ?: failure::class.java.simpleName),
                dispatcherSummary
            )
        }
        val committedSummary = requireNotNull(dispatcherSummary)
        return DepotResetResult.Succeeded(
            DepotResetReport(
                resetId = resetId,
                worldId = worldData.atoCoordinator.worldId,
                status = DepotResetStatus.SUCCEEDED,
                depotIds = plan.depotIds,
                assignments = plan.assignments,
                removedRunCount = committedSummary.removedRunCount,
                releasedRouteSectionCount = committedSummary.releasedRouteSectionCount,
                releasedBerthCount = committedSummary.releasedBerthCount,
                stoppedTimetableIds = committedSummary.stoppedTimetableIds,
                message = "Depot Reset committed; World remains stalled"
            )
        )
    }

    private fun rollbackPlacements(prepared: List<PreparedPlacement>): List<String> {
        val failures = ArrayList<String>()
        prepared.asReversed().forEach { placement ->
            runCatching {
                placement.train.setLocation(placement.previous)
                placement.train.commit()
            }.onFailure { failure ->
                failures += "${placement.assignment.trainId}:" +
                    (failure.message ?: failure::class.java.simpleName)
            }
        }
        return failures
    }

    private fun partiallyCommitted(
        resetId: String,
        depotIds: Set<DepotId>,
        assignments: List<DepotResetAssignment>,
        stage: String,
        message: String,
        summary: TimetableDepotResetSummary? = null
    ): DepotResetResult.PartiallyCommitted = DepotResetResult.PartiallyCommitted(
        DepotResetReport(
            resetId = resetId,
            worldId = world.basicWorldData.atoCoordinator.worldId,
            status = DepotResetStatus.PARTIALLY_COMMITTED,
            depotIds = depotIds,
            assignments = assignments,
            removedRunCount = summary?.removedRunCount ?: 0,
            releasedRouteSectionCount = summary?.releasedRouteSectionCount ?: 0,
            releasedBerthCount = summary?.releasedBerthCount ?: 0,
            stoppedTimetableIds = summary?.stoppedTimetableIds.orEmpty(),
            failureStage = stage,
            message = message
        )
    )

    private fun failed(
        resetId: String,
        depotIds: Set<DepotId>,
        assignments: List<DepotResetAssignment>,
        stage: String,
        message: String
    ): DepotResetResult.Failed = DepotResetResult.Failed(
        DepotResetReport(
            resetId = resetId,
            worldId = world.basicWorldData.atoCoordinator.worldId,
            status = DepotResetStatus.FAILED,
            depotIds = depotIds,
            assignments = assignments,
            failureStage = stage,
            message = message
        )
    )
}

object DepotResetReportJson {
    private val json = Json { prettyPrint = true }

    fun encode(report: DepotResetReport): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "resetId" to JsonPrimitive(report.resetId),
                "occurredAtEpochMillis" to JsonPrimitive(report.occurredAtEpochMillis),
                "worldId" to JsonPrimitive(report.worldId),
                "status" to JsonPrimitive(report.status.name),
                "depotIds" to JsonArray(
                    report.depotIds.map { it.value }.sorted().map(::JsonPrimitive)
                ),
                "assignments" to JsonArray(report.assignments.map { assignment ->
                    JsonObject(
                        mapOf(
                            "trainId" to JsonPrimitive(assignment.trainId),
                            "depotId" to JsonPrimitive(assignment.stablingPointRef.depotId.value),
                            "stablingPointId" to JsonPrimitive(assignment.stablingPointRef.pointId),
                            "destinationId" to JsonPrimitive(assignment.destinationId)
                        )
                    )
                }),
                "removedRunCount" to JsonPrimitive(report.removedRunCount),
                "releasedRouteSectionCount" to JsonPrimitive(report.releasedRouteSectionCount),
                "releasedBerthCount" to JsonPrimitive(report.releasedBerthCount),
                "stoppedTimetableIds" to JsonArray(
                    report.stoppedTimetableIds.sorted().map(::JsonPrimitive)
                ),
                "failureStage" to (report.failureStage?.let(::JsonPrimitive)
                    ?: kotlinx.serialization.json.JsonNull),
                "message" to JsonPrimitive(report.message)
            )
        )
    )
}
