package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.di.NoOpWorldParentContext
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.util.AsyncTickTestSupport
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.Executors

class CoreTrainTailBogieTest {
    @Test
    fun `tail bogie uses last car in forward direction`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("t-tail", rail, world, listOf(10.0, 10.0), isLoop = false)

        assertSame(train.cars.last().bogieB, train.tailBogie)
    }

    @Test
    fun `tail bogie switches to first car when reversed`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0))
        val train = createTrainWithConfig("t-tail-reversed", rail, world, listOf(10.0, 10.0), isLoop = false)

        train.reverseOnMainThread()

        assertSame(train.cars.first().bogieA, train.tailBogie)
    }

    @Test
    fun `trailing bogies preserve siding path while first bogie enters merged main line`() = withWorld { world ->
        val main = world.createRailForTest(
            straightCurve(Vector3d(-30.0, 0.0, 0.0), Vector3d(0.0, 0.0, 0.0))
        )
        val siding = world.createRailForTest(
            straightCurve(Vector3d(-30.0, 0.0, 5.0), Vector3d(0.0, 0.0, 0.0))
        )
        val merged = world.createRailForTest(
            straightCurve(Vector3d(0.0, 0.0, 0.0), Vector3d(30.0, 0.0, 0.0))
        )
        IsolatedSimulationThreadMarker.run { RailView.connect(main.viewAB, merged.viewBA) }
        IsolatedSimulationThreadMarker.run { RailView.connect(siding.viewAB, merged.viewBA) }
        merged.viewBA.selectNextRail(main.viewBA)
        val train = createTrainWithConfig(
            "t-trailing-merge",
            siding,
            world,
            listOf(10.0, 10.0),
            isLoop = false
        )
        train.world.onOwnerForTest {
            train.setLocation(RailLocationWithFacing(siding.viewAB, siding.curve.totalArcLength - 0.2))
            train.commit()
        }

        AsyncTickTestSupport.run { train.moveTrainForward(1.0) }
        train.commit()

        assertFalse(train.isDerailed)
        assertSame(merged, train.headBogie.location.rail)
        assertSame(siding, train.cars.first().tailBogie.location.rail)
        assertSame(siding, train.cars.last().headBogie.location.rail)
        assertSame(siding, train.tailBogie.location.rail)
    }

    @Test
    fun `curved consist can move after stopping and reversing`() = withWorld { world ->
        val rail = world.createRailForTest(PrimeCurveGenerator.buildCircle(30.0, segmentCount = 1).single())
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
        val train = world.onOwnerForTest { CoreTrain("t-curved-reverse", cars, world, emptyMap()) }
        train.world.onOwnerForTest {
            train.setLocation(initial)
            train.commit()
        }
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
    fun `reversed train keeps cars spaced while moving`() = withWorld { world ->
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(1000.0))
        val train = createTrainWithConfig("t-reverse-spacing", rail, world, listOf(10.0, 10.0, 10.0), isLoop = false)

        train.world.onOwnerForTest {
            train.setLocation(RailLocationWithFacing(rail.viewAB, 150.0))
            train.commit()
        }
        val directionsBeforeReverse = train.cars.map { org.joml.Vector3d(it.direction) }
        val operatingDirectionsBeforeReverse = train.cars.map { org.joml.Vector3d(it.operatingDirection) }
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
            assertEquals(
                -1.0,
                operatingDirectionsBeforeReverse[index].dot(car.operatingDirection),
                1e-9,
                "car $index operating direction should flip"
            )
        }
    }

    private fun withWorld(block: (CoreWorld) -> Unit) {
        TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
            var world: CoreWorld? = null
            AutoCloseable { world?.releaseRuntimeBinding() }.use {
                PhysicsCoordinator(1, automaticFallback = false).use { physics ->
                    val created = CoreWorld.createEmpty(tick, NoOpWorldParentContext, physics)
                    world = created
                    block(created)
                }
            }
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
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        })
        try {
            reverse()
        } finally {
            registration.close()
        }
    }
}
