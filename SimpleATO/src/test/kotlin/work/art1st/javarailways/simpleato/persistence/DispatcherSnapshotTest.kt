package work.art1st.javarailways.simpleato.persistence

import work.art1st.javarailways.simpleato.simpleAtoWorldData

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.UnavailableRouteAdmissionTestContext
import work.art1st.javarailways.simpleato.timetable.RecordingTimetableAppliedPlanService
import work.art1st.javarailways.simpleato.timetable.TimetableCheckpointRestoreResult
import work.art1st.javarailways.simpleato.restriction.emptyInspectionRuntime
import work.art1st.javarailways.simpleato.restriction.emptyTrainRestrictionSnapshots
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator

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
            ),
            timetables = listOf(SavedTimetableV1.capture(Timetable("weekday")))
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
    fun `SimpleATO runtime restores timetables before dispatcher clocks`() {
        val world = createTestWorld()
        TestSimpleATOWorldDataRegistry.register(world, BasicWorldData())
        val snapshot = SimpleATORuntimeSnapshotV1(
            formatVersion = SimpleATORuntimeSnapshotV1.CURRENT_FORMAT_VERSION,
            infrastructureRevision = 0L,
            physicsEpoch = 0L,
            dispatcher = SavedDispatcherV1(
                formatVersion = SavedDispatcherV1.CURRENT_FORMAT_VERSION,
                dispatcherTick = 5L,
                missionRevision = 2L,
                clocks = listOf(SavedTimetableClockV1(
                    "weekday", 0L, 24_000L, 1L, emptySet(), emptyMap()
                )),
                runs = emptyList()
            ),
            timetables = listOf(SavedTimetableV1.capture(Timetable("weekday")))
        )

        snapshot.restore(world)

        assertEquals("weekday", world.simpleAtoWorldData.timetables.all().single().id)
        assertEquals("weekday", world.simpleAtoWorldData.dispatcher.saveCheckpoint().clocks.single().timetableId)
    }

    @Test
    fun `restored active runs become recovery required checkpoints`() {
        val world = createTestWorld()
        val data = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, data)
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(20.0, segmentCount = 1)
        )
        data.wayPoints.add(WayPoint("central@one", "Central", rail.id, 0.0))
        data.wayPoints.add(WayPoint("terminal@one", "Terminal", rail.id, 10.0))
        world.simpleAtoWorldData.timetables.add(Timetable(
            "weekday",
            services = listOf(TimetableService(
                "S1",
                TrainSelector.TrainId("T1"),
                listOf(
                    TimetableStep.Check("central@one"),
                    TimetableStep.At(0L, TimetableAction.GoTo("terminal@one"))
                )
            ))
        ))
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
            emptyInspectionRuntime(),
            RecordingTimetableAppliedPlanService()
        )

        assertEquals(
            TimetableCheckpointRestoreResult.Restored,
            dispatcher.restoreCheckpoint(world, saved)
        )

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

    @Test
    fun `restore publishes no clocks when an applied timetable cannot be reinstalled`() {
        val world = createTestWorld()
        TestSimpleATOWorldDataRegistry.register(world, BasicWorldData())
        val plans = RecordingTimetableAppliedPlanService()
        val dispatcher = TimetableDispatcher(
            UnavailableRouteAdmissionTestContext,
            emptyTrainRestrictionSnapshots,
            emptyInspectionRuntime(),
            plans
        )
        val saved = SavedDispatcherV1(
            formatVersion = SavedDispatcherV1.CURRENT_FORMAT_VERSION,
            dispatcherTick = 5L,
            missionRevision = 2L,
            clocks = listOf(SavedTimetableClockV1(
                "missing", 0L, 24_000L, 1L, emptySet(), emptyMap()
            )),
            runs = emptyList()
        )

        val result = dispatcher.restoreCheckpoint(world, saved)

        assertTrue(result is TimetableCheckpointRestoreResult.Rejected)
        assertTrue(dispatcher.saveCheckpoint().clocks.isEmpty())
        assertTrue(plans.applied.isEmpty())
    }
}
