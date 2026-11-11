package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime
import work.art1st.javarailways.simplesignals.testing.protectionTestEpoch
import work.art1st.javarailways.simplesignals.testing.TestProtectionEpoch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionSnapshot
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionState
import work.art1st.javarailways.basic.api.route.ActiveRouteAdmissionUpdate
import work.art1st.javarailways.basic.api.route.RouteBoundary
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublisher
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPublicationStatus
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.SectionOccupancyRuntime
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPreparationResult
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionCommitStatus
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
    private val firstRoute = fixedRoute("test:first-route", first, "test:first-exit")
    private val requestedRoute = fixedRoute("test:second-route", second, "test:second-exit")
    private val owner = InfrastructureOwner("test:owner", 1L)

    @Test
    fun `static preparation accepts repeated complete table visits without any live baseline`() {
        val connector = UUID(0, 904)
        val loop = topologyState(7L, listOf(trunk, first, second, connector), listOf(
            endpoint(trunk, RailEndpoint.B) to endpoint(first, RailEndpoint.A),
            endpoint(trunk, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(first, RailEndpoint.B) to endpoint(connector, RailEndpoint.A),
            endpoint(connector, RailEndpoint.B) to endpoint(trunk, RailEndpoint.A)))
        val world = signalWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            world.initializeDefinitions(loop, SimpleSignalsWorldDefinitions(worldId, 1L, listOf(region),
                listOf(ProtectionRouteTableDefinition("test:table", 1L, region.regionId, 1L,
                    listOf(firstRoute, requestedRoute))))).status)
        val route = RouteSnapshot("test:repeated", 1L, loop.revision,
            listOf(trunk, first, connector, trunk, first).map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
        val selector = ProtectionRouteSelectionRuntime(world.authoringPublication())
        val before = selector.snapshot()
        assertTrue(world.protectionRegionInspectionSource.routeInfrastructureDependencies.dependencies(route).isEmpty())
        val matched = world.authoringPublication().routeBindingCompiler.compile(route)
        assertEquals(listOf(5.0, 35.0), matched.windows.map { it.region.entry.routeDistanceMeters })
        assertEquals(listOf(firstRoute.routeId, firstRoute.routeId), matched.windows.map { it.tableRoute?.definition?.routeId })
        assertSame(before, selector.snapshot())
        assertNull(world.protectionRouteSelectionSnapshot())
    }

    @Test
    fun `physical exit advances repeated selection despite retained resources and survives append`() {
        val connector = UUID(0, 904)
        val unrelated = UUID(0, 905)
        val loopTopology = topologyState(7L, listOf(trunk, first, second, connector, unrelated), listOf(
            endpoint(trunk, RailEndpoint.B) to endpoint(first, RailEndpoint.A),
            endpoint(trunk, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(first, RailEndpoint.B) to endpoint(connector, RailEndpoint.A),
            endpoint(connector, RailEndpoint.B) to endpoint(trunk, RailEndpoint.A)))
        val signal = ProtectionSignalDefinition("test:current", 1L, ProtectionSignalKind.CURRENT_ENTRY,
            "test:entry", 0.0, "automatic:block")
        val reverse = ProtectionRouteDefinition("test:reverse-first", "test:first-exit", "test:entry", listOf(
            ProtectionRouteTraversal(first, ViewDirection.BA, 0.0, 5.0),
            ProtectionRouteTraversal(trunk, ViewDirection.BA, 5.0, 10.0)))
        val tableRoutes = listOf(firstRoute, requestedRoute, reverse)
        fun freshRuntime(indicatorOnly: Boolean = false): Pair<SimpleSignalsWorldRuntime, TestProtectionEpoch> {
            val definitions = SimpleSignalsWorldDefinitions(worldId, 1L,
                listOf(region), listOf(ProtectionRouteTableDefinition("test:table", 1L, region.regionId, 1L,
                    tableRoutes)),
                protectionSignals = if (indicatorOnly) emptyList() else listOf(signal),
                routeIndicators = if (indicatorOnly) listOf(RouteIndicatorDefinition("test:indicator", 1L,
                    "test:entry", "none",
                    mapOf(firstRoute.routeId to "first", requestedRoute.routeId to "second"))) else emptyList())
            val world = signalWorldRuntime(worldId)
            val initialized = world.initializeDefinitions(loopTopology, definitions)
            assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, initialized.status, initialized.diagnostics.toString())
            val occupancy = world.initializeSectionOccupancy()
            val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
                TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
            })
            epoch.replaceSources(occupancy.sources)
            return world to epoch
        }
        val (world, epoch) = freshRuntime()
        val initial = RouteSnapshot("test:mission", 1L, loopTopology.revision,
            listOf(trunk, first, connector).map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
        val appended = RouteSnapshot(initial.routeId, 2L, initial.topologyRevision,
            initial.segments + listOf(trunk, second).map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
        fun physical(atEpoch: Long, branch: UUID, tail: Double, crossedExit: Boolean = false,
            trainId: String = "train", tailRail: UUID = first): RailwayWorldFrameSnapshot {
            val position = RailPositionSnapshot(tailRail, ViewDirection.AB, tail)
            return RailwayWorldFrameSnapshot.create(atEpoch, loopTopology.revision, listOf(TrainFrameSnapshot.create(
                trainId, "Train", atEpoch, atEpoch, 0.0, false, false, emptyList(), position, position,
                listOf(RailInterval(tailRail, tail, tail)), emptyList(), emptyList(), emptyList(),
                if (crossedExit) listOf(RailTraversalSegmentSnapshot(first, ViewDirection.AB, 4.0, 6.0)) else emptyList()
            )), ActiveRailContinuationsSnapshot.create(loopTopology.revision, atEpoch, mapOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A),
                endpoint(branch, RailEndpoint.A) to endpoint(trunk, RailEndpoint.B))))
        }
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(initial, 30.0, 30.0)))
        epoch.process(physical(1L, first, 4.0))
        assertEquals(firstRoute.routeId, selection(world).routeId)
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(world).source)

        // Basic has not published a release-boundary advance: a longer rail/block may remain held.
        epoch.process(physical(2L, first, 6.0, crossedExit = true))
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(appended, 50.0, 30.0)))
        epoch.process(physical(3L, second, 6.0))
        assertEquals(requestedRoute.routeId, selection(world).routeId)
        assertFalse(selection(world).routeReady)
        assertEquals(ActiveRouteAdmissionState.PREPARING, selection(world).requestState)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(appended, 50.0, 50.0)))
        epoch.process(physical(4L, second, 6.0))
        assertTrue(selection(world).routeReady)
        val unchanged = world.protectionRouteSelectionSnapshot()
        epoch.process(physical(5L, second, 6.0))
        assertSame(unchanged, world.protectionRouteSelectionSnapshot())

        val repeated = RouteSnapshot(appended.routeId, appended.revision, appended.topologyRevision,
            initial.segments + listOf(trunk, first).map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
        val (sameWorld, sameEpoch) = freshRuntime()
        sameEpoch.process(physical(1L, first, 4.0)) // Establish the source before this new request.
        sameEpoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(repeated, 50.0, 30.0)))
        sameEpoch.process(physical(2L, first, 4.0))
        assertTrue(selection(sameWorld).routeReady) // The first visit, not the ungranted second.
        sameEpoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(repeated, 50.0, 30.0, 16.0)))
        sameEpoch.process(physical(3L, first, 6.0, crossedExit = true))
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(sameWorld).source)
        assertFalse(selection(sameWorld).routeReady) // One LEFT cannot clear both identical visits.
        val unrelatedRegion = ProtectionRegionDefinition("test:unrelated", 1L, listOf(
            boundary("test:unrelated-entry", unrelated, 2.0, ViewDirection.AB),
            boundary("test:unrelated-exit", unrelated, 8.0, ViewDirection.BA)))
        val edited = SimpleSignalsWorldDefinitions(worldId, 2L, listOf(region, unrelatedRegion),
            listOf(ProtectionRouteTableDefinition("test:table", 1L, region.regionId, 1L,
                tableRoutes)), protectionSignals = listOf(signal))
        val prepared = assertInstanceOf(SimpleSignalsDefinitionPreparationResult.Prepared::class.java,
            sameWorld.prepareDefinitions(loopTopology, edited)).value
        val replacement = SectionOccupancyRuntime.from(prepared.candidatePublication)
        assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, sameWorld.commitPrepared(prepared, replacement))
        sameEpoch.replaceSources(replacement.sources)
        val chainedDefinitions = SimpleSignalsWorldDefinitions(worldId, 3L, edited.regions, edited.routeTables,
            fixedBlocks = listOf(FixedBlockDefinition("test:unrelated-block", 1L,
                listOf(FixedBlockTraversal(unrelated, ViewDirection.AB, 8.0, 10.0)))), protectionSignals = listOf(signal))
        val chained = assertInstanceOf(SimpleSignalsDefinitionPreparationResult.Prepared::class.java,
            sameWorld.prepareDefinitions(loopTopology, chainedDefinitions)).value
        val chainedOccupancy = SectionOccupancyRuntime.from(chained.candidatePublication)
        assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, sameWorld.commitPrepared(chained, chainedOccupancy))
        sameEpoch.replaceSources(chainedOccupancy.sources) // No signal epoch between these two edits.
        sameEpoch.process(physical(4L, first, 6.0))
        assertTrue(selection(sameWorld).available)
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(sameWorld).source)
        assertFalse(selection(sameWorld).routeReady) // An unrelated edit preserves the cleared first visit.
        sameEpoch.process(physical(5L, first, 6.0, crossedExit = true, trainId = "other"))
        sameEpoch.process(physical(6L, first, 6.0))
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(sameWorld).source)
        assertFalse(selection(sameWorld).routeReady) // Another train cannot clear this owner's visit.
        val backward = RouteSnapshot("test:reversed", 3L, loopTopology.revision, listOf(
            RouteSegmentSnapshot(first, ViewDirection.BA, 4.0, 10.0),
            RouteSegmentSnapshot(trunk, ViewDirection.BA, 0.0, 10.0)))
        sameEpoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(backward, 16.0, 16.0)))
        sameEpoch.process(physical(7L, first, 6.0))
        val reverseSelection = requireNotNull(sameWorld.protectionRouteSelectionSnapshot())
            .selections.getValue("test:first-exit")
        assertEquals(reverse.routeId, reverseSelection.routeId)
        assertTrue(reverseSelection.routeReady) // Its 11m exit is below the old 15m clearance.

        val (coldWorld, coldEpoch) = freshRuntime()
        coldEpoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(repeated, 50.0, 50.0)))
        coldEpoch.process(physical(1L, first, 0.0, tailRail = trunk))
        assertFalse(selection(coldWorld).available) // Exact physical origin does not establish the visit.
        coldEpoch.process(physical(2L, first, 6.0, crossedExit = true))
        assertFalse(selection(coldWorld).available) // Missing prior history is not invented by one event.

        val (indicatorWorld, indicatorEpoch) = freshRuntime(indicatorOnly = true)
        indicatorEpoch.process(physical(1L, first, 4.0))
        indicatorEpoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(appended, 50.0, 30.0)))
        indicatorEpoch.process(physical(2L, first, 4.0))
        assertEquals(firstRoute.routeId, selection(indicatorWorld).routeId)
        indicatorEpoch.process(physical(3L, second, 6.0, crossedExit = true))
        assertEquals(requestedRoute.routeId, selection(indicatorWorld).routeId)
        assertFalse(selection(indicatorWorld).routeReady) // Indicators alone still receive route exit events.
    }

    @Test
    fun `request readiness updates and ended request removes selection`() {
        val (world, epoch) = runtime()
        epoch.process(frame(1, first))
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
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
        assertNull(selection(world).routeId)
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
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
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
    }

    @Test
    fun `request moved to unrelated rail removes entrance membership`() {
        val (world, epoch) = runtime()
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route(second), 20.0, 20.0)))
        epoch.process(frame(1, second))
        val unrelated = RouteSnapshot("test:unrelated", 2L, topology.revision,
            listOf(RouteSegmentSnapshot(UUID(0, 999), ViewDirection.AB, 0.0, 10.0)))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(unrelated, 10.0, 10.0)))
        epoch.process(frame(2, first))
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
        val unchanged = world.protectionRouteSelectionSnapshot()
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        epoch.process(frame(3, first))
        assertSame(unchanged, world.protectionRouteSelectionSnapshot())
    }

    @Test
    fun `sufficient clearance floor remains ready while distant suffix is preparing`() {
        val (world, epoch) = runtime()
        val frozen = route(second)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 16.0)))
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
    fun `unrequested future entry stays empty and released past entry removes request`() {
        val (world, epoch) = runtime()
        val frozen = route(second)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 5.0)))
        epoch.process(frame(1, first))
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0)))
        epoch.process(frame(2, second))
        assertEquals(ProtectionRouteSelectionSource.REQUEST, selection(world).source)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0, 15.0)))
        epoch.process(frame(3, first))
        assertEquals(ProtectionRouteSelectionSource.NONE, selection(world).source)
    }

    @Test
    fun `conflicting requests and missing train remain unavailable`() {
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
    fun `unknown frozen path and affected topology are restrictive while unrelated edit preserves selection`() {
        val (world, epoch) = runtime()
        val partial = RouteSnapshot("test:partial", 1, topology.revision,
            listOf(RouteSegmentSnapshot(trunk, ViewDirection.AB, 0.0, 8.0)))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(partial, 8.0, 8.0)))
        epoch.process(frame(1, first))
        assertFalse(selection(world).available)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route(first), 20.0, 20.0)))
        epoch.process(frame(2, first))
        assertTrue(selection(world).routeReady)
        val extra = UUID(0, 905)
        val sameRegionConnections = listOf(endpoint(trunk, RailEndpoint.B) to endpoint(first, RailEndpoint.A),
            endpoint(trunk, RailEndpoint.B) to endpoint(second, RailEndpoint.A))
        val unrelatedRoot = topologyState(8, listOf(trunk, first, second, extra), sameRegionConnections)
        val unrelatedEdit = work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange(topology, unrelatedRoot, setOf(extra))
        world.topologyGuard.invalidate(unrelatedEdit)
        epoch.invalidateTopology(unrelatedEdit)
        epoch.process(frame(3, first, topologyRevision = 8))
        assertTrue(selection(world).routeReady)
        val affectedRoot = topologyState(9, listOf(trunk, first, second, extra),
            sameRegionConnections + (endpoint(trunk, RailEndpoint.B) to endpoint(extra, RailEndpoint.A)))
        val affectedEdit = work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange(unrelatedRoot, affectedRoot,
            setOf(trunk, first, second, extra))
        world.topologyGuard.invalidate(affectedEdit)
        epoch.invalidateTopology(affectedEdit)
        epoch.process(frame(4, first, topologyRevision = 9))
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
        Pair<SimpleSignalsWorldRuntime, TestProtectionEpoch> {
        val world = signalWorldRuntime(worldId)
        val result = world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 1L, listOf(region),
            listOf(ProtectionRouteTableDefinition("test:table", 1L, region.regionId, 1L,
                listOf(firstRoute, requestedRoute) + extraRoutes)), blocks))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.toString())
        val occupancy = world.initializeSectionOccupancy()
        val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
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
