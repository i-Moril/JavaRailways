package work.art1st.javarailways.basic.route

import work.art1st.javarailways.basic.api.route.planning.OperationalRouteLocation
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningInput
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningRejection
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanningResult
import work.art1st.javarailways.basic.api.route.planning.TopologyRoutePlanner
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.api.route.planning.RoutePlanningRestrictions
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.onOwnerForTest

/** Use actual authoring transactions, rather than supplying the planner a hand-written graph. */
class IncompleteTurnoutRoutingTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `same-side branches cannot be routed into each other before the stem exists`(reverseBranch: Boolean) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val straight = line(world, Vector3d(), Vector3d(50.0, 0.0, 0.0)).viewAB
            val branch = branch(world, reverseBranch)
            world.onOwnerForTest { RailView.connect(straight.reversed, branch.reversed) }

            assertTrue(straight.reversed.nextRails.isEmpty())
            assertTrue(branch.reversed.nextRails.isEmpty())
            assertNull(straight.getRailLocationAt(0.01).reversed.forward(0.02))
            assertUnreachable(plan(world, straight.reversed, 10.0, branch, 10.0))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `adding the stem through either branch enables both routes without branch crossover`(attachToBranch: Boolean) {
        for (reverseBranch in listOf(false, true)) {
            TestWorldFixture.create().use { fixture ->
                val world = fixture.world
                val straight = line(world, Vector3d(), Vector3d(50.0, 0.0, 0.0)).viewAB
                val branch = branch(world, reverseBranch)
                world.onOwnerForTest { RailView.connect(straight.reversed, branch.reversed) }
                val stem = line(world, Vector3d(-50.0, 0.0, 0.0), Vector3d()).viewAB
                world.onOwnerForTest { RailView.connect(stem, if (attachToBranch) branch.reversed else straight.reversed) }

                assertEquals(setOf(straight, branch), stem.nextRails.toSet())
                for (exit in listOf(straight, branch)) {
                    val planned = assertInstanceOf(TopologyRoutePlanningResult.Planned::class.java,
                        plan(world, stem, 40.0, exit, 10.0))
                    assertEquals(20.0, planned.route.totalLengthMeters, 1e-8)
                    assertNull(TopologyRoutePlanner.invalidRouteReason(world.topologyState, planned.route))
                    assertInstanceOf(TopologyRoutePlanningResult.Planned::class.java,
                        plan(world, exit.reversed, exit.curve.totalArcLength - 10.0, stem.reversed, 10.0))
                }
                assertUnreachable(plan(world, straight.reversed, 10.0, branch, 10.0))
                assertUnreachable(plan(world, branch.reversed, 10.0, straight, 10.0))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `removing the stem does not reconnect the remaining branches`(reverseBranch: Boolean) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val straight = line(world, Vector3d(), Vector3d(50.0, 0.0, 0.0)).viewAB
            val branch = branch(world, reverseBranch)
            val stem = line(world, Vector3d(-50.0, 0.0, 0.0), Vector3d())
            world.onOwnerForTest {
                RailView.connect(stem.viewAB, straight.reversed)
                RailView.connect(stem.viewAB, branch.reversed)
            }
            val previous = assertInstanceOf(TopologyRoutePlanningResult.Planned::class.java,
                plan(world, stem.viewAB, 40.0, branch, 10.0))

            world.onOwnerForTest { world.removeRail(stem) }

            assertTrue(straight.reversed.nextRails.isEmpty())
            assertTrue(branch.reversed.nextRails.isEmpty())
            assertUnreachable(plan(world, straight.reversed, 10.0, branch, 10.0))
            assertTrue(TopologyRoutePlanner.invalidRouteReason(world.topologyState, previous.route) != null)
        }
    }

    private fun plan(world: CoreWorld, from: RailView, fromArc: Double, to: RailView, toArc: Double): TopologyRoutePlanningResult =
        TopologyRoutePlanner.plan(TopologyRoutePlanningInput(
            topology = world.topologyState,
            restrictions = RoutePlanningRestrictions.unrestricted(world.topologyState),
            start = OperationalRouteLocation(from.rail.id, canonicalArc(from, fromArc)),
            end = OperationalRouteLocation(to.rail.id, canonicalArc(to, toArc)),
            routeId = "incomplete-turnout",
            routeRevision = 1L,
            startFacing = from.viewDirection,
            endFacing = to.viewDirection
        ))

    private fun canonicalArc(view: RailView, arc: Double): Double =
        if (view.viewDirection == ViewDirection.AB) arc else view.curve.totalArcLength - arc

    private fun assertUnreachable(result: TopologyRoutePlanningResult) {
        assertEquals(TopologyRoutePlanningRejection.UNREACHABLE,
            assertInstanceOf(TopologyRoutePlanningResult.Rejected::class.java, result).reason)
    }

    private fun branch(world: CoreWorld, reversed: Boolean): RailView = if (reversed) {
        line(world, Vector3d(50.0, 0.0, 10.0), Vector3d()).viewBA
    } else {
        line(world, Vector3d(), Vector3d(50.0, 0.0, 10.0)).viewAB
    }

    private fun line(world: CoreWorld, from: Vector3d, to: Vector3d): CoreRail {
        val tangent = Vector3d(to).sub(from).normalize()
        return world.createRailForTest(PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(from, tangent, Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(to, tangent, Vector3d(0.0, 1.0, 0.0), from.distance(to))
        )))
    }
}
