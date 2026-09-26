package work.art1st.javarailways.simpleato.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.UnavailableRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots

class DispatcherSnapshotTest {
    @Test
    fun `SimpleATO runtime codec round trips the dispatcher checkpoint and rejects other versions`() {
        val snapshot = SimpleATORuntimeSnapshotV1(
            formatVersion = SimpleATORuntimeSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 7L,
            physicsEpoch = 11L,
            dispatcher = SavedDispatcherV1(
                formatVersion = SavedDispatcherV1.CURRENT_FORMAT_VERSION,
                dispatcherTick = 5L,
                missionRevision = 3L,
                clocks = emptyList(),
                runs = emptyList()
            )
        )

        assertEquals(snapshot, SimpleATORuntimeSnapshotCodec.decode(
            SimpleATORuntimeSnapshotCodec.encode(snapshot)
        ))
        assertThrows(IllegalArgumentException::class.java) {
            SimpleATORuntimeSnapshotCodec.decode(
                SimpleATORuntimeSnapshotCodec.encode(snapshot.copy(formatVersion = 2))
            )
        }
    }

    @Test
    fun `restored active runs become recovery required checkpoints`() {
        val saved = SavedDispatcherV1(
            formatVersion = SavedDispatcherV1.CURRENT_FORMAT_VERSION,
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
        val dispatcher = TimetableDispatcher(
            UnavailableRouteAdmissionTestContext,
            emptyTrainRestrictionSnapshots,
            emptyInspectionRuntime()
        )

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
