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
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableAction
import work.art1st.javarailways.simpleato.timetable.TimetableLineSpeedLimit
import work.art1st.javarailways.simpleato.timetable.TimetableService
import work.art1st.javarailways.simpleato.timetable.TimetableStep
import work.art1st.javarailways.simpleato.timetable.TrainSelector
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationRequest
import work.art1st.javarailways.simpleato.timetable.simulation.IsolatedTimetableSimulationResult
import work.art1st.javarailways.simpleato.SimpleATOPlugin
import work.art1st.javarailways.simpleato.SimpleATOWorldData
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.timetable.TimetableAppliedPlanService
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.MotorControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig

class IsolatedTimetableSimulationFactoryTest {
    @BeforeEach
    fun initializePlugin() {
        if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(101)
        if (SimpleATOPlugin.instanceOrNull() == null) {
            SimpleATOPlugin.INSTANCE = SimpleATOPlugin(102)
        }
    }

    @Test
    fun `creates only synthetic timetable trains from a static world snapshot`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        registerWorldData(source)
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        createTrainWithConfig("live-transient", rail, source, listOf(10.0))
        val timetable = Timetable("t", services = listOf(TimetableService(
            "duty",
            TrainSelector.TrainId("scheduled-train"),
            listOf(TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.GoTo("origin")))
        )))
        val profile = profile()
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile },
            9.80665
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
    fun `isolated timetable operation honors the shared line maximum speed resolver`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        registerWorldData(source)
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        source.basicWorldData.wayPoints.add(WayPoint("terminal", "Terminal", rail.id, 250.0))
        val destinationService = OperationalDestinationService(source)
        listOf("a" to 10.0, "b" to 280.0).forEach { (id, arcLength) ->
            destinationService.register(OperationalDestination(
                id = "$id@one",
                displayName = id.uppercase(),
                railId = rail.id,
                arcLengthInAbMeters = arcLength,
                approachDirection = ViewDirection.AB,
                role = DestinationRole.StationBerthRole(
                    stationId = StationId(id),
                    stationDisplayName = id.uppercase(),
                    berthId = "one",
                    doorSide = DoorSide.LEFT
                )
            ))
        }
        val lineId = LineId("metro")
        source.simpleAtoWorldData.lines.put(
            Line(lineId, "Metro", listOf(StationId("a"), StationId("b")), listOf(StationId("b"), StationId("a"))),
            0L
        )
        fun timetable(id: String, speed: Double) = Timetable(
            id,
            services = listOf(TimetableService(
                "duty",
                TrainSelector.TrainId("scheduled-$id"),
                listOf(
                    TimetableStep.Check("origin"),
                    TimetableStep.At(0, TimetableAction.GoTo("terminal"))
                ),
                lineId = lineId
            )),
            lineSpeedLimits = listOf(TimetableLineSpeedLimit(lineId, speed))
        )
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile() },
            9.80665
        )
        fun capture(timetable: Timetable) = factory.capture(source, timetable, listOf(
            TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")
        ))
        val slowTimetable = timetable("slow", 3.0)
        val fastTimetable = timetable("fast", 9.0)
        val slowSnapshot = capture(slowTimetable)
        val fastSnapshot = capture(fastTimetable)

        val slowTicks = factory.create(slowSnapshot, workerThreads = 1).use { simulation ->
            val result = simulation.simulate(IsolatedTimetableSimulationRequest(slowTimetable))
            assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
            result.simulatedTicks
        }
        val fastTicks = factory.create(fastSnapshot, workerThreads = 1).use { simulation ->
            val result = simulation.simulate(IsolatedTimetableSimulationRequest(fastTimetable))
            assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
            result.simulatedTicks
        }

        assertTrue(slowTicks > fastTicks)
        source.physicsCoordinator.close()
        (source.tickManager as? AutoCloseable)?.close()
    }

    @Test
    fun `reuses one named synthetic train without consuming a second origin`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(
            source,
            PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1)
        )
        registerWorldData(source)
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        val services = listOf("a", "b").map { id -> TimetableService(
            id,
            TrainSelector.TrainId("shared"),
            listOf(TimetableStep.At(0, TimetableAction.TakeFromWaypoint(listOf("origin"))))
        ) }
        val profile = profile()
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile },
            9.80665
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
        registerWorldData(source)
        val services = listOf("a", "b").map { id ->
            TimetableService(id, TrainSelector.TrainId("same-train"), emptyList())
        }
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, id -> profile(id) },
            9.80665
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

    private fun profile(id: String = "template"): SimulationControlProfile {
        val carControl = CarControlConfig(
            motor = MotorControlConfig(enabled = true, maxForce = 80_000.0, maxPower = 800_000.0)
        )
        return SimulationControlProfile(
            SimulationProfileType.TRAIN_TEMPLATE,
            id,
            TrainControlConfig(maxSpeed = 10.0),
            listOf(carControl),
            listOf(SimulationCarBlueprint(10.0, 0.0, 0.0, 0.0, 3.5, -3.5, carControl))
        )
    }

    private fun registerWorldData(world: work.art1st.javarailways.core.physics.CoreWorld) {
        val basicData = BasicWorldData()
        world.registerExtensionData(BasicPlugin.INSTANCE, basicData)
        val atoCoordinator = AtoWorldCoordinator(safetyInterlock = basicData.safetyInterlock)
        val worldId = atoCoordinator.worldId
        val restrictions = TrainRestrictionSnapshotStore(worldId)
        val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
        val signalEpoch = SignalEpochRuntime(worldId, restrictions, restrictions::completeEpoch)
        val dispatcher = TimetableDispatcher(
            TimetableRouteAdmissionContextProvider { null },
            restrictions,
            inspection,
            object : TimetableAppliedPlanService {
                override fun set(request: work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyRequest) =
                    work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationResult(
                        work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationStatus.APPLIED
                    )

                override fun remove(owner: work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner) =
                    work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationResult(
                        work.art1st.javarailways.basic.api.infrastructure.AppliedPlanDependencyMutationStatus.REMOVED
                    )
            }
        )
        world.registerExtensionData(
            SimpleATOPlugin.INSTANCE,
            SimpleATOWorldData(
                atoCoordinator,
                dispatcher,
                signalEpoch,
                restrictions,
                inspection,
                basicData.railObjects,
                basicData.safetyInterlock
            )
        )
    }
}
