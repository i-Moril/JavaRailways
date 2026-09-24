package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.ThreadGuardPlatformBridge

class CoreTrainTailBogieTest {
    @Test
    fun `tail bogie uses last car in forward direction`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("t-tail", rail, world, listOf(10.0, 10.0), isLoop = false)

        assertSame(train.cars.last().bogieB, train.tailBogie)
    }

    @Test
    fun `tail bogie switches to first car when reversed`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("t-tail-reversed", rail, world, listOf(10.0, 10.0), isLoop = false)

        train.reverseOnMainThread()

        assertSame(train.cars.first().bogieA, train.tailBogie)
    }

    @Test
    fun `trailing bogies preserve siding path while first bogie enters merged main line`() {
        val world = createTestWorld()
        val main = world.createRail(
            straightCurve(Vector3d(-30.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        )
        val siding = world.createRail(
            straightCurve(Vector3d(-30.0, 0.0, 5.0), Vector3d(0.0, 0.0, 0.0))
        )
        val merged = world.createRail(
            straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        )
        RailView.connect(main.viewAB, merged.viewBA)
        RailView.connect(siding.viewAB, merged.viewBA)
        merged.viewBA.selectNextRail(main.viewBA)
        val train = createTrainWithConfig(
            "t-trailing-merge",
            siding,
            world,
            listOf(10.0, 10.0),
            isLoop = false
        )
        train.setLocation(RailLocationWithFacing(siding.viewAB, siding.curve.totalArcLength - 0.2))
        train.commit()

        AsyncTickTestSupport.run { train.moveTrainForward(1.0) }
        train.commit()

        assertFalse(train.isDerailed)
        assertSame(merged, train.headBogie.location.rail)
        assertSame(siding, train.cars.first().tailBogie.location.rail)
        assertSame(siding, train.cars.last().headBogie.location.rail)
        assertSame(siding, train.tailBogie.location.rail)
    }

    @Test
    fun `curved consist can move after stopping and reversing`() {
        val world = createTestWorld()
        val rail = world.createRail(PrimeCurveGenerator.buildCircle(30.0, segmentCount = 1).single())
        rail.viewAB.mutableNextRails.add(rail.viewAB)
        rail.viewAB.activeRail = rail.viewAB
        rail.viewBA.mutableNextRails.add(rail.viewBA)
        rail.viewBA.activeRail = rail.viewBA
        val initial = RailLocationWithFacing(rail.viewAB, 150.0)
        val cars = List(5) { index ->
            CoreCar(
                id = "curved-reverse-car-$index",
                bogies = listOf(CoreBogie.create(initial), CoreBogie.create(initial)),
                bogiesOffsets = listOf(5.75, -5.75),
                length = 17.8,
                floorHeight = 1.0,
                connectorLengthAtA = 0.1,
                connectorLengthAtB = 0.1,
                world = world,
                savedExtensionData = emptyMap()
            )
        }
        val train = CoreTrain("t-curved-reverse", cars, world, emptyMap())
        train.setLocation(initial)
        train.commit()
        AsyncTickTestSupport.run { train.moveTrainForward(0.5) }
        train.commit()
        assertFalse(train.isDerailed)

        train.reverseOnMainThread()
        // Simulate a projected-distance placement that is slightly compressed in the new
        // direction. The signed ±1m interval must be able to correct it behind the old tail.
        train.cars[2].bogies.forEach { bogie ->
            bogie.updateLocation(bogie.location.forward(0.02)!!)
        }
        train.commit()
        AsyncTickTestSupport.run { train.moveTrainForward(0.01) }
        train.commit()

        assertFalse(train.isDerailed)
        train.cars.zipWithNext().forEach { (front, rear) ->
            val frontTail = Vector3d(front.position).sub(
                Vector3d(front.direction).mul(front.length / 2.0)
            )
            val rearHead = Vector3d(rear.position).add(
                Vector3d(rear.direction).mul(rear.length / 2.0)
            )
            assertEquals(0.2, frontTail.distance(rearHead), 0.006)
        }
    }

    @Test
    fun `reversed train keeps cars spaced while moving`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(1000.0))
        val train = createTrainWithConfig("t-reverse-spacing", rail, world, listOf(10.0, 10.0, 10.0), isLoop = false)

        train.setLocation(RailLocationWithFacing(rail.viewAB, 150.0))
        train.commit()
        val directionsBeforeReverse = train.cars.map { org.joml.Vector3d(it.direction) }
        train.reverseOnMainThread()
        AsyncTickTestSupport.run { train.moveTrainForward(5.0) }
        train.commit()

        assertFalse(train.isDerailed, train.derailReason)
        val d01 = train.cars[0].position.distance(train.cars[1].position)
        val d12 = train.cars[1].position.distance(train.cars[2].position)
        assertEquals(10.0, d01, 0.5, "car 0/1 spacing")
        assertEquals(10.0, d12, 0.5, "car 1/2 spacing")
        train.cars.forEachIndexed { index, car ->
            assertEquals(1.0, directionsBeforeReverse[index].dot(car.direction), 1e-9, "car $index visual direction should not flip")
        }
    }

    private fun straightCurve(from: Vector3d, to: Vector3d): PrimeCurve {
        val tangent = Vector3d(to).sub(from).normalize()
        val normal = Vector3d(0.0, 1.0, 0.0)
        val length = from.distance(to)
        return PrimeCurve.fromPoints(
            listOf(
                Curve.RawPoint(from, tangent, normal, 0.0, Curve.SegmentType.LINE_START),
                Curve.RawPoint(to, tangent, normal, length, Curve.SegmentType.LINE_END)
            )
        )
    }

    private fun CoreTrain.reverseOnMainThread() {
        val owner = Thread.currentThread()
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            reverse()
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
    }
}
