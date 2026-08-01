package work.art1st.javarailways.basic.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.timetable.TimetableDispatcher

class DispatcherSnapshotTest {
    @Test
    fun `restored active runs become recovery required checkpoints`() {
        val saved = SavedDispatcherV1(
            dispatcherTick = 500L,
            missionRevision = 12L,
            clocks = listOf(SavedTimetableClockV1(
                "weekday", 100L, 24_000L, 2L, setOf("S2"), mapOf("S3" to 1L)
            )),
            runs = listOf(
                SavedActiveRunCheckpointV1(
                    timetableId = "weekday",
                    serviceId = "S1",
                    cycleIndex = 1L,
                    trainId = "T1",
                    nextStepIndex = 4,
                    currentWayPointId = "central@one",
                    targetWayPointId = "terminal@one",
                    originalState = "RUNNING",
                    timetableElapsedTicks = 400L,
                    dwellTicksRemaining = 0L
                )
            )
        )
        val dispatcher = TimetableDispatcher()

        dispatcher.restoreCheckpoint(saved)

        assertEquals(
            setOf("S1", "S2"),
            dispatcher.saveCheckpoint().clocks.single().disabledServiceIds
        )
        assertEquals(mapOf("S3" to 1L),
            dispatcher.saveCheckpoint().clocks.single().pendingActivationCycles)
        assertEquals(500L, dispatcher.saveCheckpoint().dispatcherTick)
        val recovery = dispatcher.recoveryRequiredRuns().single()
        assertEquals("T1", recovery.trainId)
        assertEquals("RUNNING", recovery.originalState)
    }
}
