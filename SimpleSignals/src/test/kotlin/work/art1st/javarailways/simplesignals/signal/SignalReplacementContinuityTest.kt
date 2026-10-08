package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime
import work.art1st.javarailways.simplesignals.testing.protectionTestEpoch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.PerceptionSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.observation.RailwayOccupancyCrossingKind
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.*
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.TrainSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.api.restriction.*
import work.art1st.javarailways.simpleato.api.signal.SignalEpochSource
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SignalReplacementContinuityTest {
    @Test
    fun `captured unchanged inspection binding remains callable and restrictive before replacement baseline`() {
        val fixture = Fixture()
        fixture.establishRequest()
        val publications = mutableListOf<TrainRestrictionSnapshot>()
        val inspection = InspectionRuntime({ snapshot ->
            publications += snapshot
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, snapshot.revision)
        }) { _, _, failure -> throw failure }
        val captured = fixture.world.fixedBlockInspectionSource
        inspection.replaceSources(listOf(captured))
        val stamp = RailwayStateStamp(fixture.worldId.toString(), 2L, 7L)
        inspection.synchronizeRoute(stamp, "train", fixture.requestRoute)
        val trainInspection = inspection.createTrainInspection("train")
        val position = RailPositionSnapshot(fixture.trunk, ViewDirection.AB, 0.0)
        val perception = PerceptionSnapshot(2L, TrainSnapshot("train", 2L, 0.0, false, 1, position, position),
            fixture.requestRoute, emptyList())
        fun inspect() = trainInspection.update(stamp, FrozenRouteBinding.from(fixture.requestRoute),
            0.0, 50.0, 20.0, 1.0, perception)
        inspect()
        fixture.installEdit()
        assertSame(captured, fixture.world.fixedBlockInspectionSource)
        assertNull(fixture.world.sectionSignalSnapshot())
        inspection.markDirty(captured.sourceId, listOf(protectionSignalDeclarationId("test:current")))
        inspect()
        assertEquals(2, publications.last().restrictions.size)
        assertTrue(publications.last().restrictions.all {
            (it as RouteSpeedRestriction).maximumSpeedMetersPerSecond == 0.0 })
    }

    @Test
    fun `old captured epoch finishes LEFT during blocked commit and replacement inherits completed clearance`() {
        val oldCaptured = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val enteredCommit = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val fixture = Fixture { source ->
            SignalEpochSource(source.sourceId, source.areas, source.watchedContinuationEntries,
                source.watchedAdmissionRailIds) { input ->
                if (input.observation.stamp.physicsEpoch == 3L) {
                    oldCaptured.countDown()
                    check(releaseOld.await(5, TimeUnit.SECONDS))
                }
                source.handler.update(input)
            }
        }
        fixture.establishRequest()
        val prepared = fixture.prepareEdit()
        val replacement = SectionOccupancyRuntime.from(prepared.candidatePublication)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldEpoch = executor.submit { fixture.epoch.process(fixture.frame(3L, left = true)) }
            assertTrue(oldCaptured.await(5, TimeUnit.SECONDS))
            val edit = executor.submit {
                fixture.epoch.replaceSourcesAtomically(replacement.sources) {
                    assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED,
                        fixture.world.commitPrepared(prepared, replacement) {
                            enteredCommit.countDown()
                            check(releaseCommit.await(5, TimeUnit.SECONDS))
                        })
                }
            }
            assertTrue(enteredCommit.await(5, TimeUnit.SECONDS))
            releaseOld.countDown()
            oldEpoch.get(5, TimeUnit.SECONDS) // Must finish while the commit remains blocked.
            fixture.assertNextVisit()
            val otherWorld = Fixture()
            otherWorld.establishRequest() // No cross-world lock or callback wait.
            releaseCommit.countDown()
            edit.get(5, TimeUnit.SECONDS)
            fixture.epoch.process(fixture.frame(4L, tail = 6.0))
            fixture.assertNextVisit()
        } finally {
            releaseOld.countDown()
            releaseCommit.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `first replacement frame retains real LEFT across repeated unrelated edits and then remains idle`() {
        val fixture = Fixture()
        fixture.establishRequest()
        repeat(20) { fixture.installEdit() }
        fixture.epoch.process(fixture.frame(3L, left = true))
        fixture.assertNextVisit()
        val snapshot = requireNotNull(fixture.world.sectionOccupancySnapshot())
        assertEquals(listOf(RailwayOccupancyCrossingKind.LEFT),
            snapshot.crossingsBySection.getValue(SectionRef(SectionKind.PROTECTION_ROUTE, "test:first-route"))
                .map { it.kind })
        assertEquals(2, snapshot.sections.size) // Both the continued route and new block are baselined.
        val selection = fixture.world.protectionRouteSelectionSnapshot()
        fixture.epoch.process(fixture.frame(4L, tail = 6.0))
        assertSame(snapshot, fixture.world.sectionOccupancySnapshot())
        assertSame(selection, fixture.world.protectionRouteSelectionSnapshot())
    }

    @Test
    fun `old captured callback finishing after publication still supplies the next selector clearance`() {
        val enteredOld = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val fixture = Fixture { source ->
            SignalEpochSource(source.sourceId, source.areas, source.watchedContinuationEntries,
                source.watchedAdmissionRailIds) { input ->
                if (input.observation.stamp.physicsEpoch == 3L) {
                    enteredOld.countDown()
                    check(releaseOld.await(5, TimeUnit.SECONDS))
                }
                source.handler.update(input)
            }
        }
        fixture.establishRequest()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldEpoch = executor.submit { fixture.epoch.process(fixture.frame(3L, left = true)) }
            assertTrue(enteredOld.await(5, TimeUnit.SECONDS))
            fixture.installEdit() // The old handler is still paused when both references publish.
            assertNull(fixture.world.protectionRouteSelectionSnapshot())
            releaseOld.countDown()
            oldEpoch.get(5, TimeUnit.SECONDS)
            fixture.epoch.process(fixture.frame(4L, tail = 6.0))
            fixture.assertNextVisit()
        } finally {
            releaseOld.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `failed commit preserves old selector occupancy and later exit`() {
        val fixture = Fixture()
        fixture.establishRequest()
        val prepared = fixture.prepareEdit()
        val replacement = SectionOccupancyRuntime.from(prepared.candidatePublication)
        val oldDefinitions = fixture.world.currentDefinitions()
        val oldOccupancy = fixture.world.sectionOccupancySnapshot()
        assertThrows(IllegalStateException::class.java) {
            fixture.epoch.replaceSourcesAtomically(replacement.sources) {
                fixture.world.commitPrepared(prepared, replacement) { error("staged rename failed") }
            }
        }
        assertSame(oldDefinitions, fixture.world.currentDefinitions())
        assertSame(oldOccupancy, fixture.world.sectionOccupancySnapshot())
        fixture.epoch.process(fixture.frame(3L, left = true))
        fixture.assertNextVisit()
        assertNull(replacement.snapshot())
    }

    @Test
    fun `changed region cannot inherit known repeated visit even when returned to original before processing`() {
        val fixture = Fixture()
        fixture.establishRequest()
        val changed = ProtectionRegionDefinition("test:region", 2L, listOf(
            boundary("test:entry", fixture.trunk, 4.0, ViewDirection.AB),
            boundary("test:exit", fixture.branch, 5.0, ViewDirection.BA)))
        val changedRoute = ProtectionRouteDefinition("test:first-route", "test:entry", "test:exit", listOf(
            ProtectionRouteTraversal(fixture.trunk, ViewDirection.AB, 4.0, 10.0),
            ProtectionRouteTraversal(fixture.branch, ViewDirection.AB, 0.0, 5.0)))
        fixture.install(fixture.definitions(2L, changed, changedRoute, tableRevision = 2L))
        val restored = ProtectionRegionDefinition("test:region", 3L, fixture.region.boundaries)
        fixture.install(fixture.definitions(3L, restored, fixture.fixedRoute, tableRevision = 3L))
        fixture.epoch.process(fixture.frame(3L, left = true))
        assertFalse(fixture.selection().available)
        assertFalse(fixture.selection().routeReady)
        assertEquals(emptyMap<Any, Any>(), fixture.world.sectionOccupancySnapshot()!!.crossingsBySection)
    }

    private class Fixture(transformSource: (SignalEpochSource) -> SignalEpochSource = { it }) {
        val worldId = UUID.randomUUID()
        val trunk = UUID(0, 901)
        val branch = UUID(0, 902)
        private val connector = UUID(0, 903)
        private val unrelated = UUID(0, 904)
        private val topology = topologyState(7L, listOf(trunk, branch, connector, unrelated), listOf(
            endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A),
            endpoint(branch, RailEndpoint.B) to endpoint(connector, RailEndpoint.A),
            endpoint(connector, RailEndpoint.B) to endpoint(trunk, RailEndpoint.A)))
        val region = ProtectionRegionDefinition("test:region", 1L, listOf(
            boundary("test:entry", trunk, 5.0, ViewDirection.AB),
            boundary("test:exit", branch, 5.0, ViewDirection.BA)))
        val fixedRoute = ProtectionRouteDefinition("test:first-route", "test:entry", "test:exit", listOf(
            ProtectionRouteTraversal(trunk, ViewDirection.AB, 5.0, 10.0),
            ProtectionRouteTraversal(branch, ViewDirection.AB, 0.0, 5.0)))
        private val signal = ProtectionSignalDefinition("test:current", 1L, ProtectionSignalKind.CURRENT_ENTRY,
            "test:entry", 0.0, "automatic:block")
        private val fixedSignal = ProtectionSignalDefinition("test:fixed", 1L, ProtectionSignalKind.FIXED_ROUTE,
            fixedRoute.routeId, 0.0, "automatic:block")
        val world = signalWorldRuntime(worldId)
        val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        })
        private val owner = InfrastructureOwner("test:owner", 1L)
        val requestRoute = RouteSnapshot("test:repeated", 1L, topology.revision,
            listOf(trunk, branch, connector, trunk, branch).map {
                RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0)
            })

        init {
            assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
                world.initializeDefinitions(topology, definitions(1L)).status)
            epoch.replaceSources(world.initializeSectionOccupancy().sources.map(transformSource))
        }

        fun definitions(revision: Long, region: ProtectionRegionDefinition = this.region,
            route: ProtectionRouteDefinition = fixedRoute, tableRevision: Long = 1L) =
            SimpleSignalsWorldDefinitions(worldId, revision, listOf(region),
                listOf(ProtectionRouteTableDefinition("test:table", tableRevision, region.regionId, region.revision,
                    listOf(route))), protectionSignals = listOf(signal, fixedSignal))

        fun establishRequest() {
            epoch.process(frame(1L))
            epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, ActiveRouteAdmissionSnapshot(owner,
                "train", requestRoute, ActiveRouteAdmissionState.PREPARING, RouteBoundary(50.0), RouteBoundary(30.0))))
            epoch.process(frame(2L))
            assertTrue(selection().routeReady)
            assertEquals(0.0, world.sectionSignalSnapshot()!!.states.getValue("test:fixed")
                .effectiveSpeedLimitMetersPerSecond)
        }

        fun selection() = world.protectionRouteSelectionSnapshot()!!.selections.getValue("test:entry")

        fun assertNextVisit() {
            val selected = selection()
            assertEquals(ProtectionRouteSelectionSource.REQUEST, selected.source)
            assertEquals("test:first-route", selected.routeId)
            assertTrue(selected.available)
            assertEquals(ActiveRouteAdmissionState.PREPARING, selected.requestState)
            assertFalse(selected.routeReady) // Exit15 was cleared; exit45 still exceeds the30m grant.
            assertEquals(0.0, world.sectionSignalSnapshot()!!.states.getValue("test:current")
                .effectiveSpeedLimitMetersPerSecond)
            assertEquals(0.0, world.sectionSignalSnapshot()!!.states.getValue("test:fixed")
                .effectiveSpeedLimitMetersPerSecond)
        }

        fun prepareEdit(): SimpleSignalsPreparedDefinitions {
            val current = world.currentDefinitions()
            val revision = current.definitionRevision + 1L
            val block = FixedBlockDefinition("test:unrelated", revision,
                listOf(FixedBlockTraversal(unrelated, ViewDirection.AB, 0.0, if (revision % 2L == 0L) 2.0 else 3.0)))
            val definitions = SimpleSignalsWorldDefinitions(worldId, revision, current.regions, current.routeTables,
                listOf(block), current.fixedBlockSignals, current.protectionSignals, current.routeIndicators)
            return (world.prepareDefinitions(topology, definitions) as SimpleSignalsDefinitionPreparationResult.Prepared).value
        }

        fun installEdit() = installPrepared(prepareEdit())

        fun install(definitions: SimpleSignalsWorldDefinitions) = installPrepared(
            (world.prepareDefinitions(topology, definitions) as SimpleSignalsDefinitionPreparationResult.Prepared).value)

        private fun installPrepared(prepared: SimpleSignalsPreparedDefinitions) {
            val replacement = SectionOccupancyRuntime.from(prepared.candidatePublication)
            epoch.replaceSourcesAtomically(replacement.sources) {
                assertEquals(SimpleSignalsDefinitionCommitStatus.COMMITTED, world.commitPrepared(prepared, replacement))
            }
        }

        fun frame(atEpoch: Long, left: Boolean = false, tail: Double = if (left) 6.0 else 4.0): RailwayWorldFrameSnapshot {
            val position = RailPositionSnapshot(branch, ViewDirection.AB, tail)
            return RailwayWorldFrameSnapshot.create(atEpoch, topology.revision, listOf(TrainFrameSnapshot.create(
                "train", "Train", atEpoch, atEpoch, 0.0, false, false, emptyList(), position, position,
                listOf(RailInterval(branch, tail, tail)), emptyList(), emptyList(), emptyList(),
                if (left) listOf(RailTraversalSegmentSnapshot(branch, ViewDirection.AB, 4.0, 6.0)) else emptyList()
            )), ActiveRailContinuationsSnapshot.empty(topology.revision))
        }
    }

    companion object {
        private fun boundary(id: String, rail: UUID, at: Double, facing: ViewDirection) =
            ProtectionBoundaryDefinition(id, RailPositionSnapshot(rail, facing, at))
    }
}
