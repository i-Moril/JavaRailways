package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import net.kyori.adventure.text.format.NamedTextColor
import work.art1st.javarailways.bukkit.api.editor.EditorTextAppearance
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.topology.RailTopologyChange
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.simplesignals.block.FixedBlockDefinition
import work.art1st.javarailways.simplesignals.block.FixedBlockTraversal
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.lineTopologyState
import work.art1st.javarailways.simplesignals.region.endpoint
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsWorldRuntime
import work.art1st.javarailways.simplesignals.runtime.SimpleSignalsDefinitionPublicationStatus
import java.util.Optional
import java.util.UUID
import work.art1st.javarailways.simplesignals.authoring.RuntimeRoutePreviewBackendTest.Companion.proxy

class RuntimeSectionOverlayBackendTest {
    @Test fun `boundary identity joins real connected endpoints but not coincident unconnected rails`() {
        val first = UUID(0, 2)
        val second = UUID(0, 3)
        val connected = lineTopologyState(1, first, second)
        val end = RailPositionSnapshot(first, ViewDirection.AB, 10.0)
        val start = RailPositionSnapshot(second, ViewDirection.BA, 0.0)
        assertEquals(connected.overlayBoundaryIdentity(end), connected.overlayBoundaryIdentity(start))
        val blocks = listOf(
            FixedBlockDefinition("test:first", 0, listOf(FixedBlockTraversal(first, ViewDirection.AB, 5.0, 10.0))),
            FixedBlockDefinition("test:second", 0, listOf(FixedBlockTraversal(second, ViewDirection.BA, 0.0, 5.0))))
        val definitions = SimpleSignalsWorldDefinitions(UUID(0, 1), 0, emptyList(), emptyList(), blocks)
        val joined = SectionOverlayDrawing.prepare(definitions, emptySet(), emptySet(),
            boundaryIdentity = connected::overlayBoundaryIdentity) { SectionOverlayDrawingTest.curve() }
        assertEquals(3, joined.labels.count { it.id().endsWith("/boundary") })
        val separate = work.art1st.javarailways.simplesignals.region.topologyState(1,
            listOf(first, second), emptyList())
        assertNotEquals(separate.overlayBoundaryIdentity(end), separate.overlayBoundaryIdentity(start))
        val unconnected = SectionOverlayDrawing.prepare(definitions, emptySet(), emptySet(),
            boundaryIdentity = separate::overlayBoundaryIdentity) { SectionOverlayDrawingTest.curve() }
        assertEquals(4, unconnected.labels.count { it.id().endsWith("/boundary") })
        val loop = work.art1st.javarailways.simplesignals.region.topologyState(1,
            listOf(first, second), listOf(
                endpoint(first, RailEndpoint.A) to endpoint(second, RailEndpoint.B),
                endpoint(first, RailEndpoint.B) to endpoint(second, RailEndpoint.A)))
        assertEquals(loop.overlayBoundaryIdentity(RailPositionSnapshot(first, ViewDirection.AB, 0.0)),
            loop.overlayBoundaryIdentity(RailPositionSnapshot(second, ViewDirection.BA, 10.0)),
            "the structural loop anchor is still one ordinary physical connection")
    }

    @Test fun `runtime publication topology and guard identities expire capture without rebuilding topology`() {
        val world = UUID(0, 1)
        val rail = UUID(0, 2)
        var root = lineTopologyState(1, rail)
        var runtime = SimpleSignalsWorldRuntime(world)
        val block = FixedBlockDefinition("test:block", 0, listOf(FixedBlockTraversal(rail, ViewDirection.AB, 2.0, 8.0)))
        val definitions = SimpleSignalsWorldDefinitions(world, 1, emptyList(), emptyList(), listOf(block))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, runtime.initializeDefinitions(root, definitions).status)
        var curveCalls = 0
        val infrastructure = proxy(InfrastructureResourceService::class.java) { name, _ ->
            check(name == "currentTopology") { "Unexpected mutation or topology rebuild: $name" }
            root
        }
        val targets = proxy(RailTargetService::class.java) { name, _ ->
            check(name == "findCurve")
            curveCalls++
            Optional.of(SectionOverlayDrawingTest.curve())
        }
        val backend = RuntimeSectionOverlayBackend({ runtime }, infrastructure, targets, work.art1st.javarailways.bukkit.api.diagnostics.WorldIssueService())
        val first = backend.capture(world)!!
        val drawing = backend.prepare(first)
        assertTrue(backend.current(first))
        assertTrue(drawing.labels.isNotEmpty())
        repeat(3) { assertTrue(backend.current(first)) }
        assertEquals(1, curveCalls)
        val nextRoot = lineTopologyState(2, rail)
        runtime.topologyGuard.invalidate(RailTopologyChange(root, nextRoot, setOf(rail)))
        root = nextRoot
        assertFalse(backend.current(first))
        val invalidated = backend.capture(world)!!
        val unavailable = backend.prepare(invalidated)
        assertTrue(unavailable.labels.isNotEmpty())
        val readable = unavailable.labels.filter { it.appearance().mode() == EditorTextAppearance.Mode.READABLE_TEXT }
        val glyphs = unavailable.labels.filter { it.appearance().mode() == EditorTextAppearance.Mode.FIXED_GLYPH }
        assertTrue(readable.isNotEmpty())
        assertTrue(readable.all { it.text().toString().contains("unavailable") })
        assertTrue(glyphs.isNotEmpty())
        assertTrue(glyphs.all { it.text().color() == NamedTextColor.RED })
        assertEquals(2, curveCalls)
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED,
            runtime.initializeDefinitions(root, SimpleSignalsWorldDefinitions(world, 2,
                emptyList(), emptyList(), listOf(block))).status)
        assertFalse(backend.current(invalidated))
        val refreshed = backend.capture(world)!!
        assertTrue(backend.current(refreshed))
        // A new runtime with the same UUID and same topology/definitions never inherits the old capture.
        runtime.retire()
        runtime = SimpleSignalsWorldRuntime(world).also { it.initializeDefinitions(root, definitions) }
        assertFalse(backend.current(refreshed))
        runtime.retire()
        assertNull(backend.capture(world))
    }
}
