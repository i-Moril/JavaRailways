package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.simplesignals.persistence.*
import work.art1st.javarailways.simplesignals.region.*
import java.util.UUID

class BoundaryNamesTest {
    private val world = UUID(0, 90)
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)
    private fun point(rail: UUID, arc: Double) = RailCutPosition(rail, arc)
    private fun definitions(points: List<RailCutPosition>, names: List<BoundaryName> = emptyList()) =
        SimpleSignalsWorldDefinitions(world, 1, emptyList(), emptyList(), pendingProtectionBoundaries =
            points.mapIndexed { i, position -> ProtectionBoundaryCut("test:cut$i", position) }, boundaryNames = names)
    private fun graph(vararg connections: Pair<RailEndpointRef, RailEndpointRef>): TopologyState {
        val links = linkedMapOf<RailEndpointRef, MutableSet<RailEndpointRef>>()
        connections.forEach { (a, b) -> links.getOrPut(a, ::linkedSetOf) += b; links.getOrPut(b, ::linkedSetOf) += a }
        return buildTestTopology(1, listOf(a, b, c).map { TestTopologyRail(it, 10.0) }, links)
    }
    private fun port(rail: UUID, endpoint: RailEndpoint) = RailEndpointRef(rail, endpoint)

    @Test fun `all ordinary endpoint direction pairs share names and retain raw anchors after disconnect`() {
        for (first in RailEndpoint.entries) for (second in RailEndpoint.entries) {
            val p = point(a, if (first == RailEndpoint.A) 0.0 else 10.0)
            val q = point(b, if (second == RailEndpoint.A) 0.0 else 10.0)
            val topology = graph(port(a, first) to port(b, second))
            val initial = definitions(listOf(p, q))
            val renamed = BoundaryNames(initial, topology).rename(p, " 西端 ")
            assertEquals(setOf(p, q), renamed.map { it.position }.toSet())
            val saved = definitions(listOf(p, q), renamed)
            assertEquals(setOf("西端"), BoundaryNames(saved, graph()).values(q))
            assertEquals(setOf("西端"), BoundaryNames(saved, graph()).values(p))
        }
    }
    @Test fun `sole peer endpoint anchor can be selected only through exact ordinary continuity`() {
        val p = point(a, 10.0); val q = point(b, 0.0)
        val saved = definitions(listOf(q))
        val joined = graph(port(a, RailEndpoint.B) to port(b, RailEndpoint.A))
        assertEquals(q, BoundaryNames(saved, joined).nearest(p))
        assertNull(BoundaryNames(saved, joined).nearest(point(a, 9.5)))
        assertNull(BoundaryNames(saved, graph()).nearest(p))
        val turnout = graph(port(a, RailEndpoint.B) to port(b, RailEndpoint.A), port(a, RailEndpoint.B) to port(c, RailEndpoint.A))
        assertNull(BoundaryNames(saved, turnout).nearest(p))
    }
    @Test fun `topology merge retains conflicts until explicit rename or clear`() {
        val p = point(a, 10.0); val q = point(b, 0.0)
        val topology = graph(port(a, RailEndpoint.B) to port(b, RailEndpoint.A))
        val saved = definitions(listOf(p, q), listOf(BoundaryName(p, "甲"), BoundaryName(q, "乙")))
        val normalized = BoundaryNames.normalize(saved, topology)
        val resolver = BoundaryNames(normalized, topology)
        assertEquals(setOf("甲", "乙"), resolver.values(p))
        assertTrue(resolver.label(p).render(java.util.Locale.SIMPLIFIED_CHINESE).contains("名称冲突"))
        assertEquals(listOf("同名", "同名"), resolver.rename(q, "同名").map { it.name })
        assertTrue(resolver.rename(q, "  ").isEmpty())
    }
    @Test fun `turnouts and disconnected coincident tracks never share text`() {
        val p = point(a, 10.0); val q = point(b, 0.0); val r = point(c, 0.0)
        val saved = definitions(listOf(p, q, r))
        val topology = graph(port(a, RailEndpoint.B) to port(b, RailEndpoint.A), port(a, RailEndpoint.B) to port(c, RailEndpoint.A))
        assertEquals(listOf(p), BoundaryNames(saved, topology).rename(p, "站口").map { it.position })
        assertEquals(listOf(p), BoundaryNames(saved, graph()).rename(p, "站口").map { it.position })
    }
    @Test fun `connected alias replacement preserves name but deleted final owner does not leak into recreation`() {
        val p = point(a, 10.0); val q = point(b, 0.0)
        val topology = graph(port(a, RailEndpoint.B) to port(b, RailEndpoint.A))
        val transfer = BoundaryNames.normalize(definitions(listOf(q), listOf(BoundaryName(p, "西"))), topology)
        assertEquals(listOf(BoundaryName(q, "西")), transfer.boundaryNames)
        val removed = BoundaryNames.normalize(definitions(emptyList(), transfer.boundaryNames), topology)
        assertTrue(removed.boundaryNames.isEmpty())
        assertTrue(BoundaryNames.normalize(definitions(listOf(q), removed.boundaryNames), topology).boundaryNames.isEmpty())
    }
    @Test fun `loop anchors share text while actual at grade crossings remain independent`() {
        val loop = graph(port(a, RailEndpoint.A) to port(b, RailEndpoint.B), port(a, RailEndpoint.B) to port(b, RailEndpoint.A))
        val start = point(a, 0.0); val other = point(b, 10.0)
        assertEquals(2, BoundaryNames(definitions(listOf(start, other)), loop).rename(start, "环线").size)
        val crossing = buildTestTopology(1, listOf(a, b).map { TestTopologyRail(it, 10.0) }, emptyMap(),
            atGradeCrossings = listOf(AtGradeRailCrossing(RailCrossingPoint(a, 5.0), RailCrossingPoint(b, 5.0))))
        val p = point(a, 5.0); val q = point(b, 5.0)
        assertEquals(listOf(p), BoundaryNames(definitions(listOf(p, q)), crossing).rename(p, "交叉口").map { it.position })
    }

    @Test fun `missing topology retains names on still authored raw cuts`() {
        val p = point(UUID(0, 999), 2.0)
        assertEquals(listOf(BoundaryName(p, "保留")), BoundaryNames.normalize(definitions(listOf(p), listOf(BoundaryName(p, "保留"))), graph()).boundaryNames)
    }
    @Test fun `near numeric cuts group without transitive chains and equally near distinct cuts reject`() {
        val p = point(a, 2.0)
        val close = point(a, 2.0 + work.art1st.javarailways.core.api.physics.curve.Curve.TOLERANCE / 2)
        val resolver = BoundaryNames(definitions(listOf(p, close)), graph())
        assertEquals(2, resolver.rename(p, "西").size)
        assertThrows(IllegalArgumentException::class.java) { BoundaryNames(definitions(listOf(point(a, 2.0), point(a, 3.0))), graph()).nearest(point(a, 2.5)) }
    }
    @Test fun `unicode length plain text normalization and strict codec default fixture`() {
        assertEquals("😀".repeat(64), normalizeBoundaryName(" 😀".repeat(1).trim()+"😀".repeat(63)))
        assertThrows(IllegalArgumentException::class.java) { normalizeBoundaryName("😀".repeat(65)) }
        listOf("a\nb", "a\tb", "a\u2028b").forEach { bad -> assertThrows(IllegalArgumentException::class.java) { normalizeBoundaryName(bad) } }
        assertEquals("<red>站</red>", normalizeBoundaryName("<red>站</red>"))
        val old = """{"schemaVersion":1,"worldId":"00000000-0000-0000-0000-00000000005a","definitionRevision":1,"protectionRegions":[],"protectionRouteTables":[]}"""
        assertTrue(SimpleSignalsWorldDefinitionsCodec.decode(old, world).boundaryNames.isEmpty())
        val value = definitions(listOf(point(a, 2.0), point(a, 8.0)), listOf(BoundaryName(point(a, 2.0), "同名"), BoundaryName(point(a, 8.0), "同名")))
        val encoded = SimpleSignalsWorldDefinitionsCodec.encode(value)
        assertTrue(encoded.contains("\"schemaVersion\": 1"))
        assertEquals(value, SimpleSignalsWorldDefinitionsCodec.decode(encoded, world))
        val patch = definitionDraftPatch(definitions(emptyList()), value)
        assertEquals(value.boundaryNames, patch.boundaryNames)
        assertTrue(definitionDraftSummary(definitions(emptyList()), value).contains("create=4"))
    }
}
