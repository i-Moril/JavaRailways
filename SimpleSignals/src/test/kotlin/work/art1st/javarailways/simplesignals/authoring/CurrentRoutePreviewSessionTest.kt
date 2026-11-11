package work.art1st.javarailways.simplesignals.authoring

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.*
import work.art1st.javarailways.simplesignals.region.*
import work.art1st.javarailways.simplesignals.runtime.*
import java.util.ArrayDeque
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class CurrentRoutePreviewSessionTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val other = UUID(0, 3)
    private val watched = endpoint(a, RailEndpoint.B)
    private val exitPort = endpoint(b, RailEndpoint.A)
    private val entry = ProtectionBoundaryDefinition("test:entry", RailPositionSnapshot(a, ViewDirection.AB, 2.0))
    private val exit = ProtectionBoundaryDefinition("test:exit", RailPositionSnapshot(b, ViewDirection.BA, 8.0))
    private val region = ProtectionRegionDefinition("test:region", 0, listOf(entry, exit))
    private fun active(revision: Long, value: RailEndpointRef? = exitPort, extra: Map<RailEndpointRef, RailEndpointRef> = emptyMap()) =
        ActiveRailContinuationsSnapshot.create(1, revision, extra + if (value == null) emptyMap() else mapOf(watched to value))

    @Test fun `computation failure reports original exception and closes all subscriptions without retry`() {
        val f = Fixture()
        val failure = IllegalStateException("geometry failed")
        f.duringCompute = { throw failure }
        f.start()
        assertEquals(3, f.closes)
        assertNull(f.session.visible())
        assertTrue(f.workers.isEmpty())
        assertTrue(f.outputs.isEmpty())
        assertSame(failure, f.records.single().thrown)
        assertTrue(f.records.single().message.contains(f.runtime.worldId.toString()))
    }

    @Test fun `unchanged observation pointer and unrelated choices and topology do no geometry work`() {
        val f = Fixture(); f.start()
        val first = f.session.visible()
        f.observation(f.active)
        f.observation(active(2, extra = mapOf(endpoint(other, RailEndpoint.A) to endpoint(other, RailEndpoint.B))))
        val old = lineTopologyState(1, a, b, other)
        f.topology(RailTopologyChange(old, lineTopologyState(2, a, b, other), setOf(other)))
        assertTrue(f.workers.isEmpty())
        assertSame(first, f.session.visible())
        assertEquals(1, f.computes)
    }

    @Test fun `related changes coalesce into one worker and old display is immediately invisible`() {
        val f = Fixture(); f.start()
        repeat(20) { f.observation(active((it + 2).toLong(), endpoint(other, RailEndpoint.A))) }
        assertEquals(1, f.workers.size)
        assertNull(f.session.visible())
        f.drain()
        assertEquals(2, f.computes)
        assertNotNull(f.session.visible())
    }

    @Test fun `missing turnout choice is watched and later selection recovers`() {
        val f = Fixture(); f.active = active(1, null); f.start()
        assertTrue(f.session.visible()!!.path.watchedContinuations.containsKey(watched))
        f.observation(active(2))
        assertEquals(1, f.workers.size)
        f.drain()
        assertEquals(exitPort, f.session.visible()!!.path.watchedContinuations[watched])
    }

    @Test fun `permanent current false stops without spinning then actual input event can recover`() {
        val f = Fixture(); f.valid = false; f.start()
        assertEquals(1, f.computes)
        assertTrue(f.workers.isEmpty())
        assertNull(f.session.visible())
        f.valid = true
        f.regionChanged(PreviewRegionState(region, false))
        f.drain()
        assertEquals(2, f.computes)
        assertNotNull(f.session.visible())
    }

    @Test fun `unavailable region ignores unrelated observation changes while coherent observation wait recovers`() {
        val f = Fixture(); f.unavailable = true; f.start()
        f.observation(active(2, extra = mapOf(endpoint(other, RailEndpoint.A) to endpoint(other, RailEndpoint.B))))
        assertTrue(f.workers.isEmpty())
        assertEquals(1, f.computes)
        val waiting = Fixture(); waiting.waiting = true; waiting.start()
        waiting.waiting = false
        waiting.observation(active(2))
        waiting.drain()
        assertEquals(2, waiting.computes)
    }

    @Test fun `related rail or selected region change recomputes and closed session rejects queued results`() {
        val f = Fixture(); f.start()
        val root = lineTopologyState(1, a, b)
        f.topology(RailTopologyChange(root, lineTopologyState(2, a, b), setOf(b)))
        assertEquals(1, f.workers.size)
        f.session.close()
        f.drain()
        assertEquals(1, f.computes)
        assertEquals(3, f.closes)
        assertNull(f.session.visible())
        f.observation(active(3)); f.regionChanged(PreviewRegionState(null, true))
        assertTrue(f.workers.isEmpty())
    }

    @Test fun `close during subscription attachment disposes late handles and never starts worker`() {
        val f = Fixture(); f.duringSubscribe = { f.session.close() }
        f.session.start()
        assertEquals(3, f.closes)
        f.drain() // A first observation may already have enqueued work before retirement.
        assertEquals(0, f.computes)
        assertTrue(f.workers.isEmpty())
    }

    @Test fun `new branch watched port changes during geometry computation and recaptures actual fresh choice`() {
        val f = Fixture(); f.start()
        val newPort = endpoint(other, RailEndpoint.B)
        f.extraWatch = newPort
        f.valid = false
        f.duringCompute = {
            f.observation(active(3, extra = mapOf(newPort to endpoint(other, RailEndpoint.A))))
            f.duringCompute = { f.valid = true }
        }
        f.regionChanged(PreviewRegionState(region, false))
        f.drain()
        assertEquals(3, f.computes)
        assertEquals(endpoint(other, RailEndpoint.A), f.session.visible()!!.path.watchedContinuations[newPort])
    }

    private inner class Fixture {
        val runtime = SimpleSignalsWorldRuntime(UUID(1, 1))
        val selection = RoutePreviewSelection(runtime.worldId, runtime, region.regionId, entry, exit, setOf(a, b))
        val workers = ArrayDeque<() -> Unit>()
        var computes = 0
        var closes = 0
        var valid = true
        var unavailable = false
        var waiting = false
        var active = active(1)
        var extraWatch: RailEndpointRef? = null
        var duringCompute: (() -> Unit)? = null
        var duringSubscribe: (() -> Unit)? = null
        lateinit var observation: (ActiveRailContinuationsSnapshot) -> Unit
        lateinit var topology: (RailTopologyChange) -> Unit
        lateinit var regionChanged: (PreviewRegionState) -> Unit
        val outputs = mutableListOf<RoutePreviewComputation>()
        val records = mutableListOf<LogRecord>()
        private val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) { records += record }
                override fun flush() = Unit
                override fun close() = Unit
            })
        }
        val backend = object : RoutePreviewBackend {
            override fun select(worldId: UUID, point: RailPositionSnapshot, entry: RoutePreviewSelection?) = selection
            override fun compute(selection: RoutePreviewSelection): RoutePreviewComputation {
                computes++
                check(computes < 10) { "Unbounded retry" }
                val captured = active
                val watches = if (unavailable || waiting) emptyMap() else mapOf(watched to captured.selectedContinuation(watched)) +
                    (extraWatch?.let { mapOf(it to captured.selectedContinuation(it)) } ?: emptyMap())
                val path = CurrentRoutePath(entry, exit, emptyList(), entry.position,
                    if (unavailable || waiting) CurrentRouteEnd.UNAVAILABLE else CurrentRouteEnd.REACHED_EXIT,
                    "test", waitingForObservation = waiting, watchedContinuations = watches)
                duringCompute?.invoke()
                return RoutePreviewComputation(path, PreviewRegionState(region, unavailable), setOf(a, b),
                    RoutePreviewDrawing(emptyList(), false), captured)
            }
            override fun current(selection: RoutePreviewSelection, computation: RoutePreviewComputation) = valid
            override fun subscribe(selection: RoutePreviewSelection, observation: (ActiveRailContinuationsSnapshot) -> Unit,
                topology: (RailTopologyChange) -> Unit, region: (PreviewRegionState) -> Unit): List<AutoCloseable> {
                this@Fixture.observation = { active = it; observation(it) }
                this@Fixture.topology = topology
                this@Fixture.regionChanged = region
                observation(active)
                duringSubscribe?.invoke()
                return List(3) { AutoCloseable { closes++ } }
            }
        }
        val session = CurrentRoutePreviewSession(selection, backend, { workers += it }, logger, outputs::add)
        fun start() { session.start(); drain() }
        fun drain() { while (workers.isNotEmpty()) workers.removeFirst().invoke() }
    }
}
