package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.api.timetable.*

class TimetableCirculationSummaryTest {
    @Test fun `public projection preserves separate conclusions context and blocking without search internals`() {
        val report = CirculationReport(CirculationProof.IMPOSSIBLE, CirculationProof.UNPROVEN,
            CirculationProof.UNPROVEN, listOf(CirculationFinding("COMPLETION_BOUNDS_MISSING", "unknown"),
                CirculationFinding("RECURRING_DEPOT_DEFICIT", "loss", "out", 0, true)), exploredStates = 123)
        val summary = report.summary()
        assertEquals(TimetableCirculationStatus.IMPOSSIBLE, summary.inventory)
        assertEquals(TimetableCirculationStatus.UNPROVEN, summary.timing)
        assertEquals(TimetableCirculationStatus.UNPROVEN, summary.capacity)
        assertFalse(summary.findings.first().blocking)
        assertEquals("out", summary.findings.last().dutyId)
        assertEquals(0, summary.findings.last().stepIndex)
        assertTrue(summary.findings.last().blocking)
        val input = arrayListOf(summary.findings.first())
        val immutable = TimetableCirculationSummary(TimetableCirculationStatus.UNPROVEN,
            TimetableCirculationStatus.UNPROVEN, TimetableCirculationStatus.UNPROVEN, input)
        input.clear()
        assertEquals(1, immutable.findings.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (immutable.findings as MutableList).clear()
        }
    }

    @Test fun `inventory witness projects its recurrence without upgrading unknown timing or capacity`() {
        val findings = arrayListOf(CirculationFinding("INVENTORY_WITNESS_ONLY", "two-cycle permutation"))
        val summary = CirculationReport(CirculationProof.PROVEN, CirculationProof.UNPROVEN,
            CirculationProof.UNPROVEN, findings, periodicInventoryCycles = 2, exploredStates = 87).summary()
        findings.clear()
        assertEquals(TimetableCirculationStatus.PROVEN, summary.inventory)
        assertEquals(TimetableCirculationStatus.UNPROVEN, summary.timing)
        assertEquals(TimetableCirculationStatus.UNPROVEN, summary.capacity)
        assertEquals(2, summary.periodicInventoryCycles)
        assertEquals("INVENTORY_WITNESS_ONLY", summary.findings.single().code)
        assertFalse(summary.findings.single().blocking)
    }

}
