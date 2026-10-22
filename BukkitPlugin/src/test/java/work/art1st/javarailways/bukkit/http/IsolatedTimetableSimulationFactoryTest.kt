package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.line.Line
import work.art1st.javarailways.simpleato.api.line.LineId
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.timetable.Timetable
import work.art1st.javarailways.simpleato.timetable.TimetableFormationCatalog
import work.art1st.javarailways.simpleato.timetable.TimetableFormationEvent
import work.art1st.javarailways.simpleato.timetable.TimetableFormationOutput
import work.art1st.javarailways.simpleato.timetable.FormationOutputDisposition
import work.art1st.javarailways.simpleato.api.depot.*
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
import work.art1st.javarailways.simpleato.timetable.InfrastructureDependencyService
import work.art1st.javarailways.simpleato.timetable.TimetableDispatcher
import work.art1st.javarailways.simpleato.timetable.TimetableRouteAdmissionContextProvider
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.config.model.MotorControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureDependencyRequest
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionProvider
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionResult
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionSimulationFactory
import work.art1st.javarailways.simpleato.api.timetable.TimetableConditionSimulationPreparation
import work.art1st.javarailways.simpleato.api.timetable.TimetableExtensionDescriptor
import work.art1st.javarailways.simpleato.api.timetable.TimetableRuntimeContext

class IsolatedTimetableSimulationFactoryTest {
    @BeforeEach
    fun initializePlugin() {
        if (BasicPlugin.instanceOrNull() == null) BasicPlugin.INSTANCE = BasicPlugin(101)
        if (SimpleATOPlugin.instanceOrNull() == null) {
            SimpleATOPlugin.INSTANCE = SimpleATOPlugin(102)
        }
        work.art1st.javarailways.core.api.util.plugin.PluginRegistry.register(BasicPlugin.INSTANCE)
        work.art1st.javarailways.core.api.util.plugin.PluginRegistry.register(SimpleATOPlugin.INSTANCE)
    }

    @Test
    fun `isolated dynamic station stop reverses and returns using actual selected marker`() {
        val source = createTestWorld()
        registerWorldData(source)
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(180.0, 1))
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        val station = StationId("central")
        for ((id, arc) in listOf("short" to 100.0, "long" to 120.0)) {
            OperationalDestinationService(source).register(OperationalDestination("central@$id", id, rail.id, arc,
                ViewDirection.AB, DestinationRole.StationBerthRole(station, berthId = id, doorSide = DoorSide.NONE,
                    usableLengthMeters = 30.0, maximumTrainLengthMeters = if (id == "short") 5.0 else 30.0,
                    defaultDwellTicks = 10)))
        }
        val selection = work.art1st.javarailways.simpleato.api.station.StationStopSelection(station,
            listOf(work.art1st.javarailways.simpleato.api.station.StationStopCandidate("short", 0),
                work.art1st.javarailways.simpleato.api.station.StationStopCandidate("long", 1)),
            work.art1st.javarailways.simpleato.api.station.StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        val timetable = Timetable("platform-turnback", services = listOf(TimetableService("duty", TrainSelector.TrainId("train"),
            listOf(TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.SelectStop(selection)),
                TimetableStep.At(0, TimetableAction.Reverse), TimetableStep.At(0, TimetableAction.GoTo("origin"))))))
        val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, id -> profile(id) },
            9.80665, atoSafetyDistanceMeters = 0.0, extensionPlugin = BasicPlugin.INSTANCE)
        try {
            val captured = factory.capture(source, timetable, listOf(TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
            factory.create(captured, workerThreads = 1).use { simulation ->
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                val run = result.runs.single()
                assertEquals("long", run.selectedBerthRef?.berthId)
                assertTrue(run.selectedBerthCommitted)
                assertFalse(run.berthHeld)
                assertEquals(20.0, simulation.world.allTrains.single().publishedFrame!!.head.arcLengthInAbMeters, 0.5)
            }
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `isolated simulation couples moves and splits the same two permanent units`() {
        val source = createTestWorld()
        registerWorldData(source)
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        val home = DepotId("home")
        val depots = source.simpleAtoWorldData.depots
        val trains = source.onOwnerForTest {
            listOf("one" to 30.0, "two" to 41.5).map { (id, arc) ->
                val head = work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(rail.viewAB, arc)
                val car = work.art1st.javarailways.core.physics.train.CoreCar.create("$id-car",
                    work.art1st.javarailways.core.physics.train.CoreBogie.create(head),
                    work.art1st.javarailways.core.physics.train.CoreBogie.create(head), length = 10.0,
                    floorHeight = 0.0, connectorLengthAtA = 0.0, connectorLengthAtB = 0.0,
                    bogieAOffset = 3.5, bogieBOffset = -3.5, world = source)
                work.art1st.javarailways.core.physics.train.CoreTrain.create(id, listOf(car), source).also {
                    it.setLocation(head); it.commitMaintenancePlacement()
                    source.basicWorldData.wayPoints.add(WayPoint(id, id, rail.id, arc))
                    OperationalDestinationService(source).register(OperationalDestination("home@$id", id, rail.id, arc,
                        ViewDirection.AB, role = DestinationRole.DepotStablingPointRole(home.value, id)))
                    depots.registerFixedUnit(FixedEmuUnit("unit-$id", home, listOf(car.id), "emu"), listOf(car.id))
                    depots.registerRollingStock(RollingStockRecord(id, home, "emu", emptySet(), 1, 10.0, 10.0, emptySet()), StablingPointRef(home, id))
                }
            }
        }
        source.basicWorldData.wayPoints.add(WayPoint("end", "End", rail.id, 120.0))
        fun at(action: TimetableAction) = TimetableStep.At(0, action)
        val timetable = Timetable("join-divide", services = listOf(
            TimetableService("one", TrainSelector.TrainId("one"), listOf(TimetableStep.Check("one"), at(TimetableAction.Couple("join")),
                at(TimetableAction.GoTo("end")), at(TimetableAction.Split("divide")))),
            TimetableService("two", TrainSelector.TrainId("two"), listOf(TimetableStep.Check("two"), at(TimetableAction.Couple("join"))))),
            formationEvents = TimetableFormationCatalog(listOf(
                TimetableFormationEvent.Couple("join", "one", "one", "two",
                    work.art1st.javarailways.simpleato.timetable.FormationStationaryEnd.TAIL,
                    work.art1st.javarailways.simpleato.timetable.FormationRendezvous("one", "two"), 0.2,
                    TimetableFormationOutput("combined", FormationOutputDisposition.Duty("one"))),
                TimetableFormationEvent.Split("divide", "one", 1,
                    TimetableFormationOutput("first", FormationOutputDisposition.Hold),
                    TimetableFormationOutput("rest", FormationOutputDisposition.Hold)))))
        val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, id ->
            profile(id).copy(type = SimulationProfileType.WORLD_TRAIN)
        }, 9.80665, atoSafetyDistanceMeters = 0.0, extensionPlugin = BasicPlugin.INSTANCE)
        try {
            val captured = factory.capture(source, timetable, trains.map { TimetableProfileSelection(it.id, SimulationProfileType.WORLD_TRAIN, it.id) })
            var completedAt: Long? = null
            repeat(2) {
                factory.create(captured, workerThreads = 1).use { simulation ->
                    val initialCars = simulation.world.allTrains.flatMap { it.cars }.map { it.id }.toSet()
                    val initialUnits = simulation.world.simpleAtoWorldData.depots.allFixedUnits()
                    val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable, delayThresholdTicks = 4000))
                    assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                    completedAt?.let { assertEquals(it, result.simulatedTicks) }
                    completedAt = result.simulatedTicks
                    assertEquals(2, simulation.world.allTrains.size)
                    assertEquals(initialCars, simulation.world.allTrains.flatMap { it.cars }.map { it.id }.toSet())
                    assertEquals(initialUnits, simulation.world.simpleAtoWorldData.depots.allFixedUnits())
                    assertTrue(simulation.world.allTrains.all { it.publishedFrame!!.head.arcLengthInAbMeters > 90.0 })
                }
            }
            assertEquals(listOf("one-car", "two-car"), trains.flatMap { it.cars }.map { it.id })
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as? AutoCloseable)?.close()
        }
    }

    @Test
    fun `structural simulation creates complete source units and derives child only from actual split`() {
        simulateSplitOutput(storeOutput = false)
    }

    @Test
    fun `split output stores in another depot without changing unit home affiliation`() {
        simulateSplitOutput(storeOutput = true)
    }

    private fun simulateSplitOutput(storeOutput: Boolean) {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        registerWorldData(source)
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 30.0))
        source.basicWorldData.wayPoints.add(WayPoint("end", "End", rail.id, 180.0))
        source.basicWorldData.wayPoints.add(WayPoint("child-end", "Child end", rail.id, 120.0))
        val train = createTrainWithConfig("live-source", rail, source, listOf(10.0, 10.0))
        val depot = DepotId("home")
        val point = StablingPointRef(depot, "point")
        val depots = source.simpleAtoWorldData.depots
        OperationalDestinationService(source).register(OperationalDestination("home@point", "Home", rail.id, 30.0, ViewDirection.AB,
            role = DestinationRole.DepotStablingPointRole(depot.value, "point", "Home")))
        val destinationDepot = DepotId("other")
        if (storeOutput) {
            OperationalDestinationService(source).register(OperationalDestination("other@point", "Other", rail.id, 240.0, ViewDirection.AB,
                role = DestinationRole.DepotStablingPointRole(destinationDepot.value, "point", "Other")))
        }
        val ids = train.cars.map { it.id }
        ids.forEachIndexed { index, id -> depots.registerFixedUnit(FixedEmuUnit("unit-$index", depot, listOf(id), "emu"), ids) }
        depots.registerRollingStock(RollingStockRecord(train.id, depot, "emu", emptySet(), 2, 20.0, 10.0, emptySet()), point)
        val base = profile()
        val profile = base.copy(type = SimulationProfileType.WORLD_TRAIN, id = train.id,
            carControls = List(2) { base.carControls.single() }, cars = List(2) { base.cars.single() })
        val factory = IsolatedTimetableSimulationFactory(IsolatedSimulationProfileResolver { _, _, _ -> profile },
            9.80665, atoSafetyDistanceMeters = 0.0, extensionPlugin = BasicPlugin.INSTANCE)
        val timetable = Timetable("formation-sim", services = listOf(
            TimetableService("source", TrainSelector.TrainId("scheduled"), listOf(TimetableStep.Check("origin"),
                TimetableStep.At(0, TimetableAction.Split("split")), TimetableStep.At(0, TimetableAction.GoTo("end"))) +
                if (storeOutput) listOf(TimetableStep.At(0, TimetableAction.StoreAtDepot(destinationDepot))) else emptyList()),
            TimetableService("child", TrainSelector.AnyTrain, listOf(TimetableStep.At(100, TimetableAction.TakeFromFormationOutput("split", "rest")),
                TimetableStep.At(100, TimetableAction.GoTo("child-end"))))),
            formationEvents = TimetableFormationCatalog(listOf(TimetableFormationEvent.Split("split", "source", 1,
                TimetableFormationOutput("first", FormationOutputDisposition.Duty("source")),
                TimetableFormationOutput("rest", FormationOutputDisposition.Duty("child"))))))
        val selection = TimetableProfileSelection("source", SimulationProfileType.WORLD_TRAIN, train.id)
        try {
            assertThrows(IllegalArgumentException::class.java) { factory.capture(source, timetable,
                listOf(selection, selection.copy(serviceId = "child"))) }
            assertThrows(IllegalArgumentException::class.java) { factory.capture(source, timetable,
                listOf(selection.copy(profileType = SimulationProfileType.TRAIN_TEMPLATE))) }
            val snapshot = factory.capture(source, timetable, listOf(selection))
            factory.create(snapshot, workerThreads = 1).use { simulation ->
                assertEquals(1, simulation.world.allTrains.size)
                val simDepots = simulation.world.simpleAtoWorldData.depots
                assertEquals(2, simDepots.allFixedUnits().size)
                assertEquals(2, simDepots.allFixedUnitAssignments().size)
                assertTrue(simDepots.allFixedUnits().all { unit -> unit.carIds.none(ids::contains) })
                val result = simulation.simulate(IsolatedTimetableSimulationRequest(timetable))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                assertEquals(2, simulation.world.allTrains.size)
                assertEquals(2, simDepots.allFixedUnits().size)
                assertEquals(if (storeOutput) 1 else 2, simDepots.allFixedUnitAssignments().size)
                assertTrue(simDepots.allFixedUnits().all { it.homeDepotId == depot })
                if (storeOutput) {
                    val stored = simDepots.allRollingStock().filter { it.state == RollingStockState.AVAILABLE }
                    assertEquals(1, stored.size)
                    assertEquals(StablingPointRef(destinationDepot, "point"), stored.single().stablingPointRef)
                }
            }
            assertEquals(ids, train.cars.map { it.id })
            assertEquals(RollingStockState.AVAILABLE, depots.rollingStock(train.id)?.state)
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as? AutoCloseable)?.close()
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
            9.80665,
            extensionPlugin = BasicPlugin.INSTANCE
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
            9.80665,
            atoSafetyDistanceMeters = 7.5,
            extensionPlugin = BasicPlugin.INSTANCE
        )
        fun capture(timetable: Timetable) = factory.capture(source, timetable, listOf(
            TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")
        ))
        val slowTimetable = timetable("slow", 3.0)
        val fastTimetable = timetable("fast", 9.0)
        val slowSnapshot = capture(slowTimetable)
        val fastSnapshot = capture(fastTimetable)
        assertEquals(7.5, slowSnapshot.atoSafetyDistanceMeters)
        assertEquals(7.5, fastSnapshot.atoSafetyDistanceMeters)

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
            9.80665,
            extensionPlugin = BasicPlugin.INSTANCE
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
            9.80665,
            extensionPlugin = BasicPlugin.INSTANCE
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

    @Test
    fun `capture rejects an installed domain feature without detached preparation`() {
        val source = createTestWorld()
        registerWorldData(source)
        try {
            val factory = IsolatedTimetableSimulationFactory(
                IsolatedSimulationProfileResolver { _, _, _ -> profile() }, 9.80665,
                requiredDomainFeatures = { setOf("simplesignals") },
                extensionPlugin = BasicPlugin.INSTANCE
            )
            val timetable = Timetable("unsupported", services = emptyList())
            val failure = assertThrows(IllegalArgumentException::class.java) {
                factory.capture(source, timetable, emptyList())
            }
            assertTrue(failure.message!!.contains("simplesignals"))
        } finally {
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    fun `one captured snapshot creates new extension and domain state for every target`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
        registerWorldData(source)
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 20.0))
        source.basicWorldData.wayPoints.add(WayPoint("terminal", "Terminal", rail.id, 100.0))
        val providerWorlds = arrayListOf<World>()
        source.simpleAtoWorldData.timetableExtensions.registerCondition(object :
            TimetableConditionProvider,
            TimetableConditionSimulationFactory {
            override val typeId = "test:captured"
            override val version = 1
            override val descriptor = TimetableExtensionDescriptor("Captured", "Target local")
            override fun evaluate(parameters: kotlinx.serialization.json.JsonObject,
                context: TimetableRuntimeContext) =
                TimetableConditionResult.True
            override fun prepareSimulation(): TimetableConditionSimulationPreparation {
                val capturedDescriptor = descriptor
                return TimetableConditionSimulationPreparation { target ->
                    providerWorlds += target
                    object : TimetableConditionProvider {
                        override val typeId = "test:captured"
                        override val version = 1
                        override val descriptor = capturedDescriptor
                        override fun evaluate(parameters: kotlinx.serialization.json.JsonObject,
                            context: TimetableRuntimeContext) =
                            TimetableConditionResult.True
                    }
                }
            }
        })
        val timetable = Timetable("t", services = listOf(TimetableService("duty", TrainSelector.TrainId("shared"),
            listOf(TimetableStep.Check("origin"), TimetableStep.At(0, TimetableAction.GoTo("terminal"))))))
        val sourceId = java.util.UUID.randomUUID()
        val features = work.art1st.javarailways.bukkit.api.internal.DefaultSimulationFeatureService()
        val epochs = linkedMapOf<java.util.UUID, java.util.concurrent.atomic.AtomicInteger>()
        val closed = arrayListOf<java.util.UUID>()
        val owner = java.lang.reflect.Proxy.newProxyInstance(org.bukkit.plugin.Plugin::class.java.classLoader,
            arrayOf(org.bukkit.plugin.Plugin::class.java)) { _, method, _ -> when (method.name) {
                "getName" -> "ExampleSignals"
                "isEnabled" -> true
                else -> null
            } } as org.bukkit.plugin.Plugin
        features.register(owner) { capturedId ->
            assertEquals(sourceId, capturedId)
            object : work.art1st.javarailways.simpleato.api.simulation.PreparedSimulationFeature {
                override val id = "examplesignals"
                override fun install(target: work.art1st.javarailways.simpleato.api.simulation.SimulationDomainTarget): AutoCloseable {
                    val calls = java.util.concurrent.atomic.AtomicInteger()
                    epochs[target.worldId] = calls
                    assertEquals(setOf(rail.id), target.topology.railProjection.keys)
                    assertEquals(setOf(rail.id), target.operatingDirections(listOf(rail.id)).directions.keys)
                    assertEquals(mapOf(rail.id to null), target.railSpeedLimits(listOf(rail.id)))
                    val token = target.installSignalSources(listOf(
                        work.art1st.javarailways.simpleato.api.signal.SignalEpochSource(
                            "example:captured", emptyList(), emptyList(), listOf(rail.id)) {
                            calls.incrementAndGet()
                            work.art1st.javarailways.simpleato.api.signal.SignalEpochResult.empty()
                        }))
                    return AutoCloseable { token.close(); closed += target.worldId }
                }
            }
        }
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> profile() }, 9.80665,
            requiredDomainFeatures = { setOf("examplesignals") },
            prepareDomainFeatures = { features.prepare(sourceId) },
            extensionPlugin = BasicPlugin.INSTANCE
        )
        try {
            val snapshot = work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run { source.executionOwner.run {
                source.physicsCoordinator.processBetweenFrames {
                    factory.capture(source, timetable, listOf(
                        TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
                }
            } }
            features.unregisterOwner(owner)
            factory.create(snapshot, 1).use { first -> factory.create(snapshot, 1).use { second ->
                assertEquals(listOf(first.world, second.world), providerWorlds)
                assertEquals(first.world.runtimeId, first.operationalRuntime.worldId)
                assertEquals(second.world.runtimeId, second.operationalRuntime.worldId)
                org.junit.jupiter.api.Assertions.assertNotEquals(first.operationalRuntime.worldId, second.operationalRuntime.worldId)
                assertNotSame(first.world.simpleAtoWorldData.timetableExtensions, second.world.simpleAtoWorldData.timetableExtensions)
                assertNotSame(first.world.simpleAtoWorldData.timetableExtensions.condition("test:captured"),
                    second.world.simpleAtoWorldData.timetableExtensions.condition("test:captured"))
                assertNotSame(first.world.simpleAtoWorldData.dispatcher, second.world.simpleAtoWorldData.dispatcher)
                assertNotSame(first.routeAdmissionHost, second.routeAdmissionHost)
                assertNotSame(first.world.basicWorldData.railMetadata, second.world.basicWorldData.railMetadata)
                val firstId = java.util.UUID.fromString(first.world.runtimeId)
                val secondId = java.util.UUID.fromString(second.world.runtimeId)
                assertNotSame(epochs[firstId], epochs[secondId])
                work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker.run {
                    first.world.physicsCoordinator.stepSimulation(50.0).await()
                }
                assertEquals(1, epochs.getValue(firstId).get())
                assertEquals(0, epochs.getValue(secondId).get())
                first.close()
                assertEquals(listOf(firstId), closed)
                val result = second.simulate(IsolatedTimetableSimulationRequest(timetable))
                assertTrue(result is IsolatedTimetableSimulationResult.Completed, result.toString())
                assertTrue(epochs.getValue(secondId).get() > 0)
                assertEquals(1, epochs.getValue(firstId).get())
            } }
        } finally {
            features.close()
            source.physicsCoordinator.close()
            (source.tickManager as AutoCloseable).close()
        }
    }

    @Test
    @OptIn(work.art1st.javarailways.core.api.util.plugin.ExtensionData::class)
    fun `mixed installation survives seed and split rebuilds ATO from exact current car profiles`() {
        val source = createTestWorld()
        val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(300.0, 1))
        registerWorldData(source)
        source.basicWorldData.wayPoints.add(WayPoint("origin", "Origin", rail.id, 60.0))
        source.basicWorldData.wayPoints.add(WayPoint("terminal", "Terminal", rail.id, 250.0))
        val controls = listOf(20_000.0, 30_000.0, 40_000.0).map { CarControlConfig(mass = it) }
        val base = profile()
        val mixed = base.copy(carControls = controls, cars = controls.mapIndexed { index, control ->
            base.cars.single().copy(control = control, installedReversed = index != 1)
        })
        val timetable = Timetable("structure", services = listOf(TimetableService("duty",
            TrainSelector.TrainId("scheduled"), listOf(TimetableStep.Check("origin"),
                TimetableStep.At(0, TimetableAction.GoTo("terminal"))))))
        val factory = IsolatedTimetableSimulationFactory(
            IsolatedSimulationProfileResolver { _, _, _ -> mixed }, 9.80665,
            extensionPlugin = BasicPlugin.INSTANCE)
        val snapshot = factory.capture(source, timetable, listOf(
            TimetableProfileSelection("duty", SimulationProfileType.TRAIN_TEMPLATE, "template")))
        source.physicsCoordinator.close()
        (source.tickManager as AutoCloseable).close()
        factory.create(snapshot, 1).use { simulation ->
            val world = simulation.world
            val train = world.allTrains.single() as work.art1st.javarailways.core.physics.train.CoreTrain
            assertEquals(listOf(true, false, true), world.onOwnerForTest { train.save() }.cars.map { it.installedReversed })
            val originalCars = train.cars.toList()
            val originalExtensions = originalCars.map {
                it.getExtensionDataOrNull<work.art1st.javarailways.bukkit.operation.SimulationCarBehaviorExtension>(BasicPlugin.INSTANCE)
            }
            world.onOwnerForTest {
                world.simpleAtoWorldData.timetables.add(timetable)
                val started = world.simpleAtoWorldData.dispatcher.startService(world, timetable.id, "duty")
                assertTrue(started is work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult.Started, started.toString())
            }
            val originalAto = train.controller as work.art1st.javarailways.simpleato.controller.SimpleATO
            val split = train.splitBefore(originalCars[1].id, "separated")
            world.onOwnerForTest { world.physicsCoordinator.drainBetweenFramesOperations() }
            val separated = split.get(5, java.util.concurrent.TimeUnit.SECONDS) as work.art1st.javarailways.core.physics.train.CoreTrain
            assertSame(originalAto, train.controller)
            assertEquals(listOf(originalCars.first()), train.cars)
            assertSame(controls.first(), originalAto.dynamicsProfileFor(originalCars.first()))
            assertNull(originalAto.dynamicsProfileFor(originalCars[1]))
            assertNull(separated.controller)
            assertNotSame(train.getExtensionDataOrNull<work.art1st.javarailways.bukkit.extension.train.SimulationTrainExtension>(BasicPlugin.INSTANCE),
                separated.getExtensionDataOrNull<work.art1st.javarailways.bukkit.extension.train.SimulationTrainExtension>(BasicPlugin.INSTANCE))
            val newOrigin = "split-origin"
            world.onOwnerForTest {
                world.basicWorldData.wayPoints.add(WayPoint(newOrigin, "Split origin", rail.id,
                    separated.headLocation.arcLengthIn(ViewDirection.AB)))
                val detachedTimetable = Timetable("split-operation", services = listOf(TimetableService("split-duty",
                    TrainSelector.TrainId(separated.id), listOf(TimetableStep.Check(newOrigin),
                        TimetableStep.At(0, TimetableAction.GoTo("terminal"))))))
                world.simpleAtoWorldData.timetables.add(detachedTimetable)
                val started = world.simpleAtoWorldData.dispatcher.startService(world, detachedTimetable.id, "split-duty")
                assertTrue(started is work.art1st.javarailways.simpleato.timetable.TimetableServiceStartResult.Started, started.toString())
            }
            val newAto = separated.controller as work.art1st.javarailways.simpleato.controller.SimpleATO
            separated.cars.forEachIndexed { index, car ->
                assertSame(originalCars[index + 1], car)
                assertSame(originalExtensions[index + 1], car.getExtensionDataOrNull<work.art1st.javarailways.bukkit.operation.SimulationCarBehaviorExtension>(BasicPlugin.INSTANCE))
                assertSame(controls[index + 1], newAto.dynamicsProfileFor(car))
            }
        }
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
            listOf(SimulationCarBlueprint(10.0, 0.0, 0.0, 0.0, 3.5, -3.5, carControl, CapturedCarBehavior(null, emptyMap(), emptyMap())))
        )
    }

    private fun registerWorldData(world: CoreWorld) {
        val basicData = BasicWorldData()
        world.registerExtensionData(BasicPlugin.INSTANCE, basicData)
        val atoCoordinator = AtoWorldCoordinator(safetyInterlock = basicData.safetyInterlock)
        val worldId = atoCoordinator.worldId
        val restrictions = TrainRestrictionSnapshotStore(worldId)
        val inspection = InspectionRuntime(restrictions) { _, _, failure -> throw failure }
        val signalEpoch = SignalEpochRuntime(
            worldId,
            restrictions,
            restrictions::completeEpoch,
            inspection::markDirty
        )
        val dispatcher = TimetableDispatcher(
            TimetableRouteAdmissionContextProvider { null },
            restrictions,
            inspection,
            object : InfrastructureDependencyService {
                override fun set(request: InfrastructureDependencyRequest) =
                    InfrastructureDependencyMutationResult(
                        InfrastructureDependencyMutationStatus.APPLIED
                    )

                override fun remove(owner: InfrastructureOwner) =
                    InfrastructureDependencyMutationResult(
                        InfrastructureDependencyMutationStatus.REMOVED
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
