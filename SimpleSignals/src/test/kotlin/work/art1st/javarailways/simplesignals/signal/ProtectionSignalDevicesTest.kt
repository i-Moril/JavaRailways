package work.art1st.javarailways.simplesignals.signal

import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime
import work.art1st.javarailways.simplesignals.testing.protectionTestEpoch
import work.art1st.javarailways.simplesignals.testing.TestProtectionEpoch

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureOwner
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.*
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.simpleato.api.restriction.*
import work.art1st.javarailways.simpleato.api.inspection.InspectionBindingInput
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.persistence.*
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.runtime.*
import java.util.UUID

class ProtectionSignalDevicesTest {
    private val worldId = UUID(0, 1000)
    private val trunk = UUID(0, 1001)
    private val first = UUID(0, 1002)
    private val second = UUID(0, 1003)
    private val topology = topologyState(7, listOf(trunk, first, second), listOf(
        endpoint(trunk, RailEndpoint.B) to endpoint(first, RailEndpoint.A),
        endpoint(trunk, RailEndpoint.B) to endpoint(second, RailEndpoint.A)))
    private val region = ProtectionRegionDefinition("test:region", 1, listOf(
        boundary("test:entry", trunk, 5.0, ViewDirection.AB),
        boundary("test:first-exit", first, 5.0, ViewDirection.BA),
        boundary("test:second-exit", second, 5.0, ViewDirection.BA)))
    private val routeA = authoredRoute("test:a", first, "test:first-exit")
    private val routeB = authoredRoute("test:b", second, "test:second-exit")
    private val fixed = signal("test:fixed", ProtectionSignalKind.FIXED_ROUTE, routeA.routeId)
    private val current = signal("test:current", ProtectionSignalKind.CURRENT_ENTRY, "test:entry")
    private val owner = InfrastructureOwner("test:owner", 1)
    private val indicators = listOf(indicator("test:near", 2.0), indicator("test:distant", 0.0))

    @Test
    fun `repair revalidates each retained route and leaves invalid devices unavailable across codec reload`() {
        val branchSignal = signal("test:branch-b", ProtectionSignalKind.FIXED_ROUTE, routeB.routeId)
        val initial = definitions(listOf(fixed, current, branchSignal))
        val world = signalWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, world.initializeDefinitions(topology, initial).status)
        fun install(revision: Long, nextRegion: ProtectionRegionDefinition) {
            val value = SimpleSignalsWorldDefinitions(worldId, revision, listOf(nextRegion), initial.routeTables,
                protectionSignals = initial.protectionSignals, routeIndicators = initial.routeIndicators)
            val prepared = world.prepareDefinitions(topology, value) as SimpleSignalsDefinitionPreparationResult.Prepared
            world.commitPrepared(prepared.value)
        }
        install(2, ProtectionRegionDefinition(region.regionId, 2, region.boundaries,
            region.boundaries.filter { it.boundaryId != "test:second-exit" }))
        assertTrue(world.authoringPublication().routeSelection.routesById.isEmpty())
        val repairedCuts = region.boundaries.map { if (it.boundaryId == "test:second-exit")
            boundary(it.boundaryId, second, 6.0, ViewDirection.BA) else it }
        install(3, ProtectionRegionDefinition(region.regionId, 3, repairedCuts))
        assertEquals(setOf(routeA.routeId), world.authoringPublication().routeSelection.routesById.keys)
        assertEquals(initial.routeTables, world.currentDefinitions().routeTables)
        assertEquals(initial.protectionSignals.sortedBy { it.signalId }, world.currentDefinitions().protectionSignals)
        val decoded = SimpleSignalsWorldDefinitionsCodec.decode(SimpleSignalsWorldDefinitionsCodec.encode(world.currentDefinitions()), worldId)
        val reloaded = signalWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, reloaded.initializeDefinitions(topology, decoded).status)
        val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        })
        epoch.replaceSources(reloaded.initializeSectionOccupancy().sources)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(mission(first), 20.0, 20.0)))
        epoch.process(frame(1, first))
        assertEquals(30.0, speed(reloaded, fixed.signalId))
        assertEquals(30.0, speed(reloaded, current.signalId))
        assertInstanceOf(SectionSignalState.Unavailable::class.java, reloaded.sectionSignalSnapshot()!!.states.getValue(branchSignal.signalId))
        assertTrue(reloaded.protectionRegionInspectionSource.routeInfrastructureDependencies.dependencies(mission(first)).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            reloaded.protectionRegionInspectionSource.routeInfrastructureDependencies.dependencies(mission(second))
        }
    }

    @Test
    fun `aligned protection devices wait for their request floor and end with no route`() {
        val f = runtime()
        val route = mission(first)
        f.epoch.process(frame(1, first))
        fun speeds(expected: Double) {
            assertEquals(expected, speed(f.world, fixed.signalId))
            assertEquals(expected, speed(f.world, current.signalId))
        }
        speeds(0.0)
        assertTrue(f.world.routeIndicatorStates().values.all { it.selection.source == ProtectionRouteSelectionSource.NONE })
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 20.0)))
        f.epoch.process(frame(2, first))
        speeds(0.0)
        assertTrue(f.world.routeIndicatorStates().values.all {
            it.selection.routeId == routeA.routeId && !it.selection.routeReady })
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 20.0, 14.0)))
        f.epoch.process(frame(3, first))
        speeds(0.0)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 16.0, 16.0)))
        f.epoch.process(frame(4, first))
        speeds(30.0)
        val granted = f.world.protectionRouteSelectionSnapshot()
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 20.0, 16.0)))
        f.epoch.process(frame(5, first))
        speeds(30.0)
        assertSame(granted, f.world.protectionRouteSelectionSnapshot(), "farther preparation retains the covered region")
        f.epoch.process(frame(6, second))
        speeds(0.0)
        f.epoch.process(frame(7, first))
        speeds(30.0)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, null))
        f.epoch.process(frame(8, first))
        speeds(0.0)
        assertTrue(f.world.routeIndicatorStates().values.all {
            it.selection.source == ProtectionRouteSelectionSource.NONE && it.selection.routeId == null &&
                it.modelVariant == "opaque/none" })
    }

    @Test
    fun `only the requesting train tail exit removes both signal readiness and indicator identity`() {
        val f = runtime()
        fun physical(epoch: Long, tail: Double, left: Boolean = false): RailwayWorldFrameSnapshot {
            val head = RailPositionSnapshot(first, ViewDirection.AB, 6.0)
            val rear = RailPositionSnapshot(first, ViewDirection.AB, tail)
            return RailwayWorldFrameSnapshot.create(epoch, topology.revision, listOf(TrainFrameSnapshot.create(
                "train", "Train", epoch, epoch, 0.0, false, false, emptyList(), head, rear,
                listOf(RailInterval(first, tail, 6.0)), emptyList(), emptyList(), emptyList(),
                if (left) listOf(RailTraversalSegmentSnapshot(first, ViewDirection.AB, 4.0, 6.0)) else emptyList()
            )), ActiveRailContinuationsSnapshot.create(topology.revision, epoch, mapOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(first, RailEndpoint.A),
                endpoint(first, RailEndpoint.A) to endpoint(trunk, RailEndpoint.B))))
        }
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(mission(first), 20.0, 20.0)))
        f.epoch.process(physical(1, 4.0))
        assertTrue(f.world.routeIndicatorStates().values.all {
            it.selection.routeId == routeA.routeId && it.selection.routeReady })
        f.epoch.process(physical(2, 4.0))
        assertEquals(ProtectionRouteSelectionSource.REQUEST,
            f.world.protectionRouteSelectionSnapshot()!!.selections.getValue("test:entry").source)
        f.epoch.process(physical(3, 6.0, left = true))
        assertEquals(0.0, speed(f.world, fixed.signalId))
        assertEquals(0.0, speed(f.world, current.signalId))
        assertTrue(f.world.routeIndicatorStates().values.all {
            it.selection.source == ProtectionRouteSelectionSource.NONE && it.selection.routeId == null &&
                it.modelVariant == "opaque/none" })
        assertEquals(1, f.world.sectionOccupancySnapshot()!!.crossingsBySection.getValue(
            SectionRef(SectionKind.PROTECTION_ROUTE, routeA.routeId)).size)
    }

    @Test
    fun `shared-prefix routes compile and fixed binding remains independent during requested selection`() {
        val f = runtime()
        f.epoch.process(frame(1, first))
        assertEquals(0.0, speed(f.world, fixed.signalId))
        assertEquals(0.0, speed(f.world, current.signalId))
        assertTrue(f.world.routeIndicatorStates().values.all { it.modelVariant == "opaque/none" })
        val frozen = mission(second)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0)))
        f.epoch.process(frame(2, first))
        assertEquals(0.0, speed(f.world, fixed.signalId), "a request for B must not open aligned route A")
        assertEquals(0.0, speed(f.world, current.signalId))
        assertEquals("private/no-route", output(f.world, current.signalId).modelVariant)
        f.world.routeIndicatorStates().values.forEach {
            assertEquals(routeB.routeId, it.selection.routeId)
            assertEquals("opaque/b", it.modelVariant)
            assertFalse(it.selection.routeReady)
        }
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 16.0)))
        f.epoch.process(frame(3, second))
        assertEquals(30.0, speed(f.world, current.signalId))
        assertEquals(0.0, speed(f.world, fixed.signalId))
        assertTrue(f.world.routeIndicatorStates().values.all { it.selection.routeReady })
        assertTrue(f.invalidations.last().contains(protectionSignalDeclarationId(current.signalId)))
        val same = f.world.sectionSignalSnapshot()
        val sameIndicators = f.world.routeIndicatorStates()
        f.epoch.process(frame(4, second))
        assertSame(same, f.world.sectionSignalSnapshot())
        assertSame(sameIndicators, f.world.routeIndicatorStates())
    }

    @Test
    fun `fixed readiness requires a unique matching owner and recovers after ambiguity ends`() {
        val f = runtime()
        val matching = admission(mission(first), 20.0, 20.0)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, matching))
        f.epoch.process(frame(1, first))
        assertEquals(30.0, speed(f.world, fixed.signalId))
        val other = InfrastructureOwner("test:other", 1)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(other,
            matching.copy(owner = other)))
        f.epoch.process(frame(2, first))
        assertEquals(0.0, speed(f.world, fixed.signalId), "ambiguous owners must stop the fixed route")
        assertEquals(0.0, speed(f.world, current.signalId))
        assertTrue(f.world.routeIndicatorStates().values.all { !it.selection.available })
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(other, null))
        f.epoch.process(frame(3, first))
        assertEquals(30.0, speed(f.world, fixed.signalId))
        assertEquals(30.0, speed(f.world, current.signalId))
    }

    @Test
    fun `inactive route occupancy and unrelated selection do not execute injected rules`() {
        val calls = hashMapOf<String, Int>()
        val f = runtime(calls = calls)
        f.epoch.process(frame(1, first))
        assertNull(calls[fixed.signalId])
        assertNull(calls[current.signalId])
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(mission(second), 20.0)))
        f.epoch.process(frame(2, first))
        assertNull(calls[fixed.signalId], "another requested route cannot execute a permissive fixed rule")
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(mission(second), 20.0, 20.0)))
        f.epoch.process(frame(3, second))
        val before = calls[current.signalId]
        val indicatorsBefore = f.world.routeIndicatorStates()
        f.epoch.process(frame(4, second, listOf(RailInterval(first, 2.0, 3.0))))
        assertEquals(before, calls[current.signalId], "current rule must not read inactive route A")
        assertSame(indicatorsBefore, f.world.routeIndicatorStates())
        f.epoch.process(frame(5, second, listOf(RailInterval(second, 2.0, 3.0))))
        assertEquals(before!! + 1, calls[current.signalId])
        assertEquals(3.0, speed(f.world, current.signalId))
        assertEquals("private/occupied", output(f.world, current.signalId).modelVariant)
    }

    @Test
    fun `no request stale admission and multiple owners remain stop`() {
        val f = runtime()
        f.epoch.process(frame(1, second))
        assertEquals(0.0, speed(f.world, current.signalId))
        assertTrue(f.world.routeIndicatorStates().values.all { it.modelVariant == "opaque/none" })
        val frozen = mission(second)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(frozen, 20.0, 20.0)))
        val other = InfrastructureOwner("test:other", 1)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(other,
            admission(frozen, 20.0, 20.0).copy(owner = other)))
        f.epoch.process(frame(2, second))
        assertEquals(0.0, speed(f.world, current.signalId))
        assertTrue(f.world.routeIndicatorStates().values.all { !it.selection.available })
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(other, null))
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner,
            admission(frozen, 20.0, 20.0).copy(trainId = "missing")))
        f.epoch.process(frame(3, second))
        assertEquals(0.0, speed(f.world, current.signalId))
    }

    @Test
    fun `selected branch reads only its configured downstream fixed block and unchanged output stops wave`() {
        val blockA = FixedBlockDefinition("test:block-a", 1, listOf(FixedBlockTraversal(first, ViewDirection.AB, 5.0, 10.0)))
        val blockB = FixedBlockDefinition("test:block-b", 1, listOf(FixedBlockTraversal(second, ViewDirection.AB, 5.0, 10.0)))
        val signalA = fixedBlockSignal("test:end-a", blockA.blockId)
        val signalB = fixedBlockSignal("test:end-b", blockB.blockId)
        val dependent = signal(current.signalId, current.kind, current.bindingId, downstreamByRoute = mapOf(
            routeA.routeId to listOf(FixedBlockSignalDependency(signalA.signalId)),
            routeB.routeId to listOf(FixedBlockSignalDependency(signalB.signalId))))
        val calls = hashMapOf<String, Int>()
        val f = runtime(listOf(dependent), listOf(blockA, blockB), listOf(signalA, signalB), calls)
        f.epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(mission(first), 20.0, 20.0)))
        f.epoch.process(frame(1, first))
        assertEquals(30.0, speed(f.world, current.signalId))
        val before = calls[current.signalId]
        f.epoch.process(frame(2, first, listOf(RailInterval(second, 7.0, 8.0))))
        assertEquals(before, calls[current.signalId])
        assertEquals(30.0, speed(f.world, current.signalId))
        f.epoch.process(frame(3, first, listOf(RailInterval(first, 7.0, 8.0))))
        assertEquals(before!! + 1, calls[current.signalId])
        assertEquals(8.0, speed(f.world, current.signalId))
        f.epoch.process(frame(4, first, listOf(RailInterval(first, 8.0, 9.0))))
        assertEquals(before + 1, calls[current.signalId], "same fixed-block output does not propagate")
    }

    @Test
    fun `current schema fixture defaults and full device round trip are strict`() {
        val fixture = """{"schemaVersion":2,"worldId":"$worldId","definitionRevision":0,"protectionRegions":[],"protectionRouteTables":[]}"""
        val empty = SimpleSignalsWorldDefinitionsCodec.decode(fixture, worldId)
        assertTrue(empty.protectionSignals.isEmpty())
        assertTrue(empty.routeIndicators.isEmpty())
        val definitions = definitions()
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(definitions)
        assertEquals(definitions, SimpleSignalsWorldDefinitionsCodec.decode(encoded, worldId))
        assertTrue(encoded.contains("\"schemaVersion\": 2"))
        assertThrows(IllegalArgumentException::class.java) {
            SimpleSignalsWorldDefinitionsCodec.decode(encoded.replace("FIXED_ROUTE", "UNKNOWN"), worldId)
        }
    }

    @Test
    fun `unknown references reject whole candidate and preserve established runtime`() {
        val f = runtime()
        val old = f.world.currentDefinitions()
        val rejected = f.world.prepareDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 2,
            old.regions, old.routeTables, protectionSignals = listOf(signal(current.signalId, current.kind,
                "test:missing", revision = 2))))
        assertInstanceOf(SimpleSignalsDefinitionPreparationResult.Rejected::class.java, rejected)
        assertSame(old, f.world.currentDefinitions())
        val original = indicators.first()
        val rejectedIndicator = f.world.prepareDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 2,
            old.regions, old.routeTables, routeIndicators = listOf(RouteIndicatorDefinition(original.indicatorId,
                2, "test:unknown", original.noRouteModelVariant, original.modelVariantsByRouteId, original.model))))
        assertInstanceOf(SimpleSignalsDefinitionPreparationResult.Rejected::class.java, rejectedIndicator)
        assertSame(old, f.world.currentDefinitions())
    }

    @Test
    fun `typed inspection identities keep equal authored block and device IDs distinct`() {
        val block = FixedBlockDefinition("test:equal", 1, listOf(FixedBlockTraversal(first, ViewDirection.AB, 5.0, 10.0)))
        val f = runtime(listOf(signal(block.blockId, ProtectionSignalKind.CURRENT_ENTRY, "test:entry")),
            listOf(block), listOf(fixedBlockSignal("test:block-signal", block.blockId)))
        val binding = f.world.fixedBlockInspectionSource.bindingProvider.bind(
            InspectionBindingInput(RailwayStateStamp(worldId.toString(), 1, topology.revision), "train", mission(first)))
        assertEquals(setOf(fixedBlockSignalDeclarationId(block.blockId), protectionSignalDeclarationId(block.blockId)),
            binding.declarations.map { it.declarationId }.toSet())
        assertEquals(5.0, binding.declarations.single { it.declarationId == protectionSignalDeclarationId(block.blockId) }
            .triggerRouteDistanceMeters)
        assertEquals(20.0, binding.declarations.single { it.declarationId == protectionSignalDeclarationId(block.blockId) }
            .minimumRequestedAuthorityEndRouteDistanceMeters)
    }

    @Test
    fun `BA route occupancy and declaration follow the authored facing`() {
        val backward = ProtectionRouteDefinition("test:backward", "test:first-exit", "test:entry", listOf(
            ProtectionRouteTraversal(first, ViewDirection.BA, 0.0, 5.0),
            ProtectionRouteTraversal(trunk, ViewDirection.BA, 5.0, 10.0)))
        val fixedBackward = signal("test:reverse-signal", ProtectionSignalKind.FIXED_ROUTE, backward.routeId)
        val world = signalWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            world.initializeDefinitions(topology, SimpleSignalsWorldDefinitions(worldId, 1, listOf(region), listOf(
                ProtectionRouteTableDefinition("test:table", 1, region.regionId, 1, listOf(routeA, routeB, backward))),
                protectionSignals = listOf(fixedBackward))).status)
        val occupancy = world.initializeSectionOccupancy()
        val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        })
        epoch.replaceSources(occupancy.sources)
        val route = RouteSnapshot("test:reverse-mission", 1, topology.revision, listOf(
            RouteSegmentSnapshot(first, ViewDirection.BA, 0.0, 10.0),
            RouteSegmentSnapshot(trunk, ViewDirection.BA, 0.0, 10.0)))
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 20.0, 20.0)))
        epoch.process(frame(1, first))
        assertEquals(30.0, speed(world, fixedBackward.signalId))
        epoch.process(frame(2, first, listOf(RailInterval(first, 2.0, 3.0))))
        assertEquals(3.0, speed(world, fixedBackward.signalId))
        val declaration = world.fixedBlockInspectionSource.bindingProvider.bind(
            InspectionBindingInput(RailwayStateStamp(worldId.toString(), 2, topology.revision), "train", route))
            .declarations.single()
        assertEquals(ViewDirection.BA, declaration.facing)
        assertEquals(first, declaration.railId)
        assertEquals(5.0, declaration.triggerRouteDistanceMeters)
    }

    @Test
    fun `rail-disjoint routes in one connected throat have independent occupancy`() {
        val rails = (11L..17L).map { UUID(0, it) }
        val (upperEntry, upperCore, upperExit, lowerEntry, lowerCore) = rails
        val lowerExit = rails[5]
        val connector = rails[6]
        val connected = topologyState(42, rails, listOf(
            endpoint(upperEntry, RailEndpoint.B) to endpoint(upperCore, RailEndpoint.A),
            endpoint(upperCore, RailEndpoint.B) to endpoint(upperExit, RailEndpoint.A),
            endpoint(upperCore, RailEndpoint.B) to endpoint(connector, RailEndpoint.A),
            endpoint(lowerEntry, RailEndpoint.B) to endpoint(lowerCore, RailEndpoint.A),
            endpoint(lowerCore, RailEndpoint.B) to endpoint(lowerExit, RailEndpoint.A),
            endpoint(lowerCore, RailEndpoint.B) to endpoint(connector, RailEndpoint.B)))
        val throat = ProtectionRegionDefinition("test:disjoint", 1, listOf(
            boundary("test:upper-entry", upperEntry, 5.0, ViewDirection.AB),
            boundary("test:upper-exit", upperExit, 5.0, ViewDirection.BA),
            boundary("test:lower-entry", lowerEntry, 5.0, ViewDirection.AB),
            boundary("test:lower-exit", lowerExit, 5.0, ViewDirection.BA)))
        fun path(id: String, entry: String, exit: String, entryRail: UUID, core: UUID, exitRail: UUID) =
            ProtectionRouteDefinition(id, entry, exit, listOf(
                ProtectionRouteTraversal(entryRail, ViewDirection.AB, 5.0, 10.0),
                ProtectionRouteTraversal(core, ViewDirection.AB, 0.0, 10.0),
                ProtectionRouteTraversal(exitRail, ViewDirection.AB, 0.0, 5.0)))
        val upper = path("test:upper", "test:upper-entry", "test:upper-exit", upperEntry, upperCore, upperExit)
        val lower = path("test:lower", "test:lower-entry", "test:lower-exit", lowerEntry, lowerCore, lowerExit)
        val upperSignal = signal("test:upper-signal", ProtectionSignalKind.CURRENT_ENTRY, upper.entryBoundaryId)
        val lowerSignal = signal("test:lower-signal", ProtectionSignalKind.CURRENT_ENTRY, lower.entryBoundaryId)
        val world = signalWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            world.initializeDefinitions(connected, SimpleSignalsWorldDefinitions(worldId, 1, listOf(throat), listOf(
                ProtectionRouteTableDefinition("test:table", 1, throat.regionId, 1, listOf(upper, lower))), protectionSignals = listOf(upperSignal, lowerSignal))).status)
        val occupancy = world.initializeSectionOccupancy()
        val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        })
        epoch.replaceSources(occupancy.sources)
        val upperMission = RouteSnapshot("test:upper-mission", 1, connected.revision,
            listOf(upperEntry, upperCore, upperExit).map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
        val lowerMission = RouteSnapshot("test:lower-mission", 1, connected.revision,
            listOf(lowerEntry, lowerCore, lowerExit).map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(upperMission, 30.0, 30.0)))
        val lowerOwner = InfrastructureOwner("test:lower-owner", 1)
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(lowerOwner,
            admission(lowerMission, 30.0, 30.0).copy(owner = lowerOwner, trainId = "lower-train")))
        val position = RailPositionSnapshot(upperCore, ViewDirection.AB, 2.0)
        val physical = RailwayWorldFrameSnapshot.create(1, connected.revision, listOf(TrainFrameSnapshot.create(
            "train", "Train", 1, 1, 0.0, false, false, emptyList(), position, position,
            listOf(RailInterval(upperCore, 2.0, 3.0)), emptyList(), emptyList(), emptyList(), emptyList()),
            TrainFrameSnapshot.create("lower-train", "Lower", 1, 1, 0.0, false, false, emptyList(),
                RailPositionSnapshot(lowerEntry, ViewDirection.AB, 0.0), RailPositionSnapshot(lowerEntry, ViewDirection.AB, 0.0),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList())),
            ActiveRailContinuationsSnapshot.create(connected.revision, 1, mapOf(
                endpoint(upperCore, RailEndpoint.B) to endpoint(upperExit, RailEndpoint.A),
                endpoint(lowerCore, RailEndpoint.B) to endpoint(lowerExit, RailEndpoint.A))))
        epoch.process(physical)
        assertEquals(3.0, speed(world, upperSignal.signalId))
        assertEquals(30.0, speed(world, lowerSignal.signalId))
        assertEquals(setOf("train"), world.sectionOccupancySnapshot()!!.sections.getValue(
            SectionRef(SectionKind.PROTECTION_ROUTE, upper.routeId)).occupants)
        assertTrue(world.sectionOccupancySnapshot()!!.sections.getValue(
            SectionRef(SectionKind.PROTECTION_ROUTE, lower.routeId)).occupants.isEmpty())
    }

    @Test
    fun `last configured downstream path becoming inactive cannot retain permissive output`() {
        val next = UUID(0, 1020)
        val alternative = UUID(0, 1021)
        val expanded = topologyState(topology.revision, listOf(trunk, first, second, next, alternative), listOf(
            endpoint(trunk, RailEndpoint.B) to endpoint(first, RailEndpoint.A),
            endpoint(trunk, RailEndpoint.B) to endpoint(second, RailEndpoint.A),
            endpoint(first, RailEndpoint.B) to endpoint(next, RailEndpoint.A),
            endpoint(first, RailEndpoint.B) to endpoint(alternative, RailEndpoint.A)))
        val block = FixedBlockDefinition("test:next", 1, listOf(FixedBlockTraversal(next, ViewDirection.AB, 0.0, 10.0)))
        val downstream = fixedBlockSignal("test:downstream", block.blockId)
        val dependent = signal(current.signalId, current.kind, current.bindingId, downstreamByRoute = mapOf(
            routeA.routeId to listOf(FixedBlockSignalDependency(downstream.signalId, listOf(
                FixedBlockSignalContinuationRequirement(endpoint(first, RailEndpoint.B), endpoint(next, RailEndpoint.A))))),
            routeB.routeId to emptyList()))
        val world = signalWorldRuntime(worldId)
        val result = world.initializeDefinitions(expanded, definitions(listOf(dependent), listOf(block), listOf(downstream)))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.toString())
        val occupancy = world.initializeSectionOccupancy()
        val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        })
        epoch.replaceSources(occupancy.sources)
        val route = RouteSnapshot("test:next-mission", 1, expanded.revision,
            listOf(trunk, first, next).map { RouteSegmentSnapshot(it, ViewDirection.AB, 0.0, 10.0) })
        epoch.publishAdmissionUpdate(ActiveRouteAdmissionUpdate(owner, admission(route, 30.0, 30.0)))
        epoch.process(frame(1, first, additionalSelections = mapOf(
            endpoint(first, RailEndpoint.B) to endpoint(next, RailEndpoint.A))))
        assertEquals(30.0, speed(world, current.signalId))
        epoch.process(frame(2, first, additionalSelections = mapOf(
            endpoint(first, RailEndpoint.B) to endpoint(alternative, RailEndpoint.A))))
        assertEquals(0.0, speed(world, current.signalId))
        assertInstanceOf(SectionSignalState.Unavailable::class.java,
            world.sectionSignalSnapshot()!!.states.getValue(current.signalId))
        assertTrue(world.protectionRouteSelectionSnapshot()!!.selections.getValue("test:entry").routeReady)
    }

    private data class Fixture(val world: SimpleSignalsWorldRuntime, val epoch: TestProtectionEpoch,
        val invalidations: MutableList<Set<String>>)

    private fun runtime(signals: List<ProtectionSignalDefinition> = listOf(fixed, current),
        blocks: List<FixedBlockDefinition> = emptyList(), fixedSignals: List<FixedBlockSignalDefinition> = emptyList(),
        calls: MutableMap<String, Int>? = null): Fixture {
        val world = signalWorldRuntime(worldId)
        val prepared = world.prepareDefinitions(topology, definitions(signals, blocks, fixedSignals))
            as SimpleSignalsDefinitionPreparationResult.Prepared
        world.commitPrepared(prepared.value)
        val occupancy = if (calls == null) world.initializeSectionOccupancy() else {
            val publication = prepared.value.candidatePublication
            val bindings = (publication.fixedBlockSignals.bindings + publication.protectionSignals.bindings).map { binding ->
                SectionSignalRuleBinding(binding.signalId, binding.controlledSection, binding.downstreamSignals,
                    binding.observedSections, binding.entryBoundaryId, binding.preparedRoutesBySection,
                    binding.declarationId, binding.continuationEntries, binding.unreadyOutput) { input ->
                    calls[binding.signalId] = calls.getOrDefault(binding.signalId, 0) + 1
                    if (binding.preparedRoutesBySection.isNotEmpty()) assertNotNull(input.preparedRoute)
                    binding.rule.evaluate(input)
                }
            }
            world.initializeSectionOccupancy(bindings)
        }
        val invalidations = arrayListOf<Set<String>>()
        val epoch = protectionTestEpoch(world, worldId.toString(), TrainRestrictionPublisher {
            TrainRestrictionPublicationResult(TrainRestrictionPublicationStatus.ACCEPTED, it.revision)
        }, invalidateInspectionDeclarations = { source, ids ->
            assertEquals(FIXED_BLOCK_SIGNAL_SOURCE_ID, source)
            invalidations += ids
        })
        epoch.replaceSources(occupancy.sources)
        return Fixture(world, epoch, invalidations)
    }

    private fun definitions(signals: List<ProtectionSignalDefinition> = listOf(fixed, current),
        blocks: List<FixedBlockDefinition> = emptyList(), fixedSignals: List<FixedBlockSignalDefinition> = emptyList()) =
        SimpleSignalsWorldDefinitions(worldId, 1, listOf(region), listOf(ProtectionRouteTableDefinition(
            "test:table", 1, region.regionId, 1, listOf(routeA, routeB))),
            blocks, fixedSignals, signals, indicators)

    private fun signal(id: String, kind: ProtectionSignalKind, binding: String, revision: Long = 1,
        downstreamByRoute: Map<String, List<FixedBlockSignalDependency>> = emptyMap()) = ProtectionSignalDefinition(
        id, revision, kind, binding, 5.0, ConfiguredSignalOutput(0.0, "private/no-route"),
        ConfiguredSignalOutput(3.0, "private/occupied"), levels(), downstreamSignalsByRouteId = downstreamByRoute)
    private fun fixedBlockSignal(id: String, block: String) = FixedBlockSignalDefinition(id, 1, block, 0.0,
        ConfiguredSignalOutput(3.0, "arbitrary/occupied"), levels())
    private fun levels() = listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(8.0, "private/approach")),
        FixedBlockSignalClearLevel(10.0, ConfiguredSignalOutput(30.0, "private/clear")))
    private fun indicator(id: String, at: Double) = RouteIndicatorDefinition(id, 1, "test:entry",
        "opaque/none", mapOf("test:a" to "opaque/a", "test:b" to "opaque/b"),
        DeviceModelDefinition("test:indicator", ModelPlacement.RailAttached(RailPositionSnapshot(trunk, ViewDirection.AB, at))))
    private fun authoredRoute(id: String, rail: UUID, exit: String) = ProtectionRouteDefinition(id, "test:entry", exit,
        listOf(ProtectionRouteTraversal(trunk, ViewDirection.AB, 5.0, 10.0),
            ProtectionRouteTraversal(rail, ViewDirection.AB, 0.0, 5.0)))
    private fun boundary(id: String, rail: UUID, at: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition(id, RailPositionSnapshot(rail, facing, at))
    private fun mission(branch: UUID) = RouteSnapshot("test:mission", 1, topology.revision,
        listOf(RouteSegmentSnapshot(trunk, ViewDirection.AB, 0.0, 10.0),
            RouteSegmentSnapshot(branch, ViewDirection.AB, 0.0, 10.0)))
    private fun admission(route: RouteSnapshot, requested: Double, committed: Double? = null) =
        ActiveRouteAdmissionSnapshot(owner, "train", route,
            if (requested == committed) ActiveRouteAdmissionState.GRANTED else ActiveRouteAdmissionState.PREPARING,
            RouteBoundary(requested), committed?.let(::RouteBoundary))
    private fun frame(epoch: Long, branch: UUID, intervals: List<RailInterval> = emptyList(),
        additionalSelections: Map<RailEndpointRef, RailEndpointRef> = emptyMap()): RailwayWorldFrameSnapshot {
        val position = RailPositionSnapshot(trunk, ViewDirection.AB, 0.0)
        return RailwayWorldFrameSnapshot.create(epoch, topology.revision, listOf(TrainFrameSnapshot.create(
            "train", "Train", epoch, epoch, 0.0, false, false, emptyList(), position, position,
            intervals, emptyList(), emptyList(), emptyList(), emptyList())),
            ActiveRailContinuationsSnapshot.create(topology.revision, epoch, mapOf(
                endpoint(trunk, RailEndpoint.B) to endpoint(branch, RailEndpoint.A),
                endpoint(branch, RailEndpoint.A) to endpoint(trunk, RailEndpoint.B)) + additionalSelections))
    }
    private fun speed(world: SimpleSignalsWorldRuntime, id: String) =
        world.sectionSignalSnapshot()!!.states.getValue(id).effectiveSpeedLimitMetersPerSecond
    private fun output(world: SimpleSignalsWorldRuntime, id: String) =
        (world.sectionSignalSnapshot()!!.states.getValue(id) as SectionSignalState.Available).output
}
