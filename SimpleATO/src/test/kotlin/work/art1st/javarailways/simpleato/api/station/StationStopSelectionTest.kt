package work.art1st.javarailways.simpleato.api.station

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
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

    private fun fixture(length: Double = 20.0, body: (StationBerthAllocator, (StationStopSelection, StationBerthOwner, Long) -> Map<String, StationCallResolutionResult>) -> Unit) {
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
                world.simpleAtoWorldData.stations.register(Station(stationId, "Central", berths.map { it.berthRef }), berths)
                val allocator = StationBerthAllocator()
                val resolver = StationCallResolver(world)
                body(allocator) { selection, owner, revision -> selection.candidates.associate { candidate ->
                    candidate.berthId to resolver.resolve(StationCallRequest(owner.serviceRunId, WayPointRef("start"),
                        StationCall(owner.callId, revision, stationId, StationBerthRef(stationId, candidate.berthId), dwellPlan = DwellPlan(0))),
                        trainLengthMeters = train.cars.sumOf { it.length }, requiredStartDirection = ViewDirection.AB)
                } }
            } finally { TestSimpleATOWorldDataRegistry.unregister(world) }
        }
    }

    @Test fun `actual physical length and route direction filter candidates before priority`() {
        for ((length, expected) in listOf(10.0 to "short", 30.0 to "long")) fixture(length) { allocator, resolve ->
            val request = selection(StationStopSelectionPolicy.FIXED_PREFERENCE, "wrong-direction", "short", "long")
            val resolutions = resolve(request, owner(), 1)
            assertEquals(StationCallResolutionFailure.BERTH_DIRECTION_UNAVAILABLE,
                (resolutions.getValue("wrong-direction") as StationCallResolutionResult.Failure).reason)
            val result = StationStopSelector(allocator).select(request, owner(), 1, resolutions) as StationStopSelectionResult.Selected
            assertEquals(expected, result.key.berthRef.berthId)
            assertEquals(1, allocator.allocations().size)
        }
    }

    @Test fun `fixed preference waits while available priority chooses one unoccupied berth`() = fixture { allocator, resolve ->
        val occupied = StationBerthAllocationKey(owner("other"), StationBerthRef(stationId, "p1"), 1)
        allocator.allocate(occupied)
        val fixed = selection(StationStopSelectionPolicy.FIXED_PREFERENCE, "p1", "p2")
        val selector = StationStopSelector(allocator)
        val waiting = selector.select(fixed, owner(), 1, resolve(fixed, owner(), 1)) as StationStopSelectionResult.Waiting
        assertEquals(occupied, waiting.conflicts.single().heldBy)
        assertEquals(listOf(occupied), allocator.allocations())
        val available = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        val selected = selector.select(available, owner(), 1, resolve(available, owner(), 1)) as StationStopSelectionResult.Selected
        assertEquals("p2", selected.key.berthRef.berthId)
        assertEquals(2, allocator.allocations().size)
    }

    @Test fun `all busy differs from no eligible candidates and neither creates reservations`() = fixture { allocator, resolve ->
        val busy = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        listOf("p1", "p2").forEach { allocator.allocate(StationBerthAllocationKey(owner(it), StationBerthRef(stationId, it), 1)) }
        val before = allocator.allocations()
        val waiting = StationStopSelector(allocator).select(busy, owner(), 1, resolve(busy, owner(), 1)) as StationStopSelectionResult.Waiting
        assertEquals(2, waiting.conflicts.size)
        val invalid = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "short", "wrong-direction")
        val rejected = StationStopSelector(allocator).select(invalid, owner(), 1, resolve(invalid, owner(), 1)) as StationStopSelectionResult.NoEligibleCandidate
        assertEquals(2, rejected.rejections.size)
        assertEquals(before, allocator.allocations())
    }

    @Test fun `retry keeps selected owner and stale revision cannot reserve another candidate`() = fixture { allocator, resolve ->
        val request = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        val selector = StationStopSelector(allocator)
        val selected = selector.select(request, owner(), 1, resolve(request, owner(), 1)) as StationStopSelectionResult.Selected
        val reordered = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p2", "p1")
        assertEquals(selected.key, (selector.select(reordered, owner(), 1, resolve(reordered, owner(), 1)) as StationStopSelectionResult.Selected).key)
        assertEquals(selected.key, (selector.select(request, owner(), 2, resolve(request, owner(), 2)) as StationStopSelectionResult.ExistingSelectionMismatch).held)
        assertFalse(allocator.release(selected.key.copy(missionRevision = 2)))
        assertEquals(listOf(selected.key), allocator.allocations())
    }

    @Test fun `candidate snapshot is immutable and equal priorities use stable identity`() = fixture { allocator, resolve ->
        val input = arrayListOf(StationStopCandidate("p2", 0), StationStopCandidate("p1", 0))
        val request = StationStopSelection(stationId, input, StationStopSelectionPolicy.AVAILABLE_PRIORITY)
        input.clear()
        assertEquals(2, request.candidates.size)
        assertThrows(UnsupportedOperationException::class.java) { (request.candidates as MutableList).clear() }
        val result = StationStopSelector(allocator).select(request, owner(), 1, resolve(request, owner(), 1)) as StationStopSelectionResult.Selected
        assertEquals("p1", result.key.berthRef.berthId)
    }

    @Test fun `wrong call resolution is rejected before any logical allocation`() = fixture { allocator, resolve ->
        val request = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        assertThrows(IllegalArgumentException::class.java) {
            StationStopSelector(allocator).select(request, owner(), 1, resolve(request, owner("other"), 1))
        }
        assertTrue(allocator.allocations().isEmpty())
    }

    @Test fun `simultaneous retries cannot allocate two alternatives to one call owner`() = fixture { allocator, resolve ->
        val first = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p1", "p2")
        val second = selection(StationStopSelectionPolicy.AVAILABLE_PRIORITY, "p2", "p1")
        val input = listOf(first to resolve(first, owner(), 1), second to resolve(second, owner(), 1))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val futures = input.map { (request, resolutions) -> executor.submit<StationStopSelectionResult> {
                check(start.await(5, TimeUnit.SECONDS))
                StationStopSelector(allocator).select(request, owner(), 1, resolutions)
            } }
            start.countDown()
            val results = futures.map { it.get(5, TimeUnit.SECONDS) as StationStopSelectionResult.Selected }
            assertEquals(results.first().key, results.last().key)
            assertEquals(1, allocator.allocations().size)
        } finally { executor.shutdownNow() }
    }
}
