package work.art1st.javarailways.basic.rail

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.configureTurnoutForTest
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import kotlin.math.cos
import kotlin.math.sin

class TurnoutTopologyTest {
    private val fixtures = mutableListOf<TestWorldFixture>()

    private fun newTestWorld() = TestWorldFixture.create().also { fixtures += it }.world

    @AfterEach
    fun closeTestWorlds() {
        var firstFailure: Throwable? = null
        fixtures.asReversed().forEach { fixture ->
            try {
                fixture.close()
            } catch (failure: Throwable) {
                val primary = firstFailure
                if (primary == null) firstFailure = failure else primary.addSuppressed(failure)
            }
        }
        fixtures.clear()
        firstFailure?.let { throw it }
    }

    @Test
    fun `turnout closure contains all three manually connected legs from any selected leg`() {
        val (stem, straight, branch) = turnout()
        val expected = setOf(stem.id, straight.id, branch.id)

        assertEquals(expected, TurnoutTopology.turnoutRailsTouching(listOf(stem)).mapTo(hashSetOf()) { it.id })
        assertEquals(expected, TurnoutTopology.turnoutRailsTouching(listOf(straight)).mapTo(hashSetOf()) { it.id })
        assertEquals(expected, TurnoutTopology.turnoutRailsTouching(listOf(branch)).mapTo(hashSetOf()) { it.id })
    }

    @Test
    fun `incomplete same-side pair retains full authored maintenance closure`() {
        val world = newTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }
        val straight = rail(world, Vector3d(), Vector3d(10.0, 0.0, 0.0))
        val branch = rail(world, Vector3d(), Vector3d(10.0, 0.0, 3.0))
        val unrelated = rail(world, Vector3d(), Vector3d(0.0, 0.0, 10.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(straight, branch) }

        assertTrue(straight.viewBA.nextRails.isEmpty())
        assertTrue(branch.viewBA.nextRails.isEmpty())
        assertTrue(TurnoutTopology.isTurnoutEndpoint(straight.viewBA))
        assertTrue(TurnoutTopology.hasTurnoutEndpoint(branch))
        assertFalse(TurnoutTopology.hasTurnoutEndpoint(unrelated))
        assertEquals(setOf(straight, branch), TurnoutTopology.turnoutRailsTouching(listOf(straight)))
        assertEquals(setOf(straight, branch), TurnoutTopology.turnoutRailsTouching(listOf(branch)))

        val stem = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d())
        IsolatedSimulationThreadMarker.run { CoreRail.connect(stem, branch) }
        assertEquals(setOf(straight, branch, stem), TurnoutTopology.turnoutRailsTouching(listOf(straight)))
        IsolatedSimulationThreadMarker.run { world.removeRail(stem) }
        assertTrue(straight.viewBA.nextRails.isEmpty())
        assertEquals(setOf(straight, branch), TurnoutTopology.turnoutRailsTouching(listOf(straight)))
    }

    @Test
    fun `ordinary two-leg continuation remains available for normal geometry editing`() {
        val world = newTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }
        val before = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d())
        val after = rail(world, Vector3d(), Vector3d(10.0, 0.0, 0.0))
        IsolatedSimulationThreadMarker.run { CoreRail.connect(before, after) }

        assertFalse(TurnoutTopology.hasTurnoutEndpoint(before))
        assertFalse(TurnoutTopology.hasTurnoutEndpoint(after))
        assertTrue(TurnoutTopology.turnoutRailsTouching(listOf(before, after)).isEmpty())
    }

    private fun turnout(): Triple<CoreRail, CoreRail, CoreRail> {
        val world = newTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }
        val stem = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val straight = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
        val radians = Math.toRadians(20.0)
        val branch = rail(
            world,
            Vector3d(0.0, 0.0, 0.0),
            Vector3d(cos(radians) * 10.0, 0.0, sin(radians) * 10.0)
        )
        configureTurnoutForTest(stem, listOf(straight, branch))
        return Triple(stem, straight, branch)
    }

    private fun rail(world: work.art1st.javarailways.core.physics.CoreWorld, from: Vector3d, to: Vector3d): CoreRail {
        val tangent = Vector3d(to).sub(from).normalize()
        val length = from.distance(to)
        val curve = PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(0.0, 1.0, 0.0), length)
        ))
        return world.createRailForTest(curve)
    }
}
