package work.art1st.javarailways.core.physics.rail

import work.art1st.javarailways.core.physics.train.onOwnerForTest

import work.art1st.javarailways.core.physics.createRailForTest

import org.joml.Vector3d
import org.joml.Vector3dc
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.CoreBogie
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreTrain
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PhysOvalConnectionTest {
    @Test
    fun `phys oval connects and forwards across every joint`() = withWorld { world ->
        val rails = createPhysOvalRails(world)

        rails.indices.forEach { i ->
            val current = rails[i]
            val next = rails[(i + 1) % rails.size]
            assertSame(next.viewAB, current.viewAB.nextActiveRail, "rail $i should connect to next rail AB")

            val beforeJoint = RailLocationWithFacing(current.viewAB, current.curve.totalArcLength - 0.5)
            val afterJoint = beforeJoint.forward(1.0)
            assertNotNull(afterJoint, "rail $i should forward across joint")
            assertSame(next, afterJoint!!.rail, "rail $i should forward onto next rail")
        }
    }

    @Test
    fun `multi car train moves across phys oval straight arc joint`() = withWorld { world ->
        val rails = createPhysOvalRails(world)
        val train = createCommandStyleTrain(world, rails[0], carCount = 4, carLength = 2.0)
        val beforeJoint = RailLocationWithFacing(rails[0].viewAB, rails[0].curve.totalArcLength - 0.5)

        train.world.onOwnerForTest {
            train.setLocation(beforeJoint)
            train.commit()
        }
        train.moveTrainForward(1.0)
        train.commit()

        assertFalse(train.isDerailed)
        assertSame(rails[1], train.head.location.rail)
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        val world = createTestWorld()
        (world.tickManager as AutoCloseable).use {
            AutoCloseable { world.releaseRuntimeBinding() }.use {
                world.physicsCoordinator.use { block(world) }
            }
        }
    }

    private fun createPhysOvalRails(world: work.art1st.javarailways.core.physics.CoreWorld): List<CoreRail> {
        val origin = Vector3d(0.0, 64.0, 0.0)
        val normal = Vector3d(0.0, 1.0, 0.0)
        val straightLen = 200.0
        val radius = 80.0
        val step = 0.005

        val s1 = makeStraight(origin, Vector3d(1.0, 0.0, 0.0), straightLen, normal)
        val a1 = makeArcRightTurn(Vector3d(s1.lastPoint().position), radius, fromEast = true, normal, step)
        val s2 = makeStraight(Vector3d(a1.lastPoint().position), Vector3d(-1.0, 0.0, 0.0), straightLen, normal)
        val a2 = makeArcRightTurn(Vector3d(s2.lastPoint().position), radius, fromEast = false, normal, step)

        val rails = listOf(s1, a1, s2, a2).map(world::createRailForTest)
        CoreRail.connect(rails[0], rails[1])
        CoreRail.connect(rails[1], rails[2])
        CoreRail.connect(rails[2], rails[3])
        CoreRail.connect(rails[3], rails[0])
        return rails
    }

    private fun makeStraight(start: Vector3dc, dir: Vector3dc, length: Double, normal: Vector3dc): PrimeCurve {
        val endPos = Vector3d(start).add(Vector3d(dir).mul(length), Vector3d())
        return PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(Vector3d(start), Vector3d(dir), normal, 0.0, Curve.SegmentType.LINE_START),
                Curve.RawPoint(endPos, Vector3d(dir), normal, length, Curve.SegmentType.LINE_END)
            )
        )
    }

    private fun makeArcRightTurn(
        startPoint: Vector3dc,
        radius: Double,
        fromEast: Boolean,
        normal: Vector3dc,
        step: Double
    ): PrimeCurve {
        val (startAngle, endAngle) = if (fromEast) -PI / 2 to PI / 2 else PI / 2 to 3 * PI / 2
        val center = Vector3d(startPoint).add(0.0, 0.0, -radius * sin(startAngle), Vector3d())
        val n = maxOf(200, (radius * PI / step).toInt())
        val pts = mutableListOf<Curve.RawPoint>()
        pts.add(Curve.RawPoint(Vector3d(startPoint), Vector3d(-sin(startAngle), 0.0, cos(startAngle)), normal, 0.0))
        var arcLen = 0.0
        for (i in 1..n) {
            val a = startAngle + (endAngle - startAngle) * i / n
            val p = Vector3d(center).add(radius * cos(a), 0.0, radius * sin(a), Vector3d())
            val t = Vector3d(-sin(a), 0.0, cos(a))
            arcLen += p.distance(pts.last().position)
            pts.add(Curve.RawPoint(p, t, normal, arcLen))
        }
        return PrimeCurve.fromPoints(pts)
    }

    private fun PrimeCurve.lastPoint(): Curve.Point = getPointAt(totalArcLength)

    private fun createCommandStyleTrain(
        world: work.art1st.javarailways.core.physics.CoreWorld,
        rail: CoreRail,
        carCount: Int,
        carLength: Double
    ): CoreTrain {
        var currentLocation = RailLocationWithFacing(rail.viewAB, 0.0)
        val cars = mutableListOf<CoreCar>()

        repeat(carCount) { index ->
            val bogieA = CoreBogie.create(currentLocation)
            val nextLocation = currentLocation.forward(carLength) ?: error("cannot place bogieB")
            val bogieB = CoreBogie.create(nextLocation)
            cars += CoreCar.create(
                id = "phys-oval-car-$index",
                bogieA = bogieA,
                bogieB = bogieB,
                length = carLength,
                world = world
            )
            currentLocation = nextLocation
        }

        return world.onOwnerForTest { CoreTrain.create("phys-oval-train", cars, world) }
    }
}
