package work.art1st.javarailways.simplesignals.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.rail.topology.TopologyState
import work.art1st.javarailways.simplesignals.block.FixedBlockAuthorityMatchResult
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockRouteMatcher
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.signal.*
import work.art1st.javarailways.simpleato.signal.SignalEpochRuntime
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationResult
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionPublicationStatus
import java.util.UUID

class SectionTopologyRevalidationTest {
    private val worldId = UUID(1, 1)
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val branch = UUID(0, 3)
    private val unrelated = UUID(0, 4)
    private val otherNew = UUID(0, 5)
    private val line = listOf(endpoint(a, RailEndpoint.B) to endpoint(b, RailEndpoint.A))

    private fun topology(revision: Long, extra: Boolean = false, others: List<UUID> = listOf(unrelated)): TopologyState =
        topologyState(revision, listOf(a, b) + (if (extra) listOf(branch) else emptyList()) + others,
            line + if (extra) listOf(endpoint(a, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)) else emptyList())
    private fun block(id: String, vararg rails: UUID) = FixedBlockDefinition(id, 0,
        rails.map { FixedBlockTraversal(it, ViewDirection.AB, 0.0, 10.0) }, bidirectional = true)
    private fun signal(block: FixedBlockDefinition) = FixedBlockSignalDefinition("${block.blockId}/signal", 0,
        block.blockId, 2.0, "automatic:block")
    private fun region() = ProtectionRegionDefinition("test:region", 0, listOf(
        ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(a, ViewDirection.AB, 2.0)),
        ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(b, ViewDirection.BA, 8.0))))
    private fun runtime(root: TopologyState, blocks: List<FixedBlockDefinition> = emptyList(),
        regions: List<ProtectionRegionDefinition> = emptyList()): SimpleSignalsWorldRuntime = SimpleSignalsWorldRuntime(worldId).also {
        val result = it.initializeDefinitions(root, SimpleSignalsWorldDefinitions(worldId, 1, regions, emptyList(), blocks, blocks.map(::signal)))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.joinToString())
        it.initializeSectionOccupancy()
    }
    private fun route(root: TopologyState, vararg rails: UUID) = RouteSnapshot("test:route", 0, root.revision,
        rails.map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
    private fun frame(epoch: Long, root: TopologyState, occupied: UUID? = null): RailwayWorldFrameSnapshot {
        val position = occupied?.let { RailPositionSnapshot(it, ViewDirection.AB, 5.0) }
        val trains = if (occupied == null) emptyList() else listOf(TrainFrameSnapshot.create(
            "train", "Train", epoch, epoch, 0.0, false, false, emptyList(), checkNotNull(position), position,
            listOf(RailInterval(occupied, 4.0, 5.0)), emptyList(), emptyList(), emptyList(), emptyList()))
        return RailwayWorldFrameSnapshot.create(epoch, root.revision, trains, ActiveRailContinuationsSnapshot.empty(root.revision))
    }
    private fun refresh(runtime: SimpleSignalsWorldRuntime): SimpleSignalsRuntimePublication {
        val base = runtime.authoringPublication()
        val captured = runtime.topologyGuard.capture()
        val next = SectionTopologyRevalidation.compile(base, captured, 1000.0)
        val occupancy = SectionOccupancyRuntime.from(next)
        occupancy.retainPublishedFrom(runtime.currentSectionOccupancy())
        assertTrue(runtime.commitTopologyRevalidation(base, captured, next, occupancy))
        return next
    }

    @Test fun `inserted turnout disables only indexed block and keeps unchanged geometry and signal index`() {
        val initial = topology(1)
        val main = block("test:main", a, b)
        val other = block("test:other", unrelated)
        val runtime = runtime(initial, listOf(main, other))
        val original = runtime.authoringPublication()
        val otherGeometry = runtime.fixedBlockSnapshot().blocksById.getValue(other.blockId)
        val changed = topology(2, true)
        assertTrue(runtime.topologyGuard.invalidate(RailTopologyChange(initial, changed, setOf(a, b, branch))))
        assertThrows(IllegalStateException::class.java) { runtime.topologyGuard.requireUsable(route(changed, a, b), 20.0) }
        assertThrows(IllegalStateException::class.java) {
            runtime.fixedBlockInspectionSource.routeInfrastructureDependencies.dependencies(route(changed, a, b))
        }
        assertTrue(runtime.fixedBlockInspectionSource.routeInfrastructureDependencies.dependencies(route(initial, unrelated)).isNotEmpty())
        runtime.topologyGuard.requireUsable(route(initial, unrelated), 10.0)
        val next = refresh(runtime)
        assertSame(otherGeometry, next.fixedBlocks.snapshot(changed).blocksById.getValue(other.blockId))
        assertTrue(next.fixedBlockSignals.hasSignalForEntry(signal(other).entry))
        assertEquals(setOf(main.blockId), next.fixedBlocks.snapshot(changed).unavailableBlockIds)
        assertEquals(original.definitions, runtime.currentDefinitions())
        assertTrue(FixedBlockRouteMatcher.matchRoute(runtime.fixedBlockSnapshot(), route(initial, unrelated)) is FixedBlockAuthorityMatchResult.Matched)
    }

    @Test fun `deleted rail keeps definition and reverse dependency so replacement repairs it`() {
        val initial = topology(1)
        val main = block("test:main", a, b)
        val runtime = runtime(initial, listOf(main))
        val missing = topologyState(2, listOf(a, unrelated), emptyList())
        runtime.topologyGuard.invalidate(RailTopologyChange(initial, missing, setOf(a, b)))
        refresh(runtime)
        assertEquals(listOf(main), runtime.currentDefinitions().fixedBlocks)
        assertTrue(runtime.topologyGuard.sectionUnavailable(TopologySectionKind.BLOCK, main.blockId))
        val repaired = topology(3)
        assertTrue(runtime.topologyGuard.invalidate(RailTopologyChange(missing, repaired, setOf(a, b))))
        refresh(runtime)
        assertFalse(runtime.topologyGuard.sectionUnavailable(TopologySectionKind.BLOCK, main.blockId))
        runtime.topologyGuard.requireUsable(route(repaired, a, b), 20.0)
    }

    @Test fun `unaffected section observation and signal survive pending topology and worker baseline`() {
        val initial = topology(1)
        val main = block("test:main", a, b)
        val other = block("test:other", unrelated)
        val runtime = runtime(initial, listOf(main, other))
        val occupancy = runtime.currentSectionOccupancy()!!
        val epochs = SignalEpochRuntime(worldId.toString(), { value ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, value.revision)
        })
        epochs.replaceSources(occupancy.sources)
        fun frame(epoch: Long, root: TopologyState) = RailwayWorldFrameSnapshot.create(epoch, root.revision,
            emptyList(), ActiveRailContinuationsSnapshot.empty(root.revision))
        epochs.process(frame(1, initial))
        val otherRef = SectionRef(SectionKind.FIXED_BLOCK, other.blockId)
        val mainRef = SectionRef(SectionKind.FIXED_BLOCK, main.blockId)
        val observation = occupancy.snapshot()!!.sections.getValue(otherRef)
        val output = occupancy.signalSnapshot()!!.states.getValue(signal(other).signalId)
        val changed = topology(2, true)
        val event = RailTopologyChange(initial, changed, setOf(a, b, branch))
        runtime.topologyGuard.invalidate(event)
        epochs.invalidateTopology(event)
        epochs.process(frame(2, changed))
        assertSame(observation, occupancy.snapshot()!!.sections.getValue(otherRef))
        assertEquals(output, occupancy.signalSnapshot()!!.states.getValue(signal(other).signalId))
        assertFalse(occupancy.snapshot()!!.sections.getValue(mainRef).available)
        val base = runtime.authoringPublication()
        val captured = runtime.topologyGuard.capture()
        val candidate = SectionTopologyRevalidation.compile(base, captured, 1000.0)
        val replacement = SectionOccupancyRuntime.from(candidate)
        replacement.retainPublishedFrom(occupancy)
        epochs.replaceSourcesAtomically(replacement.sources) {
            assertTrue(runtime.commitTopologyRevalidation(base, captured, candidate, replacement))
        }
        epochs.process(frame(3, changed))
        assertSame(observation, replacement.snapshot()!!.sections.getValue(otherRef))
        assertEquals(output, replacement.signalSnapshot()!!.states.getValue(signal(other).signalId))
        assertFalse(replacement.snapshot()!!.sections.getValue(mainRef).available)
    }

    @Test fun `open siding is forbidden before worker and after failure then repair automatically restores region`() {
        val initial = topology(1)
        val region = region()
        val other = block("test:other", unrelated)
        val runtime = runtime(initial, listOf(other), listOf(region))
        val changed = topology(2, true)
        runtime.topologyGuard.invalidate(RailTopologyChange(initial, changed, setOf(a, b, branch)))
        val sideRoute = route(changed, branch)
        assertThrows(IllegalStateException::class.java) { runtime.topologyGuard.requireUsable(sideRoute, 10.0) }
        assertThrows(IllegalStateException::class.java) {
            runtime.protectionRegionInspectionSource.routeInfrastructureDependencies.dependencies(sideRoute)
        }
        assertTrue(runtime.protectionRegionInspectionSource.routeInfrastructureDependencies.dependencies(route(initial, unrelated)).isEmpty())
        runtime.topologyGuard.requireUsable(route(initial, unrelated), 10.0)
        val next = refresh(runtime)
        assertEquals(setOf(region.regionId), next.regions.compilationSnapshot()!!.unavailableRegionIds)
        assertEquals(listOf(region), runtime.currentDefinitions().regions)
        assertThrows(IllegalStateException::class.java) { runtime.topologyGuard.requireUsable(sideRoute, 10.0) }
        val restrictions = runtime.routePlanningBinding.source.restrictions(changed)
        assertTrue(restrictions.forbiddenTraversals.any { it.railId == branch })
        assertFalse(restrictions.forbiddenTraversals.any { it.railId == unrelated })
        val repaired = topology(3)
        runtime.topologyGuard.invalidate(RailTopologyChange(changed, repaired, setOf(a, b, branch)))
        refresh(runtime)
        assertFalse(runtime.topologyGuard.sectionUnavailable(TopologySectionKind.REGION, region.regionId))
    }

    @Test fun `worker handoff keeps unrelated unavailable JS signal on the last completed epoch until replacement baseline`() {
        val initial = topology(1)
        val main = block("test:main", a, b)
        val other = block("test:other", unrelated)
        val runtime = runtime(initial, listOf(main, other))
        val occupancy = runtime.currentSectionOccupancy()!!
        val epochs = SignalEpochRuntime(worldId.toString(), { value ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, value.revision)
        })
        epochs.replaceSources(occupancy.sources)
        epochs.process(frame(1, initial))
        val section = SectionRef(SectionKind.FIXED_BLOCK, other.blockId)
        assertEquals(0.0, occupancy.fixedBlockSignal(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry(section.id))!!.state.effectiveSpeedLimitMetersPerSecond)
        val changed = topology(2, true)
        val event = RailTopologyChange(initial, changed, setOf(a, b, branch))
        runtime.topologyGuard.invalidate(event)
        epochs.invalidateTopology(event)
        val base = runtime.authoringPublication()
        val captured = runtime.topologyGuard.capture()
        val candidate = SectionTopologyRevalidation.compile(base, captured, 1000.0)
        val replacement = SectionOccupancyRuntime.from(candidate)
        replacement.retainPublishedFrom(occupancy) // Worker sees the old restrictive output.
        epochs.process(frame(2, changed, unrelated)) // Captured old source sees the real occupant later.
        epochs.replaceSourcesAtomically(replacement.sources) {
            assertTrue(runtime.commitTopologyRevalidation(base, captured, candidate, replacement))
            assertEquals(0.0, replacement.fixedBlockSignal(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry(section.id))!!.state.effectiveSpeedLimitMetersPerSecond)
            assertEquals(setOf("train"), replacement.snapshot()!!.sections.getValue(section).occupants)
            assertEquals(2L, replacement.signalById(signal(other).signalId)!!.stamp.physicsEpoch)
            // Host permits an old epoch even after plugin commit, until its source swap completes.
            epochs.process(frame(3, changed))
            assertEquals(0.0, replacement.fixedBlockSignal(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry(section.id))!!.state.effectiveSpeedLimitMetersPerSecond)
            assertEquals(3L, replacement.signalById(signal(other).signalId)!!.stamp.physicsEpoch)
            assertEquals(0.0, replacement.fixedBlockSignal(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry(main.blockId))!!.state.effectiveSpeedLimitMetersPerSecond)
        }
        epochs.process(frame(4, changed))
        assertEquals(0.0, replacement.fixedBlockSignal(work.art1st.javarailways.simplesignals.signal.FixedBlockSignalEntry(section.id))!!.state.effectiveSpeedLimitMetersPerSecond)
        assertTrue(replacement.snapshot()!!.sections.getValue(section).occupants.isEmpty())
    }

    @Test fun `invalid protection route keeps nonempty last known occupancy through worker and later baseline`() {
        val initial = topology(1)
        val region = region()
        val path = ProtectionRouteDefinition("test:path", "test:entry", "test:exit", listOf(
            ProtectionRouteTraversal(a, ViewDirection.AB, 2.0, 10.0), ProtectionRouteTraversal(b, ViewDirection.AB, 0.0, 8.0)))
        val table = ProtectionRouteTableDefinition("test:table", 0, region.regionId, region.revision, listOf(path))
        val device = ProtectionSignalDefinition("test:route-signal", 0, ProtectionSignalKind.FIXED_ROUTE, path.routeId,
            2.0, "automatic:block")
        val runtime = SimpleSignalsWorldRuntime(worldId)
        val installed = runtime.initializeDefinitions(initial,
            SimpleSignalsWorldDefinitions(worldId, 1, listOf(region), listOf(table), protectionSignals = listOf(device)))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, installed.status, installed.diagnostics.toString())
        val occupancy = runtime.initializeSectionOccupancy()
        val epochs = SignalEpochRuntime(worldId.toString(), { value ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, value.revision)
        })
        epochs.replaceSources(occupancy.sources)
        epochs.process(frame(1, initial, a))
        val section = SectionRef(SectionKind.PROTECTION_ROUTE, path.routeId)
        assertEquals(setOf("train"), occupancy.snapshot()!!.sections.getValue(section).occupants)
        val changed = topology(2, true)
        val event = RailTopologyChange(initial, changed, setOf(a, b, branch))
        runtime.topologyGuard.invalidate(event)
        epochs.invalidateTopology(event)
        epochs.process(frame(2, changed, a))
        val base = runtime.authoringPublication()
        val captured = runtime.topologyGuard.capture()
        val candidate = SectionTopologyRevalidation.compile(base, captured, 1000.0)
        val replacement = SectionOccupancyRuntime.from(candidate)
        replacement.retainPublishedFrom(occupancy)
        epochs.replaceSourcesAtomically(replacement.sources) {
            assertTrue(runtime.commitTopologyRevalidation(base, captured, candidate, replacement))
        }
        epochs.process(frame(3, changed, a))
        val retained = replacement.snapshot()!!.sections.getValue(section)
        assertEquals(setOf("train"), retained.occupants)
        assertFalse(retained.available)
        assertTrue(replacement.snapshot()!!.crossingsBySection[section].isNullOrEmpty())
        assertEquals(listOf(table), runtime.currentDefinitions().routeTables)
    }

    @Test fun `disjoint consecutive source swaps preserve signal-only pending baseline from the first swap`() {
        val continuations = mapOf(
            endpoint(a, RailEndpoint.B) to setOf(endpoint(branch, RailEndpoint.A)),
            endpoint(branch, RailEndpoint.A) to setOf(endpoint(a, RailEndpoint.B)),
            endpoint(branch, RailEndpoint.B) to setOf(endpoint(b, RailEndpoint.A)),
            endpoint(b, RailEndpoint.A) to setOf(endpoint(branch, RailEndpoint.B)))
        fun root(revision: Long, connectorLength: Double, otherLength: Double) =
            work.art1st.javarailways.core.physics.rail.topology.buildTestTopology(revision,
                listOf(a to 10.0, b to 10.0, branch to connectorLength, unrelated to otherLength).map { (id, length) ->
                    work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail(id, length)
                }, continuations)
        val initial = root(1, 10.0, 10.0)
        val main = block("test:main", a)
        val downstream = block("test:downstream", b)
        val other = block("test:other", unrelated)
        val upstreamSignal = FixedBlockSignalDefinition("test:upstream", 0, main.blockId, 2.0,
            "automatic:block")
        val runtime = SimpleSignalsWorldRuntime(worldId)
        val installed = runtime.initializeDefinitions(initial, SimpleSignalsWorldDefinitions(worldId, 1,
            emptyList(), emptyList(), listOf(main, downstream, other),
            listOf(upstreamSignal, signal(downstream), signal(other))))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, installed.status, installed.diagnostics.toString())
        val occupancy = runtime.initializeSectionOccupancy()
        val epochs = SignalEpochRuntime(worldId.toString(), { value ->
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, value.revision)
        })
        epochs.replaceSources(occupancy.sources)
        epochs.process(frame(1, initial))
        val mainArea = runtime.fixedBlockSnapshot().blocksById.getValue(main.blockId).occupancyArea
        fun swap(previous: TopologyState, next: TopologyState, rail: UUID) {
            val event = RailTopologyChange(previous, next, setOf(rail))
            runtime.topologyGuard.invalidate(event)
            epochs.invalidateTopology(event)
            val base = runtime.authoringPublication()
            val captured = runtime.topologyGuard.capture()
            if (rail == branch) assertEquals(setOf(TopologySection(TopologySectionKind.SIGNAL, upstreamSignal.signalId)),
                captured.pending.keys, "connector geometry changes signal distance, not either monitored block")
            val candidate = SectionTopologyRevalidation.compile(base, captured, 1000.0)
            val replacement = SectionOccupancyRuntime.from(candidate)
            replacement.retainPublishedFrom(runtime.currentSectionOccupancy())
            epochs.replaceSourcesAtomically(replacement.sources) {
                assertTrue(runtime.commitTopologyRevalidation(base, captured, candidate, replacement))
            }
        }
        val connectorChanged = root(2, 12.0, 10.0)
        swap(initial, connectorChanged, branch)
        val unrelatedChanged = root(3, 12.0, 12.0)
        swap(connectorChanged, unrelatedChanged, unrelated) // No signal callback between the two swaps.
        val replacement = runtime.currentSectionOccupancy()!!
        assertSame(mainArea, runtime.fixedBlockSnapshot().blocksById.getValue(main.blockId).occupancyArea)
        assertEquals(0.0, replacement.signalById(upstreamSignal.signalId)!!.state.effectiveSpeedLimitMetersPerSecond)
        epochs.process(frame(2, unrelatedChanged))
        assertTrue(upstreamSignal.signalId in replacement.signalSnapshot()!!.changedSignalIds,
            "the first swap's signal-only pending baseline must still reevaluate after the second swap")
        assertEquals(0.0, replacement.signalById(upstreamSignal.signalId)!!.state.effectiveSpeedLimitMetersPerSecond)
    }

    @Test fun `disjoint bulk transaction does not add unrelated new rails to broken region`() {
        val initial = topology(1)
        val runtime = runtime(initial, regions = listOf(region()))
        val changed = topology(2, true, listOf(unrelated, otherNew))
        runtime.topologyGuard.invalidate(RailTopologyChange(initial, changed, setOf(a, b, branch, unrelated, otherNew)))
        runtime.topologyGuard.requireUsable(route(changed, otherNew), 10.0)
        refresh(runtime)
        runtime.topologyGuard.requireUsable(route(changed, otherNew), 10.0)
    }

    @Test fun `overlap neighbour retains cause rail dependency and recovers when remote connection is removed`() {
        val connections = line + listOf(endpoint(branch, RailEndpoint.B) to endpoint(unrelated, RailEndpoint.A),
            endpoint(unrelated, RailEndpoint.B) to endpoint(otherNew, RailEndpoint.A))
        val rails = listOf(a, b, branch, unrelated, otherNew)
        val initial = topologyState(1, rails, connections)
        val neighbour = ProtectionRegionDefinition("test:neighbour", 0, listOf(
            ProtectionBoundaryDefinition("test:neighbour-entry", RailPositionSnapshot(unrelated, ViewDirection.AB, 2.0)),
            ProtectionBoundaryDefinition("test:neighbour-exit", RailPositionSnapshot(otherNew, ViewDirection.BA, 8.0))))
        val runtime = runtime(initial, regions = listOf(region(), neighbour))
        val changed = topologyState(2, rails,
            connections + (endpoint(a, RailEndpoint.B) to endpoint(branch, RailEndpoint.A)))
        runtime.topologyGuard.invalidate(RailTopologyChange(initial, changed, setOf(a, b, branch)))
        refresh(runtime)
        assertEquals(setOf(region().regionId, neighbour.regionId), runtime.protectionRegionSnapshot().unavailableRegionIds)
        val repaired = topologyState(3, rails, connections)
        runtime.topologyGuard.invalidate(RailTopologyChange(changed, repaired, setOf(a, b, branch)))
        assertTrue(TopologySection(TopologySectionKind.REGION, neighbour.regionId) in runtime.topologyGuard.capture().pending)
        refresh(runtime)
        assertTrue(runtime.protectionRegionSnapshot().unavailableRegionIds.isEmpty())
    }

    @Test fun `unrelated later edit does not discard completed local worker`() {
        val initial = topology(1)
        val runtime = runtime(initial, listOf(block("test:main", a, b)))
        val changed = topology(2, true)
        runtime.topologyGuard.invalidate(RailTopologyChange(initial, changed, setOf(a, b, branch)))
        val base = runtime.authoringPublication()
        val pending = runtime.topologyGuard.capture()
        val candidate = SectionTopologyRevalidation.compile(base, pending, 1000.0)
        val unrelatedChange = topology(3, true, listOf(unrelated, otherNew))
        runtime.topologyGuard.invalidate(RailTopologyChange(changed, unrelatedChange, setOf(otherNew)))
        assertTrue(runtime.commitTopologyRevalidation(base, pending, candidate, SectionOccupancyRuntime.from(candidate)))
        assertSame(unrelatedChange, runtime.topologyGuard.currentTopology())
    }

    @Test fun `related edit and unload prevent old worker publication`() {
        val initial = topology(1)
        val runtime = runtime(initial, listOf(block("test:main", a, b)))
        val changed = topology(2, true)
        runtime.topologyGuard.invalidate(RailTopologyChange(initial, changed, setOf(a, b, branch)))
        val base = runtime.authoringPublication()
        val pending = runtime.topologyGuard.capture()
        val candidate = SectionTopologyRevalidation.compile(base, pending, 1000.0)
        val repaired = topology(3)
        runtime.topologyGuard.invalidate(RailTopologyChange(changed, repaired, setOf(a, b, branch)))
        assertFalse(runtime.commitTopologyRevalidation(base, pending, candidate, SectionOccupancyRuntime.from(candidate)))
        runtime.retire()
        assertFalse(runtime.commitTopologyRevalidation(base, runtime.topologyGuard.capture(), candidate, SectionOccupancyRuntime.from(candidate)))
        assertSame(base, runtime.authoringPublication())
    }

    @Test fun `ordinary definition editing preserves unrelated invalid section and can repair its boundaries`() {
        val initial = topology(1)
        val original = region()
        val runtime = runtime(initial, regions = listOf(original))
        val changed = topology(2, true)
        runtime.topologyGuard.invalidate(RailTopologyChange(initial, changed, setOf(a, b, branch)))
        refresh(runtime)
        val fixed = ProtectionRegionDefinition(original.regionId, 1, original.boundaries +
            ProtectionBoundaryDefinition("test:side-exit", RailPositionSnapshot(branch, ViewDirection.BA, 8.0)))
        val repaired = runtime.prepareDefinitions(changed, SimpleSignalsWorldDefinitions(worldId, 2, listOf(fixed), emptyList()))
        assertTrue(repaired is SimpleSignalsDefinitionPreparationResult.Prepared, repaired.toString())
        val unrelatedEdit = runtime.prepareDefinitions(changed,
            SimpleSignalsWorldDefinitions(worldId, 2, listOf(original), emptyList(), listOf(block("test:other", unrelated))))
        assertTrue(unrelatedEdit is SimpleSignalsDefinitionPreparationResult.Prepared, unrelatedEdit.toString())
    }
}
