package work.art1st.javarailways.bukkit.tool

import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.infrastructure.ManualTurnoutSelectionStatus
import work.art1st.javarailways.basic.api.infrastructure.RailIntervalResource
import work.art1st.javarailways.basic.api.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.bukkit.api.internal.DefaultInfrastructureResourceService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.topology.TestTopologyRail
import work.art1st.javarailways.core.physics.rail.topology.buildTestTopology
import work.art1st.javarailways.core.physics.train.createTestWorld
import java.lang.reflect.Proxy
import java.util.UUID

class TurnoutWandSelectionTest {
    private val worlds = mutableListOf<CoreWorld>()

    @AfterEach
    fun closeWorlds() = worlds.forEach {
        it.physicsCoordinator.close()
        (it.tickManager as? AutoCloseable)?.close()
    }

    @Test
    fun `hit chooses only the near canonical endpoint of a real turnout`() {
        val fixture = turnout()
        val topology = fixture.world.topologyState
        assertEquals(fixture.stemPort, turnoutPortAt(topology, fixture.stem.id, 9.0, 10.0))
        assertEquals(fixture.firstPort, turnoutPortAt(topology, fixture.first.id, 1.0, fixture.first.curve.totalArcLength))
        assertNull(turnoutPortAt(topology, fixture.stem.id, 1.0, 10.0), "must not jump to the far turnout")
        assertNull(turnoutPortAt(topology, fixture.stem.id, Double.NaN, 10.0))
        assertNull(turnoutPortAt(topology, fixture.stem.id, 11.0, 10.0))
        val unconnected = fixture.world.createRailForTest(curve(0.0, 0.0, 10.0, 3.0))
        assertNull(turnoutPortAt(fixture.world.topologyState, unconnected.id, 0.0, unconnected.curve.totalArcLength),
            "a nearby unconnected rail is not a turnout port")
    }

    @Test
    fun `request respects explicit legal continuation and does not connect branch to branch`() {
        val fixture = turnout()
        val topology = fixture.world.topologyState
        assertEquals(fixture.firstPort, fixture.selection.request(fixture.worldId, topology, fixture.firstPort)?.toPort)
        assertNull(fixture.selection.request(fixture.worldId, topology, fixture.stemPort))
        val branch = fixture.selection.copy(fromPort = fixture.firstPort)
        assertNull(branch.request(fixture.worldId, topology, fixture.secondPort))
        assertNotNull(branch.request(fixture.worldId, topology, fixture.stemPort))
    }

    @Test
    fun `world changes and replacement roots invalidate selections even at the same revision`() {
        val fixture = turnout()
        val selection = fixture.selection
        val rails = listOf(fixture.stem, fixture.first, fixture.second)
        val copy = buildTestTopology(selection.topology.revision,
            rails.map { TestTopologyRail(it.id, it.curve.totalArcLength) },
            mapOf(fixture.stemPort to setOf(fixture.firstPort, fixture.secondPort),
                fixture.firstPort to setOf(fixture.stemPort), fixture.secondPort to setOf(fixture.stemPort)))
        assertEquals(selection.topology, copy)
        assertFalse(selection.isCurrent(fixture.worldId, copy))
        assertNull(selection.request(fixture.worldId, copy, fixture.firstPort))
        assertNull(selection.request(UUID.randomUUID(), selection.topology, fixture.firstPort))
        fixture.world.createRailForTest(curve(100.0, 0.0, 110.0, 0.0))
        assertNull(selection.request(fixture.worldId, fixture.world.topologyState, fixture.firstPort))
        assertNull(currentTurnoutConnection(fixture.world, selection))
    }

    @Test
    fun `current display reads directed live continuation and detects missing reverse alignment`() {
        val fixture = turnout()
        fixture.stem.viewAB.selectNextRail(fixture.second.viewAB)
        fixture.second.viewBA.selectNextRail(fixture.stem.viewBA)
        fixture.first.viewBA.selectNextRail(fixture.stem.viewBA)
        assertEquals(TurnoutWandConnection(fixture.secondPort, true), currentTurnoutConnection(fixture.world, fixture.selection))
        assertEquals(TurnoutWandConnection(fixture.stemPort, false),
            currentTurnoutConnection(fixture.world, fixture.selection.copy(fromPort = fixture.firstPort)))
        fixture.stem.viewAB.selectNextRail(null)
        assertNull(currentTurnoutConnection(fixture.world, fixture.selection))
    }

    @Test
    fun `wand port request passes through host service and reports actual success and refused state`() {
        val fixture = turnout()
        val service = DefaultInfrastructureResourceService()
        val owner = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { instance, method, args ->
            when (method.name) {
                "isEnabled" -> true
                "getName" -> "turnout-wand-test"
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.get(0)
                else -> error(method.name)
            }
        } as Plugin
        try {
            service.registerWorld(fixture.worldId, { fixture.world.topologyState }) { resource ->
                resource is RailIntervalResource && fixture.world.getRail(resource.railId) != null ||
                    resource is StableInfrastructureResource && service.stableResourceExists(fixture.worldId, resource)
            }
            service.attachInfrastructureRuntime(fixture.worldId, fixture.world, { null }, {}, 5.0) { reason, failure ->
                throw IllegalStateException(reason, failure)
            }
            val request = requireNotNull(fixture.selection.request(fixture.worldId, fixture.world.topologyState, fixture.firstPort))
            val success = service.selectTurnout(owner, fixture.worldId, request)
            assertEquals(ManualTurnoutSelectionStatus.SELECTED, success.status)
            assertEquals(TurnoutWandConnection(fixture.firstPort, true), currentTurnoutConnection(fixture.world, fixture.selection))
            assertTrue(service.liveClaims(fixture.worldId).isEmpty())
            assertEquals(ManualTurnoutSelectionStatus.UNCHANGED, service.selectTurnout(owner, fixture.worldId, request).status)

            val rejected = service.selectTurnout(owner, fixture.worldId,
                request.copy(topologyRevision = request.topologyRevision - 1, toPort = fixture.secondPort))
            assertEquals(ManualTurnoutSelectionStatus.STALE_TOPOLOGY, rejected.status)
            assertEquals(TurnoutWandConnection(fixture.firstPort, true), currentTurnoutConnection(fixture.world, fixture.selection),
                "rejected intent must never be shown as the current branch")

            val reverse = fixture.selection.copy(fromPort = fixture.secondPort)
            assertEquals(TurnoutWandConnection(fixture.stemPort, false), currentTurnoutConnection(fixture.world, reverse))
            val reverseRequest = requireNotNull(reverse.request(fixture.worldId, fixture.world.topologyState, fixture.stemPort))
            assertEquals(ManualTurnoutSelectionStatus.SELECTED, service.selectTurnout(owner, fixture.worldId, reverseRequest).status)
            assertEquals(TurnoutWandConnection(fixture.stemPort, true), currentTurnoutConnection(fixture.world, reverse))
            assertEquals(TurnoutWandConnection(fixture.secondPort, true), currentTurnoutConnection(fixture.world, fixture.selection))
            assertTrue(service.liveClaims(fixture.worldId).isEmpty(), "reverse switching must also release its temporary claim")
        } finally {
            service.close()
        }
    }

    @Test
    fun `item codec never revives transient topology and rejects unknown input`() {
        val fixture = turnout()
        val state = TurnoutWandState().also { it.selection = fixture.selection; it.querySequence = 7 }
        val encoded = TurnoutWandStateCodec.encode(state)
        assertEquals("""{"mode":"SELECT"}""", encoded)
        val restored = requireNotNull(TurnoutWandStateCodec.decode(encoded))
        assertEquals(TurnoutWandMode.SELECT, restored.mode)
        assertNull(restored.selection)
        assertEquals(0L, restored.querySequence)
        assertNull(TurnoutWandStateCodec.decode("""{"mode":"REMOVED"}"""))
        assertNull(TurnoutWandStateCodec.decode("""{"mode":"SELECT","topologyRevision":7}"""))
        state.clear()
        assertNull(state.selection)
        assertEquals(8L, state.querySequence, "clearing also invalidates pending targeting completion")
    }

    @Test
    fun `pending click cannot apply after clearing changing item world or topology`() {
        val fixture = turnout()
        val state = TurnoutWandState().also { it.selection = fixture.selection }
        val sequence = ++state.querySequence
        val root = fixture.world.topologyState
        fun validity(active: TurnoutWandState?, worldId: UUID, topology: work.art1st.javarailways.core.api.physics.rail.topology.TopologyState?) =
            turnoutTargetValidity(state, sequence, fixture.worldId, root, active, worldId, topology)
        assertEquals(TurnoutWandTargetValidity.CURRENT, validity(state, fixture.worldId, root))
        assertEquals(TurnoutWandTargetValidity.CANCELLED, validity(null, fixture.worldId, root), "wand moved out of main hand")
        assertEquals(TurnoutWandTargetValidity.CANCELLED, validity(TurnoutWandState(), fixture.worldId, root), "another wand item")
        assertEquals(TurnoutWandTargetValidity.STALE, validity(state, UUID.randomUUID(), root))
        assertEquals(TurnoutWandTargetValidity.STALE, validity(state, fixture.worldId, null), "world unloaded")
        fixture.world.createRailForTest(curve(100.0, 0.0, 110.0, 0.0))
        assertEquals(TurnoutWandTargetValidity.STALE, validity(state, fixture.worldId, fixture.world.topologyState))
        state.clear()
        assertEquals(TurnoutWandTargetValidity.CANCELLED, validity(state, fixture.worldId, root))
        assertEquals(TurnoutWandConnection(fixture.secondPort, true),
            currentTurnoutConnection(fixture.world, fixture.selection.copy(topology = fixture.world.topologyState)))
    }

    private fun turnout(): Fixture {
        val world = createTestWorld().also(worlds::add)
        val stem = world.createRailForTest(curve(-10.0, 0.0, 0.0, 0.0))
        val first = world.createRailForTest(curve(0.0, 0.0, 10.0, -5.0))
        val second = world.createRailForTest(curve(0.0, 0.0, 10.0, 5.0))
        CoreRail.connect(stem, first)
        CoreRail.connect(stem, second)
        stem.viewAB.selectNextRail(second.viewAB)
        return Fixture(world, UUID.randomUUID(), stem, first, second)
    }

    private fun curve(x1: Double, z1: Double, x2: Double, z2: Double): PrimeCurve {
        val from = Vector3d(x1, 0.0, z1)
        val to = Vector3d(x2, 0.0, z2)
        val tangent = Vector3d(to).sub(from).normalize()
        val up = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, up, 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(to, tangent, up, from.distance(to), Curve.SegmentType.LINE_END)))
    }

    private class Fixture(val world: CoreWorld, val worldId: UUID, val stem: CoreRail, val first: CoreRail, val second: CoreRail) {
        val stemPort = RailEndpointRef(stem.id, RailEndpoint.B)
        val firstPort = RailEndpointRef(first.id, RailEndpoint.A)
        val secondPort = RailEndpointRef(second.id, RailEndpoint.A)
        val selection = TurnoutWandSelection(worldId, world.topologyState, stemPort)
    }
}
