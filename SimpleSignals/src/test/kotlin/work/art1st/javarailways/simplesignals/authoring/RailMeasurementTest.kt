package work.art1st.javarailways.simplesignals.authoring

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.api.infrastructure.InfrastructureResourceService
import work.art1st.javarailways.bukkit.api.rail.RailTargetService
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.simplesignals.authoring.RuntimeRoutePreviewBackendTest.Companion.proxy
import work.art1st.javarailways.simplesignals.region.topologyState
import java.util.Optional
import java.util.UUID

class RailMeasurementTest {
    @Test fun `real backend separates3D chord from arc and needs no signal runtime or mutating host service`() {
        val world = UUID(0, 1); val rail = UUID(0, 2); val disconnected = UUID(0, 3)
        var root = topologyState(1, listOf(rail, disconnected), emptyList())
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(), Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 0.0, 1.0), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(5.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0), 5.0, Curve.SegmentType.LINE_END),
            Curve.RawPoint(Vector3d(5.0, 5.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0), 10.0, Curve.SegmentType.LINE_END)))
        val infrastructure = proxy(InfrastructureResourceService::class.java) { name, _ ->
            check(name == "currentTopology") { "Measuring must not consult operations or mutate resources: $name" }; root
        }
        val targets = proxy(RailTargetService::class.java) { name, _ -> check(name == "findCurve"); Optional.of(curve) }
        val backend = RailMeasurementBackend(infrastructure, targets)
        val captured = backend.capture(world)
        val first = backend.select(captured, rail, 0.0)
        val end = backend.select(captured, rail, 10.0)
        val result = backend.measure(captured, first, end)
        assertEquals(kotlin.math.sqrt(50.0), result.straightMeters, 1e-9)
        assertEquals(10.0, result.trackMeters)
        assertEquals(RailMeasurementResult(0.0, 0.0), backend.measure(captured, first, first))
        val other = backend.select(captured, disconnected, 10.0)
        val separate = backend.measure(captured, first, other)
        assertEquals(result.straightMeters, separate.straightMeters)
        assertNull(separate.trackMeters)
        assertTrue(MeasurementWandHandler.format(separate).contains("disconnected"))
        root = topologyState(2, listOf(rail, disconnected), emptyList())
        assertFalse(backend.current(captured))
        assertThrows(IllegalArgumentException::class.java) { backend.measure(captured, first, end) }
    }

    @Test fun `missing or shortened selected geometry cannot fabricate a measurement`() {
        val world = UUID(0, 1); val rail = UUID(0, 2)
        val root = topologyState(1, listOf(rail), emptyList())
        var curve: PrimeCurve? = null
        val infrastructure = proxy(InfrastructureResourceService::class.java) { _, _ -> root }
        val targets = proxy(RailTargetService::class.java) { _, _ -> Optional.ofNullable(curve) }
        val backend = RailMeasurementBackend(infrastructure, targets)
        val captured = backend.capture(world)
        assertThrows(IllegalArgumentException::class.java) { backend.select(captured, rail, 5.0) }
        curve = SectionOverlayDrawingTest.curve(4.0)
        assertThrows(IllegalArgumentException::class.java) { backend.select(captured, rail, 5.0) }
        assertThrows(IllegalArgumentException::class.java) { backend.select(captured, rail, -1.0) }
    }
}
