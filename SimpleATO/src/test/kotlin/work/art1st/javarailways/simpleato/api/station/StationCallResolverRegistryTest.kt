package work.art1st.javarailways.simpleato.api.station

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import work.art1st.javarailways.simpleato.timetable.*

class StationCallResolverRegistryTest {
    private val fixtures = mutableListOf<TestWorldFixture>()

    @AfterEach
    fun closeWorlds() {
        fixtures.forEach {
            TestSimpleATOWorldDataRegistry.unregister(it.world)
            it.close()
        }
    }

    @Test
    fun `real resolver supplies actual length detached berth and host validated defaults`() {
        val (world, request) = fixture()
        var observed: StationCallResolutionContext? = null
        world.simpleAtoWorldData.stationCallResolvers.register { context ->
            observed = context
            StationCallResolutionResult.Success(context.defaultResolution.copy(doorSide = DoorSide.RIGHT, usableLengthMeters = 50.0))
        }
        val result = StationCallResolver(world).resolve(request, 12.0) as StationCallResolutionResult.Success
        assertEquals(12.0, observed!!.trainLengthMeters)
        assertEquals(request.call.berthRef, observed!!.berth.berthRef)
        assertSame(world.topologyState, observed!!.topology)
        assertEquals(DoorSide.RIGHT, result.resolved.doorSide)
        assertEquals(0.3, result.resolved.positionToleranceMeters)
        assertEquals(50.0, result.resolved.usableLengthMeters)
        StationCallResolver(world).resolve(request)
        assertNull(observed!!.trainLengthMeters)
    }

    @Test
    fun `host safety rejects long trains before invoking policy and custom refusal is retained`() {
        val (world, request) = fixture()
        var calls = 0
        val rejection = StationCallResolutionResult.Failure(StationCallResolutionFailure.POLICY_REJECTED, "external length rule")
        world.simpleAtoWorldData.stationCallResolvers.register {
            calls++
            rejection
        }
        assertEquals(StationCallResolutionFailure.TRAIN_TOO_LONG,
            (StationCallResolver(world).resolve(request, 101.0) as StationCallResolutionResult.Failure).reason)
        assertEquals(0, calls)
        assertSame(rejection, StationCallResolver(world).resolve(request, 12.0))
    }

    @Test
    fun `exception unavailable duplicate stale handle and reentrant mutation fail closed`() {
        val (world, request) = fixture()
        val registry = world.simpleAtoWorldData.stationCallResolvers
        val first = registry.register { error("broken plugin") }
        assertThrows(IllegalStateException::class.java) { registry.register { success(it) } }
        assertEquals(StationCallResolutionFailure.RESOLVER_ERROR, failure(world, request))
        first.close()
        assertEquals(StationCallResolutionFailure.RESOLVER_UNAVAILABLE, failure(world, request))
        assertThrows(IllegalStateException::class.java) { registry.prepareSimulation() }
        lateinit var second: AutoCloseable
        second = registry.register { second.close(); success(it) }
        first.close()
        assertEquals(StationCallResolutionFailure.RESOLVER_ERROR, failure(world, request))
        second.close()
        registry.register { success(it) }
        second.close()
        assertTrue(StationCallResolver(world).resolve(request) is StationCallResolutionResult.Success)
    }

    @Test
    fun `custom result cannot alter route request geometry or weaken length safety`() {
        val (world, request) = fixture()
        val invalidChanges: List<(ResolvedStationCall) -> ResolvedStationCall> = listOf(
            { it.copy(request = it.request.copy(serviceRunId = "different")) },
            { it.copy(plannedRoute = it.plannedRoute.copy(routeId = "different")) },
            { it.copy(stoppingOffsetMetersBefore = 2.0) },
            { it.copy(usableLengthMeters = null) },
            { it.copy(usableLengthMeters = 101.0) }
        )
        invalidChanges.forEach { change ->
            world.simpleAtoWorldData.stationCallResolvers.register {
                StationCallResolutionResult.Success(change(it.defaultResolution))
            }.use { assertEquals(StationCallResolutionFailure.RESOLVER_INVALID_RESULT, failure(world, request)) }
        }
        world.simpleAtoWorldData.stationCallResolvers.register {
            StationCallResolutionResult.Success(it.defaultResolution.copy(usableLengthMeters = 5.0))
        }
        assertEquals(StationCallResolutionFailure.TRAIN_TOO_LONG, failure(world, request))
    }

    @Test
    fun `capture requires factory and target providers have isolated runtime state`() {
        val (source, request) = fixture()
        val registry = source.simpleAtoWorldData.stationCallResolvers
        registry.register { success(it) }.use {
            assertThrows(IllegalArgumentException::class.java) { registry.prepareSimulation() }
        }
        var sourceCalls = 0
        val provider = object : StationCallResolutionProvider, StationCallResolutionSimulationFactory {
            override fun resolve(context: StationCallResolutionContext): StationCallResolutionResult {
                sourceCalls++
                return success(context)
            }
            override fun prepareSimulation(): StationCallResolutionSimulationPreparation =
                StationCallResolutionSimulationPreparation {
                    var targetCalls = 0
                    StationCallResolutionProvider { context ->
                        targetCalls++
                        if (targetCalls == 1) success(context) else
                            StationCallResolutionResult.Failure(StationCallResolutionFailure.POLICY_REJECTED, "target count")
                    }
                }
        }
        registry.register(provider)
        val prepared = registry.prepareSimulation()
        val (a, _) = fixture()
        val (b, _) = fixture()
        val defaults = (StationCallResolver(source).resolve(request, 12.0) as StationCallResolutionResult.Success).resolved
        val context = StationCallResolutionContext(defaults,
            StationCallBerthSnapshot(request.call.berthRef, defaults.plannedRoute.expandedRoute.segments.last().railViewRef.railId,
                0.0, ViewDirection.AB), 12.0, ViewDirection.AB, source.topologyState)
        val first = prepared.create(a)
        val second = prepared.create(b)
        assertTrue(first.resolve(context) is StationCallResolutionResult.Success)
        assertTrue(first.resolve(context) is StationCallResolutionResult.Failure)
        assertTrue(second.resolve(context) is StationCallResolutionResult.Success)
        assertEquals(1, sourceCalls)
    }

    @Test
    fun `ordinary timetable preflight observes external rejection with unknown length`() {
        val (world, request) = fixture()
        val berth = requireNotNull(world.simpleAtoWorldData.stations.berth(request.call.berthRef))
        world.basicWorldData.wayPoints.add(WayPoint("target", berth.location.rail.id,
            berth.location.arcLengthIn(ViewDirection.AB)))
        val timetable = Timetable("test", services = listOf(TimetableService("service",
            TrainSelector.TrainId(world.allTrains.first().id), listOf(
                TimetableStep.Check("origin"),
                TimetableStep.At(0L, TimetableAction.CallStation("station", "berth")),
                TimetableStep.At(0L, TimetableAction.GoTo("target"))
            ))))
        var calls = 0
        world.simpleAtoWorldData.stationCallResolvers.register {
            calls++
            assertNull(it.trainLengthMeters)
            StationCallResolutionResult.Failure(StationCallResolutionFailure.POLICY_REJECTED, "external-preflight-rejection")
        }
        val validation = TimetableValidator(world).validate(timetable)
        assertFalse(validation.isSuccess)
        assertTrue(calls > 0)
        assertTrue(validation.errors.any { "external-preflight-rejection" in it }, validation.errors.toString())
    }

    @Test
    fun `selector chooses accepted candidate using plugin rules and real train length`() {
        val (world, request) = fixture()
        val store = world.simpleAtoWorldData.stations
        val original = requireNotNull(store.berth(request.call.berthRef))
        store.remove(request.call.stationId)
        val otherRef = StationBerthRef(request.call.stationId, "long")
        val short = StationBerth(request.call.berthRef, original.location, approachDirection = ViewDirection.AB)
        val long = StationBerth(otherRef, (original.location as work.art1st.javarailways.core.physics.rail.RailLocationWithFacing).forward(5.0)!!, approachDirection = ViewDirection.AB)
        store.register(Station(request.call.stationId, listOf(short.berthRef, long.berthRef)), listOf(short, long))
        val length = world.allTrains.first().cars.sumOf { it.length }
        world.simpleAtoWorldData.stationCallResolvers.register {
            assertEquals(length, it.trainLengthMeters)
            if (it.request.call.berthRef == otherRef && requireNotNull(it.trainLengthMeters) < 20.0)
                StationCallResolutionResult.Failure(StationCallResolutionFailure.POLICY_REJECTED, "long trains only")
            else success(it)
        }
        val selection = StationStopSelection(request.call.stationId,
            listOf(StationStopCandidate("long", 0), StationStopCandidate("berth", 1)), StationStopSelectionPolicy.FIXED_PREFERENCE)
        val results = selection.candidates.associate { candidate ->
            candidate.berthId to StationCallResolver(world).resolve(request.copy(call = request.call.copy(
                berthRef = StationBerthRef(request.call.stationId, candidate.berthId))), length)
        }
        val selected = StationStopSelector(StationCallRegistry()).select(selection,
            StationBerthOwner(request.serviceRunId, world.allTrains.first().id, request.call.callId),
            request.call.missionRevision, results) as StationStopSelectionResult.Selected
        assertEquals(short.berthRef, selected.key.berthRef)
    }

    private fun success(context: StationCallResolutionContext) = StationCallResolutionResult.Success(context.defaultResolution)
    private fun failure(world: World, request: StationCallRequest) =
        (StationCallResolver(world).resolve(request, 12.0) as StationCallResolutionResult.Failure).reason

    private fun fixture(): Pair<World, StationCallRequest> {
        val world = TestWorldFixture.create().also(fixtures::add).world
        val basic = BasicWorldData()
        TestSimpleATOWorldDataRegistry.register(world, basic)
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(120.0, segmentCount = 1))
        val train = createTrainWithConfig("fixture", rail, world, listOf(10.0))
        basic.wayPoints.add(WayPoint("origin", rail.id, train.head.location.arcLength))
        val ref = StationBerthRef(StationId("station"), "berth")
        val berth = StationBerth(ref, train.head.location.forward(35.0)!!, approachDirection = ViewDirection.AB,
            stoppingOffsetMetersBefore = 1.0, usableLengthMeters = 100.0)
        world.simpleAtoWorldData.stations.register(Station(ref.stationId, listOf(ref)), listOf(berth))
        return world to StationCallRequest("run", WayPointRef("origin"),
            StationCall("call", 1L, ref.stationId, ref, dwellPlan = DwellPlan(0)))
    }
}
