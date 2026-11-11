package work.art1st.javarailways.core.physics.train

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.RailTraversalSegmentSnapshot
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator

class StoppedBodyGeometryTest {
    private fun path(rail: CoreRail, start: Double, end: Double, facing: ViewDirection = ViewDirection.AB) =
        RailTraversalSegmentSnapshot(rail.id, facing, start, end)
    private fun assertInside(envelope: StoppedBodyEnvelope, train: CoreTrain) {
        envelope.requireCurrent(train.publishedFrame, train.world.topologyState, train.cars.map { it.id })
        for (span in train.publishedFrame.bodyTraversal) {
            assertTrue(envelope.bodyHull.any { retained -> retained.railId == span.railId && retained.facing == span.facing &&
                minOf(retained.startArcLengthInAbMeters, retained.endArcLengthInAbMeters) <= minOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters) &&
                maxOf(retained.startArcLengthInAbMeters, retained.endArcLengthInAbMeters) >= maxOf(span.startArcLengthInAbMeters, span.endArcLengthInAbMeters) },
                "actual complete rear span escaped conservative hull: $span / ${envelope.bodyHull}")
        }
    }

    @ParameterizedTest @ValueSource(ints = [2, 12])
    fun `explicit straight stop window bounds actual complete bodies including long trains`(cars: Int) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(500.0, 1))
            val train = createTrainWithConfig("source", rail, world, List(cars) { 10.0 }, connectorLength = 0.25)
            world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(160.0)); train.commit() }
            val geometry = StoppedBodyGeometry.capture(train)
            val source = listOf(path(rail, train.publishedFrame.tail.arcLengthInAbMeters, 381.0))
            val envelope = geometry.enclose(world.topologyState, source, rail.id, ViewDirection.AB, 379.0, 381.0, train.cars.map { it.id })
            assertTrue(envelope.bodyHull.first().startArcLengthInAbMeters > 160.0)
            for (head in listOf(379.0, 379.25, 380.0, 380.75, 381.0)) {
                world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(head)); train.commit() }
                assertInside(envelope, train)
            }
        }
    }

    @Test fun `curved path keeps the actual rear across the entire tested stopping window`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildCircle(30.0, 1, step = 0.02))
            val train = createTrainWithConfig("curve", rail, world, List(4) { 10.0 }, connectorLength = 0.25)
            world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(80.0)); train.commit() }
            val geometry = StoppedBodyGeometry.capture(train)
            val envelope = geometry.enclose(world.topologyState,
                listOf(path(rail, train.publishedFrame.tail.arcLengthInAbMeters, 141.0)), rail.id,
                ViewDirection.AB, 139.0, 141.0, train.cars.map { it.id })
            for (head in listOf(139.0, 139.5, 140.0, 140.5, 141.0)) {
                world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(head)); train.commit() }
                assertInside(envelope, train)
            }
        }
    }

    @Test fun `non-unit arc parametrization includes actual endpoint offsets rather than assuming metres of rigid length`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = world.createRailForTest(PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(0.0,0.0,0.0), Vector3d(0.0,0.0,1.0), Vector3d(0.0,1.0,0.0), 0.0, Curve.SegmentType.LINE_START),
                Curve.RawPoint(Vector3d(0.0,0.0,1000.0), Vector3d(0.0,0.0,1.0), Vector3d(0.0,1.0,0.0), 100.0, Curve.SegmentType.LINE_END))))
            val train = world.onOwnerForTest {
                val anchor = rail.viewAB.getRailLocationAt(50.0)
                val car = CoreCar.create("scaled-car", CoreBogie.create(anchor), CoreBogie.create(anchor), length = 10.0,
                    bogieAOffset = 3.0, bogieBOffset = -3.0, connectorLengthAtA = 0.25,
                    connectorLengthAtB = 0.25, world = world)
                CoreTrain("scaled", listOf(car), world, emptyMap()).also { it.setLocation(anchor); it.commit() }
            }
            val geometry = StoppedBodyGeometry.capture(train)
            val envelope = geometry.enclose(world.topologyState,
                listOf(path(rail, train.publishedFrame.tail.arcLengthInAbMeters, 81.0)), rail.id,
                ViewDirection.AB, 79.0, 81.0, train.cars.map { it.id })
            assertTrue(envelope.maximumEndpointReachMeters > 40.0)
            for (head in listOf(79.0,80.0,81.0)) {
                world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(head)); train.commit() }
                assertInside(envelope, train)
            }
        }
    }

    @Test fun `all spatially matching occurrences retain their entire ordered hull through a loop`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(220.0, 1))
            val train = createTrainWithConfig("loop", rail, world, listOf(10.0), connectorLength = 0.25)
            world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(80.0)); train.commit() }
            val geometry = StoppedBodyGeometry.capture(train)
            val retained = listOf(path(rail, 0.0,100.0), path(rail, 100.0,0.0, ViewDirection.BA), path(rail, 0.0,91.0))
            val envelope = geometry.enclose(world.topologyState, retained, rail.id, ViewDirection.AB,89.0,91.0,train.cars.map { it.id })
            assertEquals(3, envelope.bodyHull.size)
            assertEquals(retained[1], envelope.bodyHull[1], "intervening excursion outside spatial box must remain")
            assertTrue(envelope.bodyHull.first().startArcLengthInAbMeters > 0.0)
            assertEquals(91.0, envelope.bodyHull.last().endArcLengthInAbMeters)
        }
    }

    @Test fun `stale publication replaced configuration and topology cannot reuse a spatial witness`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(220.0,1))
            val train = createTrainWithConfig("source",rail,world,listOf(10.0),connectorLength=0.25)
            world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(80.0)); train.commit() }
            val geometry = StoppedBodyGeometry.capture(train)
            val old = train.publishedFrame
            val topology = world.topologyState
            val envelope = geometry.enclose(topology,listOf(path(rail,0.0,181.0)),rail.id,ViewDirection.AB,179.0,181.0,train.cars.map { it.id })
            world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(180.0));train.commit() }
            assertThrows(IllegalArgumentException::class.java) { geometry.requireConfiguration(old,train.cars.map { it.id }) }
            assertInside(envelope,train)
            val car = train.cars.single()
            val originalOffset = car.bogieAOffset
            (car.bogiesOffsets as MutableList<Double>)[0] = 2.5
            assertThrows(IllegalArgumentException::class.java) { envelope.requireCurrent(train.publishedFrame,topology,train.cars.map { it.id }) }
            car.bogiesOffsets[0] = originalOffset
            createStraightRailFromSegments(world,PrimeCurveGenerator.buildStraight(10.0,1))
            assertThrows(IllegalArgumentException::class.java) { envelope.requireCurrent(train.publishedFrame,topology,train.cars.map { it.id }) }
        }
    }

    @Test fun `large coordinate uncertainty rejects instead of pretending axle precision`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = world.createRailForTest(PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(1e16,0.0,0.0),Vector3d(0.0,0.0,1.0),Vector3d(0.0,1.0,0.0),0.0,Curve.SegmentType.LINE_START),
                Curve.RawPoint(Vector3d(1e16,0.0,220.0),Vector3d(0.0,0.0,1.0),Vector3d(0.0,1.0,0.0),220.0,Curve.SegmentType.LINE_END))))
            val train = createTrainWithConfig("large",rail,world,listOf(10.0))
            world.onOwnerForTest { train.setLocation(rail.viewAB.getRailLocationAt(80.0));train.commit() }
            val geometry = StoppedBodyGeometry.capture(train)
            val failure = assertThrows(IllegalArgumentException::class.java) {
                geometry.enclose(world.topologyState,listOf(path(rail,60.0,181.0)),rail.id,ViewDirection.AB,179.0,181.0,train.cars.map { it.id })
            }
            assertTrue(failure.message.orEmpty().contains("numerically unresolved"))
        }
    }

    @Test fun `nonzero connected endpoint gap is included in bounded endpoint displacement`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            fun line(start: Double, end: Double) = PrimeCurve.fromPoints(listOf(
                Curve.RawPoint(Vector3d(0.0,0.0,start),Vector3d(0.0,0.0,1.0),Vector3d(0.0,1.0,0.0),0.0,Curve.SegmentType.LINE_START),
                Curve.RawPoint(Vector3d(0.0,0.0,end),Vector3d(0.0,0.0,1.0),Vector3d(0.0,1.0,0.0),end-start,Curve.SegmentType.LINE_END)))
            val first = world.createRailForTest(line(0.0,100.0))
            val second = world.createRailForTest(line(100.004,220.004))
            world.onOwnerForTest { CoreRail.connect(first,second) }
            val train = createTrainWithConfig("gap",first,world,listOf(10.0,10.0),connectorLength=0.25)
            world.onOwnerForTest { train.setLocation(first.viewAB.getRailLocationAt(60.0));train.commit() }
            val geometry = StoppedBodyGeometry.capture(train)
            val envelope = geometry.enclose(world.topologyState,listOf(
                path(first,train.publishedFrame.tail.arcLengthInAbMeters,100.0),path(second,0.0,51.0)),
                second.id,ViewDirection.AB,49.0,51.0,train.cars.map { it.id })
            for (head in listOf(49.0,50.0,51.0)) {
                world.onOwnerForTest { train.setLocation(second.viewAB.getRailLocationAt(head));train.commit() }
                assertInside(envelope,train)
            }
            assertTrue(envelope.maximumEndpointReachMeters > 21.0)
        }
    }

    @Test fun `constructor rejects cancelling tangent or normal rather than issuing an affine-frame assumption`() {
        fun raw(z: Double, tangent: Vector3d, normal: Vector3d) = Curve.RawPoint(Vector3d(0.0,0.0,z),tangent,normal,z)
        assertThrows(IllegalArgumentException::class.java) { PrimeCurve.fromPoints(listOf(
            raw(0.0,Vector3d(0.0,0.0,1.0),Vector3d(0.0,1.0,0.0)),
            raw(100.0,Vector3d(0.0,0.0,1.0),Vector3d(0.0,-1.0,0.0)))) }
        assertThrows(IllegalArgumentException::class.java) { PrimeCurve.fromPoints(listOf(
            raw(0.0,Vector3d(0.0,0.0,1.0),Vector3d(0.0,1.0,0.0)),
            raw(100.0,Vector3d(0.0,0.0,-1.0),Vector3d(0.0,1.0,0.0)))) }
    }
}
