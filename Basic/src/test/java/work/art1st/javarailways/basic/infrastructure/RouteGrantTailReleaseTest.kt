package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureFootprint
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureMutationStatus
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.train.SweptRailSpanSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import java.util.UUID

class RouteGrantTailReleaseTest {
    private val railId = UUID.randomUUID()
    private val worldId = "world"
    private val topologyRevision = 4L
    private val trainId = "train"
    private val platformOwner = Any()
    private val claimOwner = InfrastructureOwner("test:route", 1L)

    @Test
    fun `resource completion keeps projecting the live grant cursor on later epochs`() {
        val first = RailIntervalResource(railId, 0.0, 30.0)
        val second = RailIntervalResource(railId, 20.0, 60.0)
        val fixture = fixture(
            listOf(
                ResolvedTailReleaseEntry(first, 10.0),
                ResolvedTailReleaseEntry(second, 20.0)
            )
        )

        val shrunk = assertInstanceOf(
            RouteGrantTailReleaseUpdate.Active::class.java,
            consume(fixture.tailRelease, observation(epoch = 8L, tailMeters = 10.0))
        )
        val repeated = consume(fixture.tailRelease, observation(epoch = 8L, tailMeters = 100.0))

        assertInstanceOf(LiveClaimTailReleaseResult.Shrunk::class.java, shrunk.releaseResult)
        assertEquals(listOf(second), fixture.registry.claimSnapshots().single().footprint.resources)
        assertEquals(
            RouteGrantTailReleaseUpdate.Unchanged(8L, RouteProgressCursorState(0, 10.0)),
            repeated
        )

        val completed = consume(fixture.tailRelease, observation(epoch = 9L, tailMeters = 20.0))
        val afterCompletion = consume(fixture.tailRelease, observation(epoch = 10L, tailMeters = 30.0))

        assertEquals(RouteGrantTailReleaseUpdate.Completed(9L, alreadyCompleted = false), completed)
        assertEquals(RouteGrantTailReleaseUpdate.Completed(10L, alreadyCompleted = true), afterCompletion)
        assertEquals(RouteProgressCursorState(0, 30.0), fixture.cursor.state)
        assertTrue(fixture.tailRelease.resourceQueueEmpty)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
        assertEquals(0, fixture.stalls.size)
    }

    @Test
    fun `installing a committed suffix retains the same live route cursor`() {
        val first = RailIntervalResource(railId, 0.0, 10.0)
        val suffixResource = RailIntervalResource(railId, 20.0, 30.0)
        val fixture = fixture(listOf(ResolvedTailReleaseEntry(first, 10.0)))

        assertInstanceOf(
            RouteGrantTailReleaseUpdate.Completed::class.java,
            consume(fixture.tailRelease, observation(epoch = 8L, tailMeters = 10.0))
        )
        consume(fixture.tailRelease, observation(epoch = 9L, tailMeters = 15.0))
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            fixture.registry.acquireClaim(
                platformOwner,
                InfrastructureClaimRequest(
                    topologyRevision,
                    claimOwner,
                    InfrastructureFootprint(listOf(suffixResource))
                )
            ).status
        )

        fixture.tailRelease.installCommittedSuffix(
            listOf(ResolvedTailReleaseEntry(suffixResource, 20.0))
        )
        val completed = consume(
            fixture.tailRelease,
            observation(epoch = 10L, tailMeters = 20.0)
        )

        assertEquals(RouteGrantTailReleaseUpdate.Completed(10L, false), completed)
        assertEquals(RouteProgressCursorState(0, 20.0), fixture.cursor.state)
        assertTrue(fixture.registry.claimSnapshots().isEmpty())
        assertEquals(0, fixture.stalls.size)
    }

    @Test
    fun `observation identity epoch and train failures latch and stall exactly once`() {
        val scenarios = listOf(
            Triple(
                RouteGrantTailReleaseFailureKind.WRONG_WORLD,
                observation(epoch = 8L, tailMeters = 1.0, world = "other"),
                "wrong world"
            ),
            Triple(
                RouteGrantTailReleaseFailureKind.STALE_TOPOLOGY,
                observation(epoch = 8L, tailMeters = 1.0, topology = topologyRevision + 1L),
                "wrong topology"
            ),
            Triple(
                RouteGrantTailReleaseFailureKind.DECREASING_PHYSICS_EPOCH,
                observation(epoch = 6L, tailMeters = 1.0),
                "decreasing epoch"
            ),
            Triple(
                RouteGrantTailReleaseFailureKind.TRAIN_MISSING,
                observation(epoch = 8L, tailMeters = 1.0, id = "other-train"),
                "missing train"
            )
        )

        scenarios.forEach { (expectedKind, invalidObservation, description) ->
            val fixture = fixture(singleEntry())
            val failed = assertInstanceOf(
                RouteGrantTailReleaseUpdate.FailedClosed::class.java,
                consume(fixture.tailRelease, invalidObservation),
                description
            )
            val repeated = consume(fixture.tailRelease, observation(epoch = 9L, tailMeters = 20.0))

            assertEquals(expectedKind, failed.kind, description)
            assertEquals(failed, repeated, description)
            assertEquals(1, fixture.stalls.size, description)
            assertEquals(1, fixture.registry.claimSnapshots().size, description)
        }
    }

    @Test
    fun `route cursor failure latches and stalls exactly once`() {
        val fixture = fixture(singleEntry())

        val failed = assertInstanceOf(
            RouteGrantTailReleaseUpdate.FailedClosed::class.java,
            consume(fixture.tailRelease, observation(epoch = 8L, tailMeters = 101.0))
        )

        assertEquals(RouteGrantTailReleaseFailureKind.ROUTE_CURSOR_FAILED, failed.kind)
        assertEquals(failed, consume(fixture.tailRelease, observation(epoch = 9L, tailMeters = 20.0)))
        assertEquals(1, fixture.stalls.size)
        assertEquals(1, fixture.registry.claimSnapshots().size)
    }

    @Test
    fun `tail release failure latches and stalls exactly once`() {
        val fixture = fixture(singleEntry(), releasePlatformOwner = Any())

        val failed = assertInstanceOf(
            RouteGrantTailReleaseUpdate.FailedClosed::class.java,
            consume(fixture.tailRelease, observation(epoch = 8L, tailMeters = 10.0))
        )

        assertEquals(RouteGrantTailReleaseFailureKind.TAIL_RELEASE_FAILED, failed.kind)
        assertEquals(failed, consume(fixture.tailRelease, observation(epoch = 9L, tailMeters = 20.0)))
        assertEquals(1, fixture.stalls.size)
        assertEquals(1, fixture.registry.claimSnapshots().size)
        assertEquals(RouteProgressCursorState(0, 0.0), fixture.cursor.state)
    }

    private fun fixture(
        entries: List<ResolvedTailReleaseEntry>,
        releasePlatformOwner: Any = platformOwner
    ): Fixture {
        val registry = InfrastructureCoordinationRegistry(
            currentTopologyRevision = { topologyRevision },
            resourceExists = { true }
        )
        assertEquals(
            InfrastructureMutationStatus.ACQUIRED,
            registry.acquireClaim(
                platformOwner,
                InfrastructureClaimRequest(
                    topologyRevision,
                    claimOwner,
                    InfrastructureFootprint(entries.map(ResolvedTailReleaseEntry::resource))
                )
            ).status
        )
        val initialized = assertInstanceOf(
            RouteProgressCursorInitialization.Initialized::class.java,
            testRouteProgressPath(railId, 100.0, 0.0).initializeCursor(position(0.0))
        )
        val stalls = mutableListOf<String>()
        return Fixture(
            registry,
            stalls,
            initialized.cursor,
            RouteGrantTailRelease(
                worldId,
                topologyRevision,
                trainId,
                initialPhysicsEpoch = 7L,
                routeProgressCursor = initialized.cursor,
                claimRelease = LiveClaimTailRelease(
                    registry,
                    releasePlatformOwner,
                    claimOwner,
                    CompiledTailReleaseQueue(entries)
                ),
                stallWorld = { detail, _ -> stalls += detail }
            )
        )
    }

    private fun singleEntry() = listOf(
        ResolvedTailReleaseEntry(RailIntervalResource(railId, 0.0, 30.0), 10.0)
    )

    private fun observation(
        epoch: Long,
        tailMeters: Double,
        world: String = worldId,
        topology: Long = topologyRevision,
        id: String = trainId
    ): RailwayObservation = RailwayObservation.fromCore(
        world,
        RailwayWorldFrameSnapshot.create(
            epoch,
            topology,
            listOf(train(id, epoch, tailMeters)),
            ActiveRailContinuationsSnapshot.empty(topology)
        )
    )

    private fun consume(
        tailRelease: RouteGrantTailRelease,
        observation: RailwayObservation
    ): RouteGrantTailReleaseUpdate = tailRelease.consume(
        observation.stamp,
        observation.trains.firstOrNull { it.trainId == trainId }
    )

    private fun train(id: String, epoch: Long, tailMeters: Double) = TrainFrameSnapshot.create(
        trainId = id,
        trainName = id,
        revision = epoch,
        physicsEpoch = epoch,
        speedMetersPerSecond = 0.0,
        derailed = false,
        reversed = false,
        cars = emptyList(),
        head = position(tailMeters + 1.0),
        tail = position(tailMeters),
        occupiedIntervals = listOf(RailInterval(railId, tailMeters, tailMeters + 1.0)),
        bodyTraversal = emptyList(),
        sweptSpans = listOf(SweptRailSpanSnapshot(railId, tailMeters, tailMeters + 1.0)),
        headTraversal = emptyList(),
        tailTraversal = emptyList()
    )

    private fun position(meters: Double) = RailPositionSnapshot(railId, ViewDirection.AB, meters)

    private data class Fixture(
        val registry: InfrastructureCoordinationRegistry,
        val stalls: MutableList<String>,
        val cursor: RouteProgressCursor,
        val tailRelease: RouteGrantTailRelease
    )
}
