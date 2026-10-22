package work.art1st.javarailways.simpleato.timetable

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
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
        val depot = world.simpleAtoWorldData.depots.operationalSnapshot()
        if (core.latestWorldFrame() !== frame) return unproven("PHYSICAL_PUBLICATION_CHANGED",
            "Physical publication changed while capturing the operational ledger")
        return TimetableUnitCirculation.analyze(timetable, UnitCirculationLedger(depot.units, depot.stock, depot.points,
            frame.trains.associate { it.trainId to it.cars.map { car -> car.carId } },
            frame.trains.associate { it.trainId to it.reversed },
            frame.trains.flatMap { it.cars }.associate { it.carId to it.lengthMeters },
            depot.claims, depot.heldOutputs, depot.unresolvedChanges))
    }

    private fun unproven(code: String, detail: String) = CirculationReport(CirculationProof.UNPROVEN,
        CirculationProof.UNPROVEN, CirculationProof.UNPROVEN, listOf(CirculationFinding(code, detail)))
}

/** Compiler/API projection deliberately excludes physical identities and internal search state. */
internal fun CirculationReport.summary() = TimetableCirculationSummary(
    inventory.publicStatus(), timing.publicStatus(), capacity.publicStatus(), findings.map {
        TimetableCirculationFinding(
            it.code, it.detail, it.dutyId, it.stepIndex, it.blocking)
    }, periodicInventoryCycles)

private fun CirculationProof.publicStatus() = when (this) {
    CirculationProof.PROVEN -> TimetableCirculationStatus.PROVEN
    CirculationProof.IMPOSSIBLE -> TimetableCirculationStatus.IMPOSSIBLE
    CirculationProof.UNPROVEN -> TimetableCirculationStatus.UNPROVEN
}
