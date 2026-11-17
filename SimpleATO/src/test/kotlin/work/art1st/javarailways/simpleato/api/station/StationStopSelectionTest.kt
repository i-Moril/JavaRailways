package work.art1st.javarailways.simpleato.api.station

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.mission.WayPointRef
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StationStopSelectionTest {
    private val stationId = StationId("central")
    private fun owner(id: String = "run") = StationBerthOwner(id, "train-$id", "call-$id")
    private fun selection(policy: StationStopSelectionPolicy, vararg ids: String) =
        StationStopSelection(stationId, ids.mapIndexed { index, id -> StationStopCandidate(id, index) }, policy)

    private fun fixture(length: Double = 20.0, body: (StationCallRegistry, (StationStopSelection, StationBerthOwner, Long) -> Map<String, StationCallResolutionResult>) -> Unit) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val data = BasicWorldData()
            TestSimpleATOWorldDataRegistry.register(world, data)
            try {
                val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0, 1))
                val train = createTrainWithConfig("physical", rail, world, listOf(length))
                data.wayPoints.add(WayPoint("start", rail.id, train.head.location.arcLength))
                fun berth(id: String, usableLength: Double = 100.0, direction: ViewDirection = ViewDirection.AB) = StationBerth(
                    StationBerthRef(stationId, id), train.head.location.forward(if (id == "p2") 100.0 else 60.0)!!,
                    approachDirection = direction, usableLengthMeters = usableLength)
                val berths = listOf(berth("p1"), berth("p2"),
                    berth("short", usableLength = 10.0), berth("long"), berth("wrong-direction", direction = ViewDirection.BA))
                world.simpleAtoWorldData.stations.register(Station(stationId, berths.map { it.berthRef }), berths)
                val registry = StationCallRegistry()
                val resolver = StationCallResolver(world)
                body(registry) { selection, owner, revision -> selection.candidates.associate { candidate ->
                    candidate.berthId to resolver.resolve(StationCallRequest(owner.serviceRunId, WayPointRef("start"),
                        StationCall(owner.callId, revision, stationId, StationBerthRef(stationId, candidate.berthId), dwellPlan = DwellPlan(0))),
                        trainLengthMeters = train.cars.sumOf { it.length }, requiredStartDirection = ViewDirection.AB)
                } }
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }

    @Test fun `actual physical length and route direction filter candidates before priority`() {
        for ((length, expected) in listOf(10.0 to "short", 30.0 to "long")) fixture(length) { registry, resolve ->
            val request = selection(StationStopSelectionPolicy.FIXED_PREFERENCE, "wrong-direction", "short", "long")
            val resolutions = resolve(request, owner(), 1)
            assertEquals(StationCallResolutionFailure.BERTH_DIRECTION_UNAVAILABLE,
                (resolutions.getValue("wrong-direction") as StationCallResolutionResult.Failure).reason)
            val result = StationStopSelector(registry).select(request, owner(), 1, resolutions) as StationStopSelectionResult.Selected
            assertEquals(expected, result.key.berthRef.berthId)
            assertEquals(1, registry.registrations().size)
        }
    }

    @Test fun `other calls do not veto a preferred berth before physical admission`() = fixture { registry, resolve ->
        val other = StationBerthRegistrationKey(owner("other"), StationBerthRef(stationId, "p1"), 1)
        registry.register(other)
        for (policy in StationStopSelectionPolicy.entries) {
            val request = selection(policy, "p1", "p2")
            val callOwner = owner(policy.name)
            val selected = StationStopSelector(registry).select(request, callOwner, 1,
                resolve(request, callOwner, 1)) as StationStopSelectionResult.Selected
            assertEquals("p1", selected.key.berthRef.berthId)
        }
        assertEquals(3, registry.registrations().size)
    }

    @Test fun `no eligible candidates creates no registration and preserves other calls`() = fixture { registry, resolve ->
        listOf("p1", "p2").forEach { registry.register(StationBerthRegistrationKey(owner(it), StationBerthRef(stationId, it), 1)) }
        val before = registry.registrations()
        val invalid = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "short", "wrong-direction")
        val rejected = StationStopSelector(registry).select(invalid, owner(), 1, resolve(invalid, owner(), 1)) as StationStopSelectionResult.NoEligibleCandidate
        assertEquals(2, rejected.rejections.size)
        assertEquals(before, registry.registrations())
    }

    @Test fun `retry keeps selected owner and stale revision cannot reserve another candidate`() = fixture { registry, resolve ->
        val request = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        val selector = StationStopSelector(registry)
        val selected = selector.select(request, owner(), 1, resolve(request, owner(), 1)) as StationStopSelectionResult.Selected
        val reordered = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p2", "p1")
        assertEquals(selected.key, (selector.select(reordered, owner(), 1, resolve(reordered, owner(), 1)) as StationStopSelectionResult.Selected).key)
        assertEquals(selected.key, (selector.select(request, owner(), 2, resolve(request, owner(), 2)) as StationStopSelectionResult.ExistingSelectionMismatch).held)
        assertFalse(registry.release(selected.key.copy(missionRevision = 2)))
        assertEquals(listOf(selected.key), registry.registrations())
    }

    @Test fun `candidate snapshot is immutable and equal priorities use stable identity`() = fixture { registry, resolve ->
        val input = arrayListOf(StationStopCandidate("p2", 0), StationStopCandidate("p1", 0))
        val request = StationStopSelection(stationId, input, StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        input.clear()
        assertEquals(2, request.candidates.size)
        assertThrows(UnsupportedOperationException::class.java) { (request.candidates as MutableList).clear() }
        val result = StationStopSelector(registry).select(request, owner(), 1, resolve(request, owner(), 1)) as StationStopSelectionResult.Selected
        assertEquals("p1", result.key.berthRef.berthId)
    }

    @Test fun `wrong call resolution is rejected before any logical allocation`() = fixture { registry, resolve ->
        val request = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        assertThrows(IllegalArgumentException::class.java) {
            StationStopSelector(registry).select(request, owner(), 1, resolve(request, owner("other"), 1))
        }
        assertTrue(registry.registrations().isEmpty())
    }

    @Test fun `simultaneous retries cannot allocate two alternatives to one call owner`() = fixture { registry, resolve ->
        val first = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        val second = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p2", "p1")
        val input = listOf(first to resolve(first, owner(), 1), second to resolve(second, owner(), 1))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = input.map { (request, resolutions) -> executor.submit<StationStopSelectionResult> {
                check(start.await(5, TimeUnit.SECONDS))
                StationStopSelector(registry).select(request, owner(), 1, resolutions)
            } }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) as StationStopSelectionResult.Selected }
            assertEquals(results.first().key, results.last().key)
            assertEquals(1, registry.registrations().size)
        } finally { executor.shutdownNow() }
    }
}
