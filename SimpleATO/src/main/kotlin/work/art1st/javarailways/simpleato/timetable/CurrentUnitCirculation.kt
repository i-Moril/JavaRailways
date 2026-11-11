package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.api.depot.StablingPointRef
import kotlin.math.abs
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.api.timetable.TimetableCirculationFinding
import work.art1st.javarailways.simpleato.api.timetable.TimetableCirculationStatus
import work.art1st.javarailways.simpleato.api.timetable.TimetableCirculationSummary

/** Read one immutable physical publication paired with one atomic operational ledger, without retries. */
internal object CurrentUnitCirculation {
    fun analyze(world: World, timetable: Timetable): CirculationReport {
        val core = world as? CoreWorld ?: return unproven("PHYSICAL_PUBLICATION_UNAVAILABLE",
            "Unit circulation requires an actual immutable Core world publication")
        val frame = core.latestWorldFrame()
        val data = world.simpleAtoWorldData
        val depot = data.depots.operationalSnapshot()
        val facts = frame.trains.mapNotNull { captured -> world.getTrain(captured.trainId)?.let { train ->
            data.candidateFacts(train)?.let { captured.trainId to it }
        } }.toMap()
        val stoppedPlacements = linkedMapOf<String, StablingPointRef>()
        for (train in frame.trains) {
            fun stopped(state: work.art1st.javarailways.core.api.render.RenderableFrameSnapshot) =
                state.velocityX == 0.0 && state.velocityY == 0.0 && state.velocityZ == 0.0
            if (train.derailed || train.speedMetersPerSecond != 0.0 || train.bodyTraversal.isEmpty() ||
                train.cars.any { !stopped(it.state) || it.bogies.any { bogie -> !stopped(bogie) } }) continue
            val matches = depot.points.mapNotNull { captured ->
                val point = world.basicWorldData.wayPoints[captured.point.destinationId] ?: return@mapNotNull null
                val approach = data.destinations[captured.point.destinationId]?.approachDirection
                captured.point.ref.takeIf { train.head.railId == point.railId &&
                    abs(train.head.arcLengthInAbMeters - point.arcLength) <= TIMETABLE_ORIGIN_TOLERANCE_METERS &&
                    (approach == null || train.head.facing == approach) }
            }
            matches.singleOrNull()?.let { stoppedPlacements[train.trainId] = it }
        }
        if (core.latestWorldFrame() !== frame) return unproven("PHYSICAL_PUBLICATION_CHANGED",
            "Physical publication changed while capturing candidate facts and operational owners")
        return TimetableUnitCirculation.analyze(timetable, UnitCirculationLedger(depot.units, depot.points,
            frame.trains.associate { it.trainId to it.cars.map { car -> car.carId } },
            frame.trains.associate { it.trainId to it.reversed },
            frame.trains.flatMap { it.cars }.associate { it.carId to it.lengthMeters }, facts, stoppedPlacements,
            depot.claims, depot.heldOutputs, depot.unresolvedChanges))
    }

    private fun unproven(code: String, detail: String) = CirculationReport(CirculationProof.UNPROVEN,
        CirculationProof.UNPROVEN, CirculationProof.UNPROVEN, listOf(CirculationFinding(code, detail)))
}

/** Compiler/API projection deliberately excludes physical identities and internal search state. */
internal fun CirculationReport.summary(blockingInventoryFindings: Boolean = true) = TimetableCirculationSummary(
    inventory.publicStatus(), timing.publicStatus(), capacity.publicStatus(), findings.map {
        TimetableCirculationFinding(
            it.code, it.detail, it.dutyId, it.stepIndex, blockingInventoryFindings && it.blocking)
    }, periodicInventoryCycles)

private fun CirculationProof.publicStatus() = when (this) {
    CirculationProof.PROVEN -> TimetableCirculationStatus.PROVEN
    CirculationProof.IMPOSSIBLE -> TimetableCirculationStatus.IMPOSSIBLE
    CirculationProof.UNPROVEN -> TimetableCirculationStatus.UNPROVEN
}
