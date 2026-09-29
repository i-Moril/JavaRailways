package work.art1st.javarailways.simpleato.timetable

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyMutationResult
import work.art1st.javarailways.basic.api.infrastructure.InfrastructureDependencyMutationStatus
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.train.createTestWorld

class FrozenRouteEditProtectionTest {
    @Test
    fun `reversal retry retains the atomic union until Basic grants the new route`() {
        val world = createTestWorld()
        val oldRail = world.createRailForTest(straight(0.0, 40.0))
        val newRail = world.createRailForTest(straight(100.0, 140.0))
        val topology = world.topologyState
        val oldRoute = route("test:old", topology.revision, oldRail.id)
        val newRoute = route("test:new", topology.revision, newRail.id)
        val dependencies = RecordingInfrastructureDependencyService()
        val protection = FrozenRouteEditProtection("test:run/frozen-route", dependencies)

        assertInstanceOf(
            FrozenRouteProtectionResult.Protected::class.java,
            protection.protect(topology, oldRoute)
        )
        assertInstanceOf(
            FrozenRouteProtectionResult.Protected::class.java,
            protection.protectReversalTransition(topology, oldRoute, newRoute)
        )
        assertEquals(setOf(oldRail.id, newRail.id), dependencies.applied.values.single().dependencies.railIds)

        dependencies.setFailure = InfrastructureDependencyMutationResult(
            InfrastructureDependencyMutationStatus.EDIT_IN_PROGRESS,
            "test edit overlaps the replacement"
        )
        assertInstanceOf(
            FrozenRouteProtectionResult.Rejected::class.java,
            protection.protect(topology, newRoute)
        )
        assertEquals(setOf(oldRail.id, newRail.id), dependencies.applied.values.single().dependencies.railIds)
    }

    private fun route(id: String, topologyRevision: Long, railId: java.util.UUID) = RouteSnapshot(
        id,
        0L,
        topologyRevision,
        listOf(RouteSegmentSnapshot(railId, ViewDirection.AB, 0.0, 40.0))
    )

    private fun straight(from: Double, to: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(
            Vector3d(from, 0.0, 0.0),
            Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0),
            0.0,
            Curve.SegmentType.LINE_START
        ),
        Curve.RawPoint(
            Vector3d(to, 0.0, 0.0),
            Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0),
            to - from,
            Curve.SegmentType.LINE_END
        )
    ))
}
