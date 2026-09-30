package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPublicationStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import java.util.UUID

class ProtectionRouteSelectionRuntimeTest {
    private val worldId = UUID(0, 900)
    private val trunk = UUID(0, 901)
    private val first = UUID(0, 902)
    private val second = UUID(0, 903)
    private val topology = topologyState(7L, listOf(trunk, first, second), listOf(
        endpoint(trunk, RailEndpoint.B) to endpoint(first, RailEndpoint.A),
        endpoint(trunk, RailEndpoint.B) to endpoint(second, RailEndpoint.A)
    ))
    private val region = ProtectionRegionDefinition("test:region", 1L, listOf(
        boundary("test:entry", trunk, 5.0, ViewDirection.AB),
        boundary("test:first-exit", first, 5.0, ViewDirection.BA),
        boundary("test:second-exit", second, 5.0, ViewDirection.BA)
    ))
    private val defaultRoute = fixedRoute("test:first-route", first, "test:first-exit")
    private val requestedRoute = fixedRoute("test:second-route", second, "test:second-exit")
    private val owner = InfrastructureOwner("test:owner", 1L)

    @Test
    fun `request only changes override default and ended request restores default`() {
        val (world, epoch) = runtime()
        epoch.process(frame(1, first))
        assertEquals(ProtectionRouteSelectionSource.DEFAULT, selection(world).source)
        val frozen = route(second)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 15.0)))
        epoch.process(frame(2, first))
        val preparing = selection(world)
        assertEquals("test:second-route", preparing.routeId)
        assertEquals(ActiveRouteAdmissionState.PREPARING, preparing.requestState)
        assertFalse(preparing.routeReady)

        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0)))
        epoch.process(frame(3, second))
        assertTrue(selection(world).routeReady)
        val unchanged = world.protectionRouteSelectionSnapshot()
        epoch.process(frame(4, second))
        assertSame(unchanged, world.protectionRouteSelectionSnapshot())
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        epoch.process(frame(5, first))
        assertEquals("test:first-route", selection(world).routeId)
        assertEquals(ProtectionRouteSelectionSource.DEFAULT, selection(world).source)
    }

    @Test
    fun `replacement baseline clears an ended request from the same selector handler`() {
        var sources = emptyList<SignalEpochSource>()
        val (world, epoch) = runtime(captureSources = { sources = it })
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route(second), 20.0, 20.0)))
        epoch.process(frame(1, second))
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(world).source)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        epoch.replaceSources(sources)
        epoch.process(frame(2, first))
        assertEquals(ProtectionRouteSelectionSource.DEFAULT, selection(world).source)
    }

    @Test
    fun `request moved to unrelated rail restores default and does not retain entrance membership`() {
        val (world, epoch) = runtime()
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route(second), 20.0, 20.0)))
        epoch.process(frame(1, second))
        val unrelated = RouteSnapshot("test:unrelated", 2L, topology.revision,
            listOf(RouteSegmentSnapshot(UUID(0, 999), ViewDirection.AB, 0.0, 10.0)))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(unrelated, 10.0, 10.0)))
        epoch.process(frame(2, first))
        assertEquals(ProtectionRouteSelectionSource.DEFAULT, selection(world).source)
        val unchanged = world.protectionRouteSelectionSnapshot()
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        epoch.process(frame(3, first))
        assertSame(unchanged, world.protectionRouteSelectionSnapshot())
    }

    @Test
    fun `old granted floor remains ready while distant suffix is preparing`() {
        val (world, epoch) = runtime()
        val frozen = route(second)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 15.0)))
        epoch.process(frame(1, second))
        assertEquals(ActiveRouteAdmissionState.GRANTED, selection(world).requestState)
        assertTrue(selection(world).routeReady)
    }

    @Test
    fun `adjacent whole fixed block must be included before requested route is ready`() {
        val block = FixedBlockDefinition("test:next-block", 1L,
            listOf(FixedBlockTraversal(second, ViewDirection.AB, 5.0, 10.0)))
        val (world, epoch) = runtime(listOf(block))
        val frozen = route(second)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 15.0, 15.0)))
        epoch.process(frame(1, second))
        assertFalse(selection(world).routeReady)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0)))
        epoch.process(frame(2, second))
        assertTrue(selection(world).routeReady)
    }

    @Test
    fun `unrequested future entry stays default and released past entry stops selecting request`() {
        val (world, epoch) = runtime()
        val frozen = route(second)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 5.0)))
        epoch.process(frame(1, first))
        assertEquals(ProtectionRouteSelectionSource.DEFAULT, selection(world).source)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0)))
        epoch.process(frame(2, second))
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(world).source)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0, 15.0)))
        epoch.process(frame(3, first))
        assertEquals(ProtectionRouteSelectionSource.DEFAULT, selection(world).source)
    }

    @Test
    fun `conflicting requests and missing train never silently choose default`() {
        val (world, epoch) = runtime()
        val frozen = route(second)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0)))
        val otherOwner = InfrastructureOwner("test:other-owner", 1)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(otherOwner,
            admission(frozen, 20.0, 20.0).copy(owner = otherOwner)))
        epoch.process(frame(1, first))
        assertFalse(selection(world).available)
        assertNull(selection(world).routeId)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(otherOwner, null))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner,
            admission(frozen, 20.0, 20.0).copy(trainId = "missing")))
        epoch.process(frame(2, first))
        assertFalse(selection(world).available)
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(world).source)
    }

    @Test
    fun `reverse request matches BA traversal and reversal clears old entrance membership`() {
        val reverse = ProtectionRouteDefinition("test:reverse", "test:second-exit", "test:entry", listOf(
            ProtectionRouteTraversal(second, ViewDirection.BA, 0.0, 5.0),
            ProtectionRouteTraversal(trunk, ViewDirection.BA, 5.0, 10.0)
        ))
        val (world, epoch) = runtime(extraRoutes = listOf(reverse))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route(second), 20.0, 20.0)))
        epoch.process(frame(1, second))
        val backward = RouteSnapshot("test:reverse-mission", 2, topology.revision, listOf(
            RouteSegmentSnapshot(second, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(trunk, ViewDirection.BA, 0.0, 10.0)
        ))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(backward, 20.0, 20.0)))
        epoch.process(frame(2, second, reverse = true))
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
        val selected = requireNotNull(world.protectionRouteSelectionSnapshot()).selections.getValue("test:second-exit")
        assertEquals("test:reverse", selected.routeId)
        assertTrue(selected.routeReady)
    }

    @Test
    fun `unknown frozen path and stale topology are restrictive`() {
        val (world, epoch) = runtime()
        val partial = RouteSnapshot("test:partial", 1, topology.revision,
            listOf(RouteSegmentSnapshot(trunk, ViewDirection.AB, 0.0, 8.0)))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(partial, 8.0, 8.0)))
        epoch.process(frame(1, first))
        assertFalse(selection(world).available)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        epoch.process(frame(2, first))
        assertTrue(selection(world).routeReady)
        epoch.process(frame(3, first, topologyRevision = 8))
        assertFalse(selection(world).available)
        assertFalse(selection(world).routeReady)
    }

    @Test
    fun `physics epoch marks vanished requesting train unavailable without simulation consuming another frame`() {
        val (world, epoch) = runtime()
        val snapshot = admission(route(second), 20.0, 20.0)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, snapshot))
        epoch.process(frame(1, second))
        assertTrue(selection(world).routeReady)
        val noTrain = RailwayWorldFrameSnapshot.create(2L, topology.revision, emptyList(),
            ActiveRailContinuationsSnapshot.create(topology.revision, 1L, mapOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
                endpoint(second, RailEndpoint.A) to endpoint(trunk, RailEndpoint.B)
            )))
        epoch.process(noTrain)
        assertFalse(selection(world).available)
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(world).source)
        // Reappearance refreshes the observation; the host never cancels or releases this request.
        epoch.process(frame(3, second))
        assertTrue(selection(world).routeReady)
    }

    private fun runtime(blocks: List<FixedBlockDefinition> = emptyList(), extraRoutes: List<ProtectionRouteDefinition> = emptyList(),
        captureSources: (List<SignalEpochSource>) -> Unit = {}):
        Pair<SimpleSignalsWorldRuntime, SignalEpochRuntime> {
        val world = SimpleSignalsWorldRuntime(worldId)
        val result = world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 1L, listOf(region),
            listOf(ProtectionRouteTableDefinition("test:table", 1L, region.regionId, 1L,
                listOf(defaultRoute, requestedRoute) + extraRoutes, listOf(defaultRoute.routeId))), blocks))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.toString())
        val occupancy = world.initializeSectionOccupancy()
        val epoch = SignalEpochRuntime(worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        })
        epoch.replaceSources(occupancy.sources)
        captureSources(occupancy.sources)
        return world to epoch
    }

    private fun fixedRoute(id: String, exitRail: UUID, exit: String) = ProtectionRouteDefinition(id, "test:entry", exit, listOf(
        ProtectionRouteTraversal(trunk, ViewDirection.AB, 5.0, 10.0),
        ProtectionRouteTraversal(exitRail, ViewDirection.AB, 0.0, 5.0)
    ))
    private fun route(exitRail: UUID) = RouteSnapshot("test:mission", 1L, topology.revision, listOf(
        RouteSegmentSnapshot(trunk, ViewDirection.AB, 0.0, 10.0),
        RouteSegmentSnapshot(exitRail, ViewDirection.AB, 0.0, 10.0)
    ))
    private fun admission(route: RouteSnapshot, requested: Double, committed: Double? = null, released: Double = 0.0) =
        ActiveRouteAdmissionSnapshot(owner, "train", route,
            if (committed == requested) ActiveRouteAdmissionState.GRANTED else ActiveRouteAdmissionState.PREPARING,
            RouteBoundary(requested), committed?.let(::RouteBoundary), released)
    private fun selection(world: SimpleSignalsWorldRuntime) =
        requireNotNull(world.protectionRouteSelectionSnapshot()).selections.getValue("test:entry")
    private fun boundary(id: String, rail: UUID, at: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition(id, RailPositionSnapshot(rail, facing, at))
    private fun frame(epoch: Long, branch: UUID, reverse: Boolean = false, topologyRevision: Long = topology.revision): RailwayWorldFrameSnapshot {
        val current = ActiveRailContinuationsSnapshot.create(topologyRevision, epoch, mapOf(
            endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A),
            endpoint(branch, RailEndpoint.A) to endpoint(trunk, RailEndpoint.B)
        ))
        val position = RailPositionSnapshot(trunk, ViewDirection.AB, 0.0)
        return RailwayWorldFrameSnapshot.create(epoch, topologyRevision, listOf(TrainFrameSnapshot.create(
            "train", "Train", epoch, epoch, 0.0, false, reverse, emptyList(), position, position,
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )), current)
    }
}
