package work.art1st.javarailways.simplesignals.signalpack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockCatalog
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.presentation.DeviceModelDefinition
import work.art1st.javarailways.simplesignals.presentation.ModelPlacement
import work.art1st.javarailways.simplesignals.region.ProtectionBoundaryDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRegionCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRouteDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableDefinition
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTableCatalog
import work.art1st.javarailways.simplesignals.region.ProtectionRouteTraversal
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.topologyState
import work.art1st.javarailways.simplesignals.runtime.SectionKind
import work.art1st.javarailways.simplesignals.runtime.SectionRef
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPreparationResult
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPublicationStatus
import work.art1st.javarailways.simplesignals.signal.AttachedSignalDefinition
import work.art1st.javarailways.simplesignals.signal.FixedBlockEntryDirection
import work.art1st.javarailways.simplesignals.signal.FixedBlockSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalDefinition
import work.art1st.javarailways.simplesignals.signal.ProtectionSignalKind
import work.art1st.javarailways.simplesignals.signal.ProtectionRouteSelectionIndex
import work.art1st.javarailways.simplesignals.testing.signalWorldRuntime
import java.util.UUID

class SignalObservationGraphPublicationTest {
    private val worldId = UUID(0, 8_000)

    @Test
    fun `real fixed block publication supplies directed roots distances and attached identities`() {
        val firstRail = UUID(0, 8_001)
        val secondRail = UUID(0, 8_002)
        val topology = lineTopologyState(41, firstRail, secondRail)
        val first = FixedBlockDefinition(
            "test:first",
            1,
            listOf(FixedBlockTraversal(firstRail, ViewDirection.AB, 2.0, 8.0)),
            bidirectional = true
        )
        val second = FixedBlockDefinition(
            "test:second",
            1,
            listOf(FixedBlockTraversal(secondRail, ViewDirection.AB, 2.0, 8.0))
        )
        val forward = fixedSignal("test:first-forward", first.blockId)
        val reverse = fixedSignal("test:first-reverse", first.blockId, FixedBlockEntryDirection.REVERSE)
        val downstream = fixedSignal("test:second-signal", second.blockId)
        val attached = AttachedSignalDefinition(
            "test:first-repeater",
            1,
            forward.signalId,
            DeviceModelDefinition("test:model", ModelPlacement.Ground(ModelPose.IDENTITY))
        )
        val definitions = SimpleSignalsWorldDefinitions(
            worldId,
            1,
            emptyList(),
            emptyList(),
            listOf(first, second),
            listOf(forward, reverse, downstream),
            attachedSignals = listOf(attached)
        )
        val world = signalWorldRuntime(worldId)

        assertEquals(
            SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            world.initializeDefinitions(topology, definitions).status
        )
        val publication = world.authoringPublication()
        val observation = publication.signalObservationGraph
        assertTrue(observation.isFor(publication.topology, publication.definitions))
        assertSame(publication.topology, observation.topology)
        assertSame(publication.definitions, observation.definitions)
        assertEquals(observation.logicalRootsByDeviceId.getValue(forward.signalId),
            observation.rootsByDeviceId.getValue(attached.signalId))

        val forwardState = observation.logicalRootsByDeviceId.getValue(forward.signalId).single().state
        val reverseState = observation.logicalRootsByDeviceId.getValue(reverse.signalId).single().state
        assertEquals(ViewDirection.AB, forwardState.direction)
        assertEquals(ViewDirection.BA, reverseState.direction)
        assertEquals("test:first/entry", forwardState.entryBoundaryId)
        assertEquals("test:first/exit", reverseState.entryBoundaryId)
        val next = observation.graph.nodesByState.getValue(forwardState).continuations.single()
        assertEquals(4.0, next.distanceMeters)
        assertEquals(SectionRef(SectionKind.FIXED_BLOCK, second.blockId), next.target!!.section)
    }

    @Test
    fun `real protection publication branches by route with turnout requirements and exact terminal distances`() {
        val approach = UUID(0, 8_010)
        val trunk = UUID(0, 8_011)
        val left = UUID(0, 8_012)
        val right = UUID(0, 8_013)
        val topology = topologyState(
            42,
            listOf(approach, trunk, left, right),
            listOf(
                endpoint(approach, RailEndpoint.B) to endpoint(trunk, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(left, RailEndpoint.A),
                endpoint(trunk, RailEndpoint.B) to endpoint(right, RailEndpoint.A)
            )
        )
        val region = ProtectionRegionDefinition(
            "test:throat",
            1,
            listOf(
                boundary("test:entry", trunk, 5.0, ViewDirection.AB),
                boundary("test:left-exit", left, 5.0, ViewDirection.BA),
                boundary("test:right-exit", right, 5.0, ViewDirection.BA)
            )
        )
        val leftRoute = route("test:left-route", left, "test:left-exit")
        val rightRoute = route("test:right-route", right, "test:right-exit")
        val approachBlock = FixedBlockDefinition(
            "test:approach",
            1,
            listOf(
                FixedBlockTraversal(approach, ViewDirection.AB, 2.0, 10.0),
                FixedBlockTraversal(trunk, ViewDirection.AB, 0.0, 4.0)
            )
        )
        val approachSignal = fixedSignal("test:approach-signal", approachBlock.blockId)
        val current = protectionSignal("test:current", ProtectionSignalKind.CURRENT_ENTRY, "test:entry")
        val fixed = protectionSignal("test:left-fixed", ProtectionSignalKind.FIXED_ROUTE, leftRoute.routeId)
        val definitions = SimpleSignalsWorldDefinitions(
            worldId,
            1,
            listOf(region),
            listOf(ProtectionRouteTableDefinition("test:routes", 1, region.regionId, 1,
                listOf(leftRoute, rightRoute))),
            listOf(approachBlock),
            listOf(approachSignal),
            listOf(current, fixed)
        )
        val world = signalWorldRuntime(worldId)

        val result = world.initializeDefinitions(topology, definitions)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.toString())
        val observation = world.authoringPublication().signalObservationGraph
        val approachRoot = observation.logicalRootsByDeviceId.getValue(approachSignal.signalId).single().state
        val branches = observation.graph.nodesByState.getValue(approachRoot).continuations
        assertEquals(2, branches.size)
        assertTrue(branches.all { it.distanceMeters == 1.0 })
        assertEquals(setOf(leftRoute.routeId, rightRoute.routeId),
            branches.map { it.requirements.protectionRoute!!.routeId }.toSet())
        assertTrue(branches.all { branch ->
            branch.requirements.requiredContinuations.single().entry == endpoint(trunk, RailEndpoint.B)
        })
        assertEquals(2, observation.logicalRootsByDeviceId.getValue(current.signalId).size)
        val leftState = branches.single { it.requirements.protectionRoute!!.routeId == leftRoute.routeId }.target!!
        assertEquals(setOf(current.signalId, fixed.signalId),
            observation.graph.nodesByState.getValue(leftState).deviceIds)

        val plan = SignalObservationPlanCompiler.compile(observation.graph, approachRoot, 30.0)
        assertEquals(2, plan.root.branches.size)
        assertTrue(plan.root.branches.all { routeBranch ->
            val routeNode = routeBranch.target!!
            routeNode.branches.single().termination?.let {
                it.reason == SignalObservationEndReason.TRACK_END && it.distanceMeters == 28.0
            } == true
        })
    }

    @Test
    fun `ambiguous real entries reject the candidate and retain the installed graph`() {
        val rail = UUID(0, 8_020)
        val topology = lineTopologyState(43, rail)
        val first = FixedBlockDefinition("test:first", 1,
            listOf(FixedBlockTraversal(rail, ViewDirection.AB, 2.0, 4.0)))
        val original = SimpleSignalsWorldDefinitions(worldId, 1, emptyList(), emptyList(), listOf(first))
        val world = signalWorldRuntime(worldId)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            world.initializeDefinitions(topology, original).status)
        val installed = world.authoringPublication()
        val conflicting = FixedBlockDefinition("test:conflict", 1,
            listOf(FixedBlockTraversal(rail, ViewDirection.AB, 2.0, 6.0)))

        val rejected = world.prepareDefinitions(
            topology,
            SimpleSignalsWorldDefinitions(worldId, 2, emptyList(), emptyList(), listOf(first, conflicting))
        )

        assertTrue(rejected is SimpleSignalsDefinitionPreparationResult.Rejected)
        assertSame(installed, world.authoringPublication())
        assertTrue(installed.signalObservationGraph.isFor(installed.topology, installed.definitions))
        assertFalse(installed.signalObservationGraph.isFor(topology,
            SimpleSignalsWorldDefinitions(worldId, 1, emptyList(), emptyList(), listOf(first))))
    }

    @Test
    fun `unavailable real protection catalog terminates before the region instead of bypassing it`() {
        val rail = UUID(0, 8_030)
        val topology = lineTopologyState(44, rail)
        val entry = boundary("test:draft-entry", rail, 2.0, ViewDirection.AB)
        val exit = boundary("test:draft-exit", rail, 8.0, ViewDirection.BA)
        val region = ProtectionRegionDefinition(
            "test:draft-region",
            1,
            listOf(entry, exit),
            boundaryDraft = listOf(entry.cut())
        )
        val route = ProtectionRouteDefinition(
            "test:draft-route",
            entry.boundaryId,
            exit.boundaryId,
            listOf(ProtectionRouteTraversal(rail, ViewDirection.AB, 2.0, 8.0))
        )
        val approach = FixedBlockDefinition("test:draft-approach", 1,
            listOf(FixedBlockTraversal(rail, ViewDirection.AB, 0.2, 1.0)))
        val signal = fixedSignal("test:draft-signal", approach.blockId)
        val definitions = SimpleSignalsWorldDefinitions(
            worldId,
            1,
            listOf(region),
            listOf(ProtectionRouteTableDefinition("test:draft-table", 1, region.regionId, 1, listOf(route))),
            listOf(approach),
            listOf(signal)
        )
        val world = signalWorldRuntime(worldId)

        val result = world.initializeDefinitions(topology, definitions)

        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.toString())
        val observation = world.authoringPublication().signalObservationGraph
        val root = observation.logicalRootsByDeviceId.getValue(signal.signalId).single().state
        val terminal = observation.graph.nodesByState.getValue(root).continuations.single()
        assertEquals(SignalObservationEndReason.TOPOLOGY_UNAVAILABLE, terminal.terminalReason)
        assertEquals(1.0, terminal.distanceMeters)
        assertTrue(observation.graph.nodesByState.keys.none { it.section.id == route.routeId })
    }

    @Test
    fun `retained compiled route remains valid when its new selection index owns the exact topology root`() {
        val rail = UUID(0, 8_040)
        val oldTopology = lineTopologyState(45, rail)
        val newTopology = lineTopologyState(46, rail)
        val entry = boundary("test:retained-entry", rail, 2.0, ViewDirection.AB)
        val exit = boundary("test:retained-exit", rail, 8.0, ViewDirection.BA)
        val region = ProtectionRegionDefinition("test:retained-region", 1, listOf(entry, exit))
        val route = ProtectionRouteDefinition("test:retained-route", entry.boundaryId, exit.boundaryId,
            listOf(ProtectionRouteTraversal(rail, ViewDirection.AB, 2.0, 8.0)))
        val table = ProtectionRouteTableDefinition("test:retained-table", 1, region.regionId, 1, listOf(route))
        val definitions = SimpleSignalsWorldDefinitions(worldId, 1, listOf(region), listOf(table))
        val previous = ProtectionRouteSelectionIndex.compile(oldTopology, listOf(table))
        val retained = ProtectionRouteSelectionIndex.compile(newTopology, listOf(table), previous)
        assertSame(previous.routesById.getValue(route.routeId), retained.routesById.getValue(route.routeId))
        assertEquals(oldTopology.revision, retained.routesById.getValue(route.routeId).topologyRevision)
        val regionCatalog = ProtectionRegionCatalog().also {
            assertEquals("PUBLISHED", it.publish(newTopology, 1, listOf(region)).status.name)
        }
        val tableCatalog = ProtectionRouteTableCatalog(regionCatalog).also {
            assertEquals("PUBLISHED", it.publish(1, listOf(table)).status.name)
        }
        val blocks = FixedBlockCatalog().also {
            assertEquals("PUBLISHED", it.publish(newTopology, 1, emptyList()).status.name)
        }

        val observation = SignalObservationGraphBuilder.compile(
            newTopology,
            definitions,
            blocks.snapshot(newTopology),
            regionCatalog.compilationSnapshot()!!,
            retained,
            tableCatalog.unavailableRegionIds()
        )

        assertSame(newTopology, observation.topology)
        assertEquals(setOf(route.routeId), observation.graph.nodesByState.keys.map { it.section.id }.toSet())
    }

    @Test
    fun `real unsectioned loop terminates only its branch with absolute cycle metrics`() {
        val source = UUID(0, 8_050)
        val loopA = UUID(0, 8_051)
        val loopB = UUID(0, 8_052)
        val loopC = UUID(0, 8_053)
        val sibling = UUID(0, 8_054)
        val topology = topologyState(
            47,
            listOf(source, loopA, loopB, loopC, sibling),
            listOf(
                endpoint(source, RailEndpoint.B) to endpoint(loopA, RailEndpoint.A),
                endpoint(source, RailEndpoint.B) to endpoint(sibling, RailEndpoint.A),
                endpoint(loopA, RailEndpoint.B) to endpoint(loopB, RailEndpoint.A),
                endpoint(loopB, RailEndpoint.B) to endpoint(loopC, RailEndpoint.A),
                endpoint(loopC, RailEndpoint.B) to endpoint(loopA, RailEndpoint.A)
            )
        )
        val block = FixedBlockDefinition("test:loop-source", 1,
            listOf(FixedBlockTraversal(source, ViewDirection.AB, 2.0, 10.0)))
        val signal = fixedSignal("test:loop-signal", block.blockId)
        val world = signalWorldRuntime(worldId)
        val result = world.initializeDefinitions(topology,
            SimpleSignalsWorldDefinitions(worldId, 1, emptyList(), emptyList(), listOf(block), listOf(signal)))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.toString())
        val observation = world.authoringPublication().signalObservationGraph
        val root = observation.logicalRootsByDeviceId.getValue(signal.signalId).single().state
        val graphBranches = observation.graph.nodesByState.getValue(root).continuations
        assertTrue(graphBranches.any { it.terminalReason == SignalObservationEndReason.CYCLE })
        assertTrue(graphBranches.any { it.terminalReason == SignalObservationEndReason.TRACK_END })

        val plan = SignalObservationPlanCompiler.compile(observation.graph, root, 100.0)
        val cycle = plan.root.branches.single { it.termination?.reason == SignalObservationEndReason.CYCLE }
            .termination!!
        val trackEnd = plan.root.branches.single { it.termination?.reason == SignalObservationEndReason.TRACK_END }
            .termination!!
        assertEquals(38.0, cycle.distanceMeters)
        assertEquals(8.0, cycle.cycleStartDistanceMeters)
        assertEquals(30.0, cycle.cycleLengthMeters)
        assertEquals(18.0, trackEnd.distanceMeters)
    }

    private fun fixedSignal(
        id: String,
        blockId: String,
        direction: FixedBlockEntryDirection = FixedBlockEntryDirection.FORWARD
    ) = FixedBlockSignalDefinition(
        id,
        1,
        blockId,
        0.0,
        "automatic:block",
        entryDirection = direction
    )

    private fun protectionSignal(id: String, kind: ProtectionSignalKind, binding: String) =
        ProtectionSignalDefinition(
            id,
            1,
            kind,
            binding,
            0.0,
            "automatic:block"
        )

    private fun route(id: String, branch: UUID, exit: String) = ProtectionRouteDefinition(
        id,
        "test:entry",
        exit,
        listOf(
            ProtectionRouteTraversal(UUID(0, 8_011), ViewDirection.AB, 5.0, 10.0),
            ProtectionRouteTraversal(branch, ViewDirection.AB, 0.0, 5.0)
        )
    )

    private fun boundary(id: String, rail: UUID, position: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition(id, RailPositionSnapshot(rail, facing, position))
}
