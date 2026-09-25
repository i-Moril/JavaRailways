package work.art1st.javarailways.simpleato.timetable.simulation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.ato.AtoSafetyProfile
import work.art1st.javarailways.basic.api.ato.KinematicTrainActuator
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.persistence.BasicInfrastructureSnapshotV1
import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableAtoFactory
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.basic.timetable.TimetableStep
import work.art1st.javarailways.basic.timetable.TrainSelector
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class IsolatedTimetableSimulationTest {
    @BeforeEach
    fun initializePlugin() {
        if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(100)
        if (SimpleATOPlugin.instanceOrNull() == null) {
            SimpleATOPlugin.INSTANCE = SimpleATOPlugin(101)
        }
    }

    @Test
    fun `clones static infrastructure without live trains and advances an isolated timetable`() {
        val fixture = fixture()
        fixture.runtime.use { runtime ->
            assertTrue(runtime.world.allTrains.none { it.id == "live-only" })
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(fixture.timetable))

            assertTrue(result is IsolatedTimetableSimulationResult.Completed) { result.toString() }
            assertEquals(1, result.runs.size)
        }
    }

    @Test
    fun `same static inputs produce identical reports with parallel physics`() {
        val first = fixture()
        val second = fixture()
        val firstResult = first.runtime.use { it.simulate(IsolatedTimetableSimulationRequest(first.timetable)) }
        val secondResult = second.runtime.use { it.simulate(IsolatedTimetableSimulationRequest(second.timetable)) }

        assertEquals(firstResult::class, secondResult::class)
        assertEquals(firstResult.simulatedTicks, secondResult.simulatedTicks)
        assertEquals(
            firstResult.runs.map { it.copy(trainHeadRailId = null) },
            secondResult.runs.map { it.copy(trainHeadRailId = null) }
        )
    }

    @Test
    fun `derives reverse time from the real dispatcher handshake`() {
        val fixture = fixture(steps = listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0, TimetableAction.GoTo("destination")),
            TimetableStep.At(1_000, TimetableAction.Reverse),
            TimetableStep.At(1_000, TimetableAction.GoTo("origin"))
        ))
        fixture.runtime.use { runtime ->
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(
                fixture.timetable,
                delayThresholdTicks = 2
            ))

            assertTrue(result is IsolatedTimetableSimulationResult.DelayExceeded) { result.toString() }
            result as IsolatedTimetableSimulationResult.DelayExceeded
            assertTrue(result.delayedRun.nextStepIndex >= 3)
            assertTrue(result.observedDelayTicks >= 2)
        }
    }

    @Test
    fun `stops at the first configured significant delay`() {
        val fixture = fixture(
            steps = listOf(
                TimetableStep.Check("origin"),
                TimetableStep.At(0, TimetableAction.GoTo("destination")),
                TimetableStep.At(1, TimetableAction.Stop(1))
            )
        )
        fixture.runtime.use { runtime ->
            val result = runtime.simulate(IsolatedTimetableSimulationRequest(
                fixture.timetable,
                delayThresholdTicks = 20
            ))

            assertTrue(result is IsolatedTimetableSimulationResult.DelayExceeded) { result.toString() }
            result as IsolatedTimetableSimulationResult.DelayExceeded
            assertTrue(result.observedDelayTicks >= 20)
            assertEquals("origin", result.delayedRun.currentWayPointId)
            assertEquals(2, result.delayedRun.nextStepIndex)
        }
    }

    private fun fixture(
        steps: List<TimetableStep> = listOf(
            TimetableStep.Check("origin"),
            TimetableStep.At(0, TimetableAction.GoTo("destination")),
            TimetableStep.At(1_000, TimetableAction.Stop(1))
        )
    ): Fixture {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        source.registerExtensionData(BasicPlugin.INSTANCE, BasicWorldData())
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        source.basicWorldData.wayPoints.add(WayPoint("destination", "Destination", rail.id, 100.0))
        createTrainWithConfig("live-only", rail, source, listOf(10.0))
        val revision = 1L
        val coreSnapshot = source.saveInfrastructure(revision)
        val basicSnapshot = BasicInfrastructureSnapshotV1.capture(source, revision)
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
        val timetable = Timetable(
            "simulation",
            services = listOf(TimetableService(
                "service",
                TrainSelector.TrainId("simulation-train"),
                steps
            ))
        )
        val runtime = IsolatedTimetableSimulationWorld.create(
            coreSnapshot,
            basicSnapshot,
            workerThreads = 2,
            basicDataFactory = {
                BasicWorldData()
            },
            simpleAtoDataFactory = {
                val profile = AtoSafetyProfile()
                SimpleATOWorldData(TimetableDispatcher(
                    atoFactory = TimetableAtoFactory { train, world ->
                        SimpleATO(train, world, KinematicTrainActuator(10.0, profile), profile)
                    }
                ))
            },
            seed = { clone ->
                val cloneRail = requireNotNull(clone.getRail(rail.id))
                createTrainWithConfig("simulation-train", cloneRail, clone, listOf(10.0))
            }
        )
        return Fixture(runtime, timetable)
    }

    private data class Fixture(
        val runtime: IsolatedTimetableSimulationWorld,
        val timetable: Timetable
    )
}
