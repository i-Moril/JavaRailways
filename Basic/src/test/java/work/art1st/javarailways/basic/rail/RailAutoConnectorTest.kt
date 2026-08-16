package work.art1st.javarailways.basic.rail

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.basic.pathFinding.TestBasicWorldDataRegistry
import work.art1st.javarailways.basic.rail.infrastructureMetadata as metadata
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.createTestWorld as createCoreTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import kotlin.math.cos
import kotlin.math.sin

class RailAutoConnectorTest {
    private fun createTestWorld() = createCoreTestWorld().also { TestBasicWorldDataRegistry.getOrCreate(it) }

    @Test
    fun `occupied endpoint rail must be taken out of service before connection`() {
        val world = createTestWorld()
        val first = CoreRail(
            world,
            straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0)),
            mutableListOf(),
            mutableListOf()
        )
        val second = CoreRail(
            world,
            straightCurve(Vector3d(30.0, 0.0, 0.0), Vector3d(60.0, 0.0, 0.0)),
            mutableListOf(),
            mutableListOf()
        )
        createTrainWithConfig("occupied-connect", first, world, listOf(8.0))

        assertThrows(IllegalArgumentException::class.java) {
            RailAutoConnector.connectIfAllowed(first, second)
        }
        assertFalse(RailAutoConnector.areConnected(first, second))
    }

    @Test
    fun `third occupied endpoint rail prevents turnout reconfiguration`() {
        val world = createTestWorld()
        val stem = CoreRail(
            world,
            straightCurve(Vector3d(-30.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)),
            mutableListOf(),
            mutableListOf()
        )
        val straight = CoreRail(
            world,
            straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(40.0, 0.0, 0.0)),
            mutableListOf(),
            mutableListOf()
        )
        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(stem, straight).outcome)
        createTrainWithConfig("occupied-third-leg", straight, world, listOf(8.0))
        val branch = CoreRail(
            world,
            angledCurveFrom(Vector3d(0.0, 0.0, 0.0), 20.0, length = 40.0),
            mutableListOf(),
            mutableListOf()
        )

        assertThrows(IllegalArgumentException::class.java) {
            RailAutoConnector.connectIfAllowed(stem, branch)
        }

        assertTrue(branch.viewBA.nextRails.isEmpty())
        assertEquals(listOf(straight.viewAB), stem.viewAB.nextRails)
    }

    @Test
    fun `auto connector rejects sharp endpoint branch`() {
        val world = createTestWorld()
        val approach = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val side = CoreRail(world, straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(10.0, 0.0, 10.0)), mutableListOf(), mutableListOf())

        val result = RailAutoConnector.connectIfAllowed(approach, side)

        assertEquals(RailAutoConnector.Outcome.ANGLE_TOO_SHARP, result.outcome)
        assertTrue((result.angleDegrees ?: 0.0) > 80.0)
        assertEquals(0, approach.viewAB.nextRails.size)
        assertEquals(0, side.viewBA.nextRails.size)
    }

    @Test
    fun `auto connector allows thirty degree endpoint transition`() {
        val world = createTestWorld()
        val approach = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val turnout = CoreRail(world, angledCurveFrom(Vector3d(10.0, 0.0, 0.0), 30.0), mutableListOf(), mutableListOf())

        val result = RailAutoConnector.connectIfAllowed(approach, turnout)

        assertEquals(RailAutoConnector.Outcome.CONNECTED, result.outcome)
        assertTrue((result.angleDegrees ?: 180.0) <= 30.000001)
        assertSame(turnout.viewAB, approach.viewAB.nextActiveRail)
        assertSame(approach.viewBA, turnout.viewBA.nextActiveRail)
    }

    @Test
    fun `created rail can select smoothest active branch after unordered auto connects`() {
        val world = createTestWorld()
        val created = CoreRail(world, straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val branch = CoreRail(world, curvedBranchEndingAt(Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val straight = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(created, branch).outcome)
        assertSame(branch.viewBA, created.viewBA.nextActiveRail)
        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(created, straight).outcome)
        assertSame(branch.viewBA, created.viewBA.nextActiveRail)

        assertTrue(RailAutoConnector.selectSmoothestActiveRail(created.viewBA))

        assertEquals(2, created.viewBA.nextRails.size)
        assertSame(straight.viewBA, created.viewBA.nextActiveRail)
    }

    @Test
    fun `auto connector preserves existing active rail when adding another legal branch`() {
        val world = createTestWorld()
        val approach = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val straight = CoreRail(world, straightCurve(Vector3d(10.0, 0.0, 0.0), Vector3d(20.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val turnout = CoreRail(world, angledCurveFrom(Vector3d(10.0, 0.0, 0.0), 20.0), mutableListOf(), mutableListOf())

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(approach, straight).outcome)
        assertSame(straight.viewAB, approach.viewAB.nextActiveRail)

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(approach, turnout).outcome)

        assertEquals(2, approach.viewAB.nextRails.size)
        assertTrue(approach.viewAB.nextRails.contains(straight.viewAB))
        assertTrue(approach.viewAB.nextRails.contains(turnout.viewAB))
        assertSame(straight.viewAB, approach.viewAB.nextActiveRail)
    }

    @Test
    fun `three-leg turnout connects stem to both branches but never branch to branch`() {
        val world = createTestWorld()
        val stem = CoreRail(world, straightCurve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val straight = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(stem, straight).outcome)
        val branch = CoreRail(world, angledCurveFrom(Vector3d(0.0, 0.0, 0.0), 20.0), mutableListOf(), mutableListOf())
        val settings = TurnoutEndpointMetadata(30.0, 7.0)
        branch.metadata = branch.metadata.copy(turnoutAtA = settings)

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(stem, branch).outcome)

        assertEquals(listOf(straight.viewAB, branch.viewAB), stem.viewAB.nextRails)
        assertEquals(listOf(stem.viewBA), straight.viewBA.nextRails)
        assertEquals(listOf(stem.viewBA), branch.viewBA.nextRails)
        assertTrue(straight.viewBA.nextRails.none { it === branch.viewAB })
        assertTrue(branch.viewBA.nextRails.none { it === straight.viewAB })
        assertEquals(settings, stem.metadata.turnoutAtB)
        assertEquals(settings, straight.metadata.turnoutAtA)
        assertEquals(settings, branch.metadata.turnoutAtA)
    }

    @Test
    fun `connector validates against transaction target topology instead of unpublished rails`() {
        val world = createTestWorld()
        val unpublished = CoreRail(world, straightCurve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val replacement = CoreRail(world, straightCurve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val straight = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val branch = CoreRail(world, angledCurveFrom(Vector3d(0.0, 0.0, 0.0), 20.0), mutableListOf(), mutableListOf())
        val targetTopology = listOf(replacement, straight, branch)
        val options = RailAutoConnector.Options(topologyRails = targetTopology)

        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(replacement, branch, options).outcome)
        assertEquals(RailAutoConnector.Outcome.CONNECTED, RailAutoConnector.connectIfAllowed(replacement, straight, options).outcome)
        assertEquals(setOf(straight.viewAB, branch.viewAB), replacement.viewAB.nextRails.toSet())
        assertTrue(unpublished.viewAB.nextRails.isEmpty())
    }

    @Test
    fun `fourth endpoint is rejected without changing the turnout`() {
        val world = createTestWorld()
        val stem = CoreRail(world, straightCurve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val straight = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        RailAutoConnector.connectIfAllowed(stem, straight)
        val branch = CoreRail(world, angledCurveFrom(Vector3d(0.0, 0.0, 0.0), 20.0), mutableListOf(), mutableListOf())
        RailAutoConnector.connectIfAllowed(stem, branch)
        val before = stem.viewAB.nextRails.toList()
        val fourth = CoreRail(world, angledCurveFrom(Vector3d(0.0, 0.0, 0.0), -20.0), mutableListOf(), mutableListOf())

        val result = RailAutoConnector.connectIfAllowed(stem, fourth)

        assertEquals(RailAutoConnector.Outcome.TOO_MANY_ENDPOINTS, result.outcome)
        assertEquals(before, stem.viewAB.nextRails)
        assertTrue(fourth.viewBA.nextRails.isEmpty())
    }

    @Test
    fun `geometrically ambiguous three-leg node is rejected`() {
        val world = createTestWorld()
        val west = CoreRail(world, straightCurve(Vector3d(-10.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        val east = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(10.0, 0.0, 0.0)), mutableListOf(), mutableListOf())
        RailAutoConnector.connectIfAllowed(west, east)
        val north = CoreRail(world, straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(0.0, 0.0, 10.0)), mutableListOf(), mutableListOf())

        val result = RailAutoConnector.connectIfAllowed(west, north)

        assertEquals(RailAutoConnector.Outcome.AMBIGUOUS_TURNOUT, result.outcome)
        assertTrue(north.viewBA.nextRails.isEmpty())
        assertEquals(listOf(east.viewAB), west.viewAB.nextRails)
    }

    private fun angledCurveFrom(from: Vector3d, angleDegrees: Double, length: Double = 10.0): PrimeCurve {
        val radians = Math.toRadians(angleDegrees)
        val to = Vector3d(
            from.x + cos(radians) * length,
            from.y,
            from.z + sin(radians) * length
        )
        return straightCurve(from, to)
    }

    private fun curvedBranchEndingAt(to: Vector3d): PrimeCurve {
        val normal = Vector3d(0.0, 1.0, 0.0)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(to.x - 10.0, to.y, to.z + 2.0), Vector3d(1.0, 0.0, -0.2).normalize(), Vector3d(normal), 0.0),
            Curve.RawPoint(Vector3d(to.x - 5.0, to.y, to.z + 1.0), Vector3d(1.0, 0.0, -0.2).normalize(), Vector3d(normal), 5.1),
            Curve.RawPoint(Vector3d(to), Vector3d(1.0, 0.0, 0.0), Vector3d(normal), 10.2)
        ))
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(Vector3d(from), Vector3d(tangent), Vector3d(normal), 0.0, Curve.SegmentType.LINE_START),
            Curve.RawPoint(Vector3d(to), Vector3d(tangent), Vector3d(normal), length, Curve.SegmentType.LINE_END)
        ))
    }
}
