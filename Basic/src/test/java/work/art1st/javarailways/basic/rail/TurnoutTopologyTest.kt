package work.art1st.javarailways.basic.rail

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.configureTurnoutForTest
import work.art1st.javarailways.core.physics.train.createTestWorld
import kotlin.math.cos
import kotlin.math.sin

class TurnoutTopologyTest {
    @Test
    fun `split rejects a short replacement leg next to an existing turnout`() {
        val (stem, _, _) = turnout()

        assertNotNull(TurnoutTopology.splitLockingClearanceError(stem, 8.0, 5.0))
        assertNull(TurnoutTopology.splitLockingClearanceError(stem, 5.0, 5.0))
    }

    @Test
    fun `ordinary two-leg node does not impose turnout locking clearance`() {
        val world = createTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }
        val first = rail(world, Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        val second = rail(world, Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0))
        CoreRail.connect(first, second)

        assertNull(TurnoutTopology.splitLockingClearanceError(first, 9.5, 5.0))
    }

    @Test
    fun `every turnout leg is recognized regardless of stem direction`() {
        val (stem, straight, branch) = turnout()

        assertNotNull(TurnoutTopology.splitLockingClearanceError(stem, 8.0, 5.0))
        assertNotNull(TurnoutTopology.splitLockingClearanceError(straight, 2.0, 5.0))
        assertNotNull(TurnoutTopology.splitLockingClearanceError(branch, 2.0, 5.0))
    }

    @Test
    fun `turnout closure contains all three manually connected legs from any selected leg`() {
        val (stem, straight, branch) = turnout()
        val expected = setOf(stem.id, straight.id, branch.id)

        assertEquals(expected, TurnoutTopology.turnoutRailsTouching(listOf(stem)).mapTo(hashSetOf()) { it.id })
        assertEquals(expected, TurnoutTopology.turnoutRailsTouching(listOf(straight)).mapTo(hashSetOf()) { it.id })
        assertEquals(expected, TurnoutTopology.turnoutRailsTouching(listOf(branch)).mapTo(hashSetOf()) { it.id })
    }

    private fun turnout(): Triple<CoreRail, CoreRail, CoreRail> {
        val world = createTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }
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
