package work.art1st.javarailways.simplesignals.authoring

import org.bukkit.plugin.Plugin
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationListener
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationService
import work.art1st.javarailways.bukkit.api.observation.RailwayObservationSubscription
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.RailwayWorldFrameSnapshot
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitions
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.*
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.UUID

class RuntimeRoutePreviewBackendTest {
    private val world = UUID(1, 1)
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val c = UUID(0, 3)
    private val isolated = UUID(0, 4)
    private fun cut(id: String, rail: UUID, ab: Double, facing: ViewDirection) =
        ProtectionBoundaryDefinition("test:$id", RailPositionSnapshot(rail, facing, ab))
    private val entry = cut("entry", a, 2.0, ViewDirection.AB)
    private val exit = cut("exit", b, 8.0, ViewDirection.BA)
    private val wrong = cut("wrong", c, 8.0, ViewDirection.BA)

    @Test fun `real backend follows current branch rejects missing choices and never calls mutating services`() {
        val f = Fixture(topologyState(1, listOf(a, b, c), listOf(endpoint(a, RailEndpoint.B) to endpoint(b, RailEndpoint.A),
            endpoint(a, RailEndpoint.B) to endpoint(c, RailEndpoint.A))), listOf(entry, exit, wrong))
        val original = f.runtime.currentDefinitions()
        val root = f.root
        val chosen = f.backend.select(world, entry.position, null)
        val selection = f.backend.select(world, exit.position, chosen)
        f.active = ActiveRailContinuationsSnapshot.create(1, 1,
            mapOf(endpoint(a, RailEndpoint.B) to endpoint(c, RailEndpoint.A)))
        val wrongResult = f.backend.compute(selection)
        assertEquals(CurrentRouteEnd.WRONG_EXIT, wrongResult.path.end)
        assertEquals(c, wrongResult.path.reached.railId)
        assertTrue(wrongResult.drawing.points.any { it.kind == PreviewParticleKind.ACTUAL_END })
        f.active = ActiveRailContinuationsSnapshot.empty(1)
        assertFalse(f.backend.current(selection, wrongResult))
        assertEquals(CurrentRouteEnd.UNSELECTED, f.backend.compute(selection).path.end)
        assertSame(original, f.runtime.currentDefinitions())
        assertSame(root, f.root)
        assertEquals(0, f.mutations)
    }

    @Test fun `valid near-joint cut snaps on its own rail but is not merged with neighbouring lines`() {
        // Region authoring requires strictly interior cuts, even at an ordinary connected joint.
        val endpointExit = cut("exit", b, 0.5, ViewDirection.BA)
        val f = Fixture(topologyState(1, listOf(a, b, isolated), listOf(endpoint(a, RailEndpoint.B) to endpoint(b, RailEndpoint.A))),
            listOf(entry, endpointExit))
        val selected = f.backend.select(world, entry.position, null)
        val direct = f.backend.select(world, RailPositionSnapshot(b, ViewDirection.AB, 0.0), selected)
        assertEquals(endpointExit, direct.exit)
        val result = f.backend.compute(direct)
        assertEquals(CurrentRouteEnd.REACHED_EXIT, result.path.end)
        assertEquals(2, result.path.traversals.size)
        assertThrows(IllegalArgumentException::class.java) {
            f.backend.select(world, RailPositionSnapshot(a, ViewDirection.AB, 10.0), selected)
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.backend.select(world, RailPositionSnapshot(isolated, ViewDirection.AB, 0.0), selected)
        }
    }

    @Test fun `missing coherent observation and invalidated region have explicit different unavailable reasons`() {
        val f = Fixture(lineTopologyState(1, a, b), listOf(entry, exit))
        val selected = f.backend.select(world, exit.position, f.backend.select(world, entry.position, null))
        f.hasObservation = false
        assertTrue(f.backend.compute(selected).path.waitingForObservation)
        f.hasObservation = true
        f.active = ActiveRailContinuationsSnapshot.empty(0)
        assertTrue(f.backend.compute(selected).path.waitingForObservation)
        f.active = ActiveRailContinuationsSnapshot.empty(1)
        val changed = lineTopologyState(2, a, b)
        f.runtime.topologyGuard.invalidate(RailTopologyChange(f.root, changed, setOf(a)))
        f.root = changed
        val unavailable = f.backend.compute(selected)
        assertEquals(CurrentRouteEnd.UNAVAILABLE, unavailable.path.end)
        assertFalse(unavailable.path.waitingForObservation)
        assertTrue(unavailable.path.traversals.isEmpty())
    }

    @Test fun `same world UUID with new runtime rejects old selection and subscriptions close`() {
        val f = Fixture(lineTopologyState(1, a, b), listOf(entry, exit))
        val selected = f.backend.select(world, exit.position, f.backend.select(world, entry.position, null))
        var observed = 0
        val handles = f.backend.subscribe(selected, { observed++ }, {}, {})
        assertEquals(1, observed)
        handles.forEach { it.close() }
        assertEquals(2, f.closes)
        f.currentRuntime = SimpleSignalsWorldRuntime(world)
        assertThrows(IllegalStateException::class.java) { f.backend.compute(selected) }
        assertThrows(IllegalArgumentException::class.java) { f.backend.select(world, exit.position, selected) }
    }

    @Test fun `selected-region notification ignores other region edit and broken observer cannot reject commit`() {
        val secondEntry = cut("entry2", a, 8.0, ViewDirection.AB)
        val secondExit = cut("exit2", a, 9.0, ViewDirection.BA)
        val firstExit = cut("exit", a, 4.0, ViewDirection.BA)
        val f = Fixture(lineTopologyState(1, a), listOf(entry, firstExit))
        val otherRegion = ProtectionRegionDefinition("test:other", 0, listOf(secondEntry, secondExit))
        var events = 0
        f.runtime.observePreviewRegion("test:region") { events++ }
        var defs = f.runtime.currentDefinitions()
        val added = SimpleSignalsWorldDefinitions(world, defs.definitionRevision + 1, defs.regions + otherRegion, emptyList())
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, f.runtime.initializeDefinitions(f.root, added).status)
        assertEquals(0, events)
        f.runtime.observePreviewRegion("test:region") { error("UI scheduling failed") }
        defs = f.runtime.currentDefinitions()
        val changed = ProtectionRegionDefinition("test:region", 1, listOf(entry, firstExit.copy(position = firstExit.position.copy(arcLengthInAbMeters = 5.0))))
        assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, f.runtime.initializeDefinitions(f.root,
            SimpleSignalsWorldDefinitions(world, defs.definitionRevision + 1, listOf(changed, otherRegion), emptyList())).status)
        assertEquals(1, events)
        f.runtime.retire()
        assertEquals(2, events)
    }

    private inner class Fixture(var root: TopologyState, cuts: List<ProtectionBoundaryDefinition>) {
        val runtime = SimpleSignalsWorldRuntime(world)
        var currentRuntime: SimpleSignalsWorldRuntime? = runtime
        var active = ActiveRailContinuationsSnapshot.empty(root.revision)
        var hasObservation = true
        var mutations = 0
        var closes = 0
        private val plugin = proxy(Plugin::class.java) { _, _ -> error("No Bukkit access on preview worker") }
        init {
            val result = runtime.initializeDefinitions(root, SimpleSignalsWorldDefinitions(world, 1,
                listOf(ProtectionRegionDefinition("test:region", 0, cuts)), emptyList()))
            assertEquals(SimpleSignalsDefinitionPublicationStatus.PUBLISHED, result.status, result.diagnostics.joinToString())
        }
        private val infrastructure = proxy(InfrastructureResourceService::class.java) { name, _ -> when (name) {
            "currentTopology" -> root
            "observeTopologyChanges" -> AutoCloseable { closes++ }
            else -> { mutations++; error("Forbidden service call: $name") }
        } }
        private val observations = object : RailwayObservationService {
            override fun latest(worldId: UUID): Optional<RailwayObservation> = if (!hasObservation) Optional.empty() else Optional.of(
                RailwayObservation.fromCore(world.toString(), RailwayWorldFrameSnapshot.create(1, active.topologyRevision, emptyList(), active)))
            override fun subscribe(owner: Plugin, worldId: UUID, listener: RailwayObservationListener): RailwayObservationSubscription =
                RailwayObservationSubscription { closes++ }
            override fun unregisterOwner(owner: Plugin) { error("Preview must close its own subscription") }
        }
        private val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(10.0, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), 10.0, Curve.SegmentType.LINE_END)))
        private val targets = proxy(RailTargetService::class.java) { name, _ -> when (name) {
            "findCurve" -> Optional.of(curve)
            else -> error("No player targeting during computation")
        } }
        val backend = RuntimeRoutePreviewBackend(plugin, { currentRuntime }, infrastructure, observations, targets)
    }
    companion object {
        internal fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T = type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args -> when (method.name) {
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.get(0)
                "toString" -> type.simpleName
                else -> handler(method.name, args)
            } })
    }
}
