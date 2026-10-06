package work.art1st.javarailways.simplesignals.signal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayStateStamp
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.ActiveRailContinuationsSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.block.*
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.*
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime
import work.art1st.javarailways.simplesignals.testing.reconfigured
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import java.util.UUID

/** Real compilers and reactive graph; no additional resource or physical observation engine. */
class DirectionalFixedBlockSignalsTest {
    private val world = UUID(0, 120)
    private val left = UUID(0, 121)
    private val a = UUID(0, 122)
    private val b = UUID(0, 123)
    private val right = UUID(0, 124)
    private val topology = topologyState(7, listOf(left, a, b, right), listOf(
        endpoint(left, RailEndpoint.B) to endpoint(a, RailEndpoint.A),
        endpoint(a, RailEndpoint.B) to endpoint(b, RailEndpoint.B),
        endpoint(b, RailEndpoint.A) to endpoint(right, RailEndpoint.A)))
    private val center = FixedBlockDefinition("test:center", 1, listOf(
        FixedBlockTraversal(a, ViewDirection.AB, 2.0, 10.0),
        FixedBlockTraversal(b, ViewDirection.BA, 2.0, 10.0)), true)
    private val leftBlock = FixedBlockDefinition("test:left", 1,
        listOf(FixedBlockTraversal(left, ViewDirection.BA, 2.0, 8.0)))
    private val rightBlock = FixedBlockDefinition("test:right", 1,
        listOf(FixedBlockTraversal(right, ViewDirection.AB, 2.0, 8.0)))
    private val blocks = FixedBlockCatalog().also {
        it.publish(topology, 1, listOf(center, leftBlock, rightBlock))
    }.snapshot(topology)
    private fun signal(id: String, block: String, direction: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD,
        downstream: String? = null, clearSpeed: Double = 30.0) = FixedBlockSignalDefinition(id, 1, block,
        if (direction == FixedBlockEntryDirection.FORWARD) 12.0 else 4.0,
        ConfiguredSignalOutput(0.0, "$id/occupied"), listOf(
            FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(8.0, "$id/approach")),
            FixedBlockSignalClearLevel(20.0, ConfiguredSignalOutput(clearSpeed, "$id/clear"))),
        listOfNotNull(downstream?.let(::FixedBlockSignalDependency)), entryDirection = direction)
    private val forward = signal("test:forward", center.blockId, downstream = "test:right-signal")
    private val reverse = signal("test:reverse", center.blockId, FixedBlockEntryDirection.REVERSE, "test:left-signal", 25.0)
    private val leftSignal = signal("test:left-signal", leftBlock.blockId)
    private val rightSignal = signal("test:right-signal", rightBlock.blockId)

    @Test fun `mixed facing paths compile each end downstream geometry and lead time independently`() {
        val compiled = CompiledFixedBlockSignals.compile(listOf(reverse, rightSignal, forward, leftSignal), blocks)
        assertEquals(3, blocks.blocksById.size)
        assertEquals(16.0, blocks.blocksById.getValue(center.blockId).lengthMeters)
        assertEquals(12.0, compiled.inspectionLeadTimeSeconds(forward.entry))
        assertEquals(4.0, compiled.inspectionLeadTimeSeconds(reverse.entry))
        val byId = compiled.bindings.associateBy { it.signalId }
        assertEquals(byId.getValue(forward.signalId).controlledSection, byId.getValue(reverse.signalId).controlledSection)
        for (rule in listOf(forward, reverse)) {
            val dependency = byId.getValue(rule.signalId).downstreamSignals.single()
            assertEquals(20.0, dependency.distanceMeters)
            assertEquals(setOf(a, b, if (rule === forward) right else left), dependency.affectedRailIds)
            assertEquals(fixedBlockSignalDeclarationId(center.blockId, rule.entryDirection), byId.getValue(rule.signalId).declarationId)
        }
        assertSame(blocks.blocksById.getValue(center.blockId).reverseTraversals,
            DirectedSignalGeometry.from(blocks.blocksById.getValue(center.blockId), FixedBlockEntryDirection.REVERSE).traversals)
        // Facing of a provider's own entry matters just as much as the consuming entry.
        assertThrows(IllegalArgumentException::class.java) {
            val oppositeRight = rightSignal.reconfigured(entryDirection = FixedBlockEntryDirection.REVERSE)
            val twoWayRight = FixedBlockDefinition(rightBlock.blockId, 1, rightBlock.traversals, true)
            val changed = FixedBlockCatalog().also { it.publish(topology, 1, listOf(center, leftBlock, twoWayRight)) }.snapshot(topology)
            CompiledFixedBlockSignals.compile(listOf(forward, oppositeRight), changed)
        }
    }

    @Test fun `shared occupancy wakes both entries while a directional downstream wave leaves opposite rule idle`() {
        val compiled = CompiledFixedBlockSignals.compile(listOf(forward, reverse, leftSignal, rightSignal), blocks)
        val calls = arrayListOf<String>()
        val bindings = compiled.bindings.map { binding -> SectionSignalRuleBinding(binding.signalId, binding.controlledSection,
            binding.downstreamSignals, declarationId = binding.declarationId) { input ->
            calls += input.signalId
            binding.rule.evaluate(input)
        } }
        val sections = blocks.blocksById.keys.map { SectionRef(SectionKind.FIXED_BLOCK, it) }
        val runtime = SectionSignalRuleRuntime.compile(sections, bindings)
        val active = ActiveRailContinuationsSnapshot.empty(topology.revision)
        fun occupancy(epoch: Long, changed: Collection<SectionRef>, occupied: Set<String>) = SectionOccupancySnapshot(
            RailwayStateStamp(world.toString(), epoch, topology.revision), sections.associateWith {
                SectionOccupancyObservation(it, 1, blocks.blocksById.getValue(it.id).lengthMeters,
                    if (it.id in occupied) setOf("train") else emptySet())
            }, changed, emptyMap())
        var state = runtime.evaluate(null, occupancy(1, sections, emptySet()), active, emptyList())
        assertEquals(30.0, state.states.getValue(forward.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(25.0, state.states.getValue(reverse.signalId).effectiveSpeedLimitMetersPerSecond)
        calls.clear()
        state = runtime.evaluate(state, occupancy(2, listOf(SectionRef(SectionKind.FIXED_BLOCK, rightBlock.blockId)),
            setOf(rightBlock.blockId)), active, emptyList())
        assertEquals(listOf(rightSignal.signalId, forward.signalId), calls)
        assertEquals(8.0, state.states.getValue(forward.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(25.0, state.states.getValue(reverse.signalId).effectiveSpeedLimitMetersPerSecond)
        calls.clear()
        state = runtime.evaluate(state, occupancy(3, listOf(SectionRef(SectionKind.FIXED_BLOCK, center.blockId)),
            setOf(center.blockId, rightBlock.blockId)), active, emptyList())
        assertEquals(setOf(forward.signalId, reverse.signalId), calls.toSet())
        assertEquals(0.0, state.states.getValue(forward.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(0.0, state.states.getValue(reverse.signalId).effectiveSpeedLimitMetersPerSecond)
        assertEquals(setOf(fixedBlockSignalDeclarationId(center.blockId),
            fixedBlockSignalDeclarationId(center.blockId, FixedBlockEntryDirection.REVERSE)), runtime.invalidatedDeclarationIds(state))
        assertEquals(setOf(forward.signalId), runtime.affectedBindings(emptyList(), listOf(forward.signalId), emptyList()).map { it.signalId }.toSet())
        assertEquals(setOf(forward.signalId, reverse.signalId), runtime.affectedBindings(
            listOf(SectionRef(SectionKind.FIXED_BLOCK, center.blockId)), emptyList(), emptyList()).map { it.signalId }.toSet())
    }

    @Test fun `duplicate entry and reverse on one way fail before unavailable and retained shortcuts`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(listOf(forward, forward.reconfigured(signalId = "test:duplicate")), blocks,
                unavailableSignalIds = setOf(forward.signalId, "test:duplicate"))
        }
        val bad = rightSignal.reconfigured(entryDirection = FixedBlockEntryDirection.REVERSE)
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(listOf(bad), blocks, unavailableSignalIds = setOf(bad.signalId))
        }
        val retained = CompiledFixedBlockSignals.compile(listOf(rightSignal), blocks).bindings.single()
        assertThrows(IllegalArgumentException::class.java) {
            CompiledFixedBlockSignals.compile(listOf(bad), blocks, retainedBindings = mapOf(bad.signalId to retained),
                topologyFailures = mutableSetOf())
        }
    }

    @Test fun `temporarily missing reverse downstream leaves forward available and both declarations addressable`() {
        val missingReverse = reverse.reconfigured(downstreamSignals = listOf(FixedBlockSignalDependency("test:missing")))
        val (_, compiled) = CompiledProtectionSignals.compile(topology, 1,
            ProtectionRouteSelectionIndex.compile(topology, emptyList()), blocks,
            listOf(forward, missingReverse, rightSignal), emptyList(), emptyList())
        val sections = blocks.blocksById.map { (id, block) -> SectionRef(SectionKind.FIXED_BLOCK, id) to
            SectionOccupancyObservation(SectionRef(SectionKind.FIXED_BLOCK, id), 1, block.lengthMeters, emptySet()) }.toMap()
        val state = compiled.ruleRuntime!!.evaluate(null, SectionOccupancySnapshot(RailwayStateStamp(world.toString(), 1, topology.revision),
            sections, sections.keys, emptyMap()), ActiveRailContinuationsSnapshot.empty(topology.revision), emptyList())
        assertEquals(30.0, state.states.getValue(forward.signalId).effectiveSpeedLimitMetersPerSecond)
        assertInstanceOf(SectionSignalState.Unavailable::class.java, state.states.getValue(reverse.signalId))
        assertEquals(forward.signalId, compiled.ruleRuntime.signalIdForDeclaration(fixedBlockSignalDeclarationId(center.blockId)))
        assertEquals(reverse.signalId, compiled.ruleRuntime.signalIdForDeclaration(
            fixedBlockSignalDeclarationId(center.blockId, FixedBlockEntryDirection.REVERSE)))
    }

    @Test fun `rebinding a retained signal identity prepares fresh reverse geometry and both old new edit paths`() {
        val runtime = signalWorldRuntime(world)
        val original = SimpleSignalsWorldDefinitions(world, 1, emptyList(), emptyList(),
            listOf(center, leftBlock, rightBlock), listOf(forward, leftSignal, rightSignal))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            runtime.initializeDefinitions(topology, original).status)
        val oldPublication = runtime.authoringPublication()
        val oldBinding = oldPublication.fixedBlockSignals.bindings.single { it.signalId == forward.signalId }
        val reconfigured = forward.reconfigured(revision = 2, entryDirection = FixedBlockEntryDirection.REVERSE,
            downstreamSignals = listOf(FixedBlockSignalDependency(leftSignal.signalId)))
        val candidate = SimpleSignalsWorldDefinitions(world, 2, emptyList(), emptyList(), original.fixedBlocks,
            listOf(reconfigured, leftSignal, rightSignal))
        val prepared = runtime.prepareDefinitions(topology, candidate) as SimpleSignalsDefinitionPreparationResult.Prepared
        val newBinding = prepared.value.candidatePublication.fixedBlockSignals.bindings.single { it.signalId == forward.signalId }
        assertNotSame(oldBinding, newBinding, "same signal ID cannot retain the old directional rule")
        assertEquals(fixedBlockSignalDeclarationId(center.blockId), oldBinding.declarationId)
        assertEquals(fixedBlockSignalDeclarationId(center.blockId, FixedBlockEntryDirection.REVERSE), newBinding.declarationId)
        assertEquals(setOf(a, b, right), oldBinding.downstreamSignals.single().affectedRailIds)
        assertEquals(setOf(a, b, left), newBinding.downstreamSignals.single().affectedRailIds)
        assertEquals(setOf(left, a, b, right), prepared.value.affectedRailIds)
        assertSame(oldPublication, runtime.authoringPublication(), "preparation does not publish the candidate")
    }

    @Test fun `typed entry declarations round trip slash paths without reverse suffix collisions`() {
        val keys = listOf(FixedBlockSignalEntry("test:x"), FixedBlockSignalEntry("test:x", FixedBlockEntryDirection.REVERSE),
            FixedBlockSignalEntry("test:x/reverse"), FixedBlockSignalEntry("test:x/reverse", FixedBlockEntryDirection.REVERSE))
        val declarations = keys.map { fixedBlockSignalDeclarationId(it.blockId, it.direction) }
        assertEquals(keys.size, declarations.toSet().size)
        assertEquals(keys, declarations.map(::blockEntryFromSignalDeclaration))
        assertFalse(protectionSignalDeclarationId("test:x") in declarations)
        assertThrows(IllegalArgumentException::class.java) { blockEntryFromSignalDeclaration("simplesignals:fixed-block/test/x") }
    }

    @Test fun `mixed protection graph uses reverse fixed geometry as consumer and provider`() {
        fun region(id: String, rail: UUID) = ProtectionRegionDefinition("test:$id", 1, listOf(
            ProtectionBoundaryDefinition("test:$id-entry", RailPositionSnapshot(rail, ViewDirection.BA, 8.0)),
            ProtectionBoundaryDefinition("test:$id-exit", RailPositionSnapshot(rail, ViewDirection.AB, 2.0))))
        val leftRegion = region("left-region", left)
        val rightRegion = region("right-region", right)
        fun route(id: String, region: ProtectionRegionDefinition, rail: UUID) = ProtectionRouteDefinition("test:$id",
            region.boundaries.first().boundaryId, region.boundaries.last().boundaryId,
            listOf(ProtectionRouteTraversal(rail, ViewDirection.BA, 2.0, 8.0)))
        val leftRoute = route("left-route", leftRegion, left)
        val rightRoute = route("right-route", rightRegion, right)
        fun protection(id: String, route: ProtectionRouteDefinition, downstream: String? = null) =
            ProtectionSignalDefinition("test:$id", 1, ProtectionSignalKind.FIXED_ROUTE, route.routeId, 0.0,
                ConfiguredSignalOutput(0.0, "stop"), ConfiguredSignalOutput(0.0, "occupied"),
                listOf(FixedBlockSignalClearLevel(0.0, ConfiguredSignalOutput(30.0, "clear"))),
                listOfNotNull(downstream?.let(::FixedBlockSignalDependency)))
        val leftProtection = protection("left-protection", leftRoute)
        val reverseToProtection = reverse.reconfigured(downstreamSignals = listOf(FixedBlockSignalDependency(leftProtection.signalId)))
        val rightProtection = protection("right-protection", rightRoute, reverse.signalId)
        val definitions = SimpleSignalsWorldDefinitions(world, 1, listOf(leftRegion, rightRegion), listOf(
            ProtectionRouteTableDefinition("test:left-table", 1, leftRegion.regionId, 1, listOf(leftRoute)),
            ProtectionRouteTableDefinition("test:right-table", 1, rightRegion.regionId, 1, listOf(rightRoute))),
            listOf(center), listOf(forward.reconfigured(downstreamSignals = emptyList()), reverseToProtection),
            listOf(leftProtection, rightProtection))
        val runtime = signalWorldRuntime(world)
        val result = runtime.initializeDefinitions(topology, definitions)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.toString())
        val publication = runtime.authoringPublication()
        assertEquals(1, publication.fixedBlocks.snapshot(topology).blocksById.size)
        assertEquals(20.0, publication.fixedBlockSignals.bindings.single { it.signalId == reverse.signalId }
            .downstreamSignals.single().distanceMeters)
        assertEquals(10.0, publication.protectionSignals.bindings.single { it.signalId == rightProtection.signalId }
            .downstreamSignals.single().distanceMeters)
        // Correct directed dependency paths are part of the same guarded old/new edit footprint.
        assertEquals(setOf(a, b, left, right), publication.protectionSignals.ruleRuntime!!.affectedBindings(
            emptyList(), listOf(leftProtection.signalId), emptyList()).flatMap { binding ->
            binding.downstreamSignals.flatMap { it.affectedRailIds }
        }.toSet())
    }
}
