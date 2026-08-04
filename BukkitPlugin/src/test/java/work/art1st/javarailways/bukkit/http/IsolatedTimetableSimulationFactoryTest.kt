package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.pathFinding.basicWorldData
import work.art1st.javarailways.basic.timetable.Timetable
import work.art1st.javarailways.basic.timetable.TimetableAction
import work.art1st.javarailways.basic.timetable.TimetableService
import work.art1st.javarailways.basic.timetable.TimetableStep
import work.art1st.javarailways.basic.timetable.TrainSelector
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class IsolatedTimetableSimulationFactoryTest {
    @BeforeEach
    fun initializePlugin() {
        if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(101)
    }

    @Test
    fun `creates only synthetic timetable trains from a static world snapshot`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        source.registerExtensionData(BasicPlugin.INSTANCE, BasicWorldData())
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        createTrainWithConfig("live-transient", rail, source, listOf(10.0))
        val timetable = Timetable("t", services = listOf(TimetableService(
            "duty",
            TrainSelector.TrainId("scheduled-train"),
            listOf(TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.GoTo("origin")))
        )))
        val profile = profile()
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile }
        )
        val snapshot = factory.capture(source, timetable, listOf(
            TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")
        ))
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()

        factory.create(snapshot, workerThreads = 2).use { simulation ->
            assertEquals(listOf("scheduled-train"), simulation.world.allTrains.map { it.name })
            assertFalse(simulation.world.allTrains.any { it.name == "live-transient" })
        }
    }

    @Test
    fun `reuses one named synthetic train without consuming a second origin`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        source.registerExtensionData(BasicPlugin.INSTANCE, BasicWorldData())
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        val services = listOf("a", "b").map { id -> TimetableService(
            id,
            TrainSelector.TrainId("shared"),
            listOf(TimetableStep.At(0, TimetableAction.TakeFromWaypoint(listOf("origin"))))
        ) }
        val profile = profile()
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile }
        )
        val snapshot = factory.capture(source, Timetable("t", services = services), services.map {
            TimetableProfileSelection(it.id, SimulationProfileType.TRAIN_TEMPLATE, "template")
        })

        factory.create(snapshot, workerThreads = 1).use { simulation ->
            assertEquals(listOf("shared"), simulation.world.allTrains.map { it.name })
        }
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
    }

    @Test
    fun `rejects different profiles for structurally equivalent selectors`() {
        val source = createTestWorld()
        source.registerExtensionData(BasicPlugin.INSTANCE, BasicWorldData())
        val services = listOf("a", "b").map { id ->
            TimetableService(id, TrainSelector.TrainId("same-train"), emptyList())
        }
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, id -> profile(id) }
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            factory.capture(source, Timetable("t", services = services), listOf(
                TimetableProfileSelection("a", SimulationProfileType.TRAIN_TEMPLATE, "one"),
                TimetableProfileSelection("b", SimulationProfileType.TRAIN_TEMPLATE, "two")
            ))
        }
        assertTrue(failure.message!!.contains("equivalent selectors"))
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
    }

    private fun profile(id: String = "template") = SimulationControlProfile(
        SimulationProfileType.TRAIN_TEMPLATE,
        id,
        TrainControlConfig(maxSpeed = 10.0),
        listOf(CarControlConfig()),
        listOf(SimulationCarBlueprint(10.0, 0.0, 0.0, 0.0, 3.5, -3.5, CarControlConfig()))
    )
}
