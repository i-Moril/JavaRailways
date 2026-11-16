package work.art1st.javarailways.basic.api.ato

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import work.art1st.javarailways.basic.api.rail.RailObjectStore
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.rail.RailObject
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainEndRailObject
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.configureTurnoutForTest
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest
import work.art1st.javarailways.core.physics.train.setControllerForTest

class SelectedTrackAheadTest {
    @ParameterizedTest
    @EnumSource(ViewDirection::class)
    fun `oriented trace keeps view local intervals and asymmetric inclusive object bounds`(facing: ViewDirection) {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = world.createRailForTest(straight(0.0, 100.0))
            val train = createTrainWithConfig("observer", rail, world, listOf(5.0))
            val view = rail.getView(facing)
            val head = RailPositionSnapshot(rail.id, facing, if (facing == ViewDirection.AB) 40.0 else 60.0)
            val path = traceSelectedTrackAhead(world, head, 20.0)
            assertEquals(world.topologyState.revision, path.topologyRevision)
            assertSame(view, path.segments.single().railView)
            assertEquals(40.0, path.segments.single().startArcLength)
            assertEquals(60.0, path.segments.single().endArcLength)
            assertEquals(20.0, path.coveredLengthMeters)
            val store = RailObjectStore()
            val tolerated = TestObject("tolerated", RailLocationWithFacing(view, 39.5))
            val before = TestObject("before", RailLocationWithFacing(view, 39.499))
            val atStart = TestObject("start", RailLocationWithFacing(view, 40.0))
            val atEnd = TestObject("end", RailLocationWithFacing(view, 60.0))
            val after = TestObject("after", RailLocationWithFacing(view, 60.001))
            val removed = TestObject("removed", RailLocationWithFacing(view, 50.0))
            listOf(tolerated, before, atStart, atEnd, after, removed).forEach(store::register)
            removed.lifecycleState = RailObjectLifecycleState.REMOVED

            val observed = observeTrackObjects(train, path, store, 0.5, 3.0)

            assertEquals(listOf(tolerated.ref, atStart.ref, atEnd.ref), observed.keys.toList())
            assertEquals(listOf(0.0, 0.0, 20.0), observed.values.map { it.distanceMeters })
            val captured = observed.getValue(tolerated.ref)
            assertSame(tolerated, captured.railObject)
            assertEquals(facing, captured.snapshot.position.facing)
            assertEquals(if (facing == ViewDirection.AB) 39.5 else 60.5, captured.snapshot.position.arcLengthInAbMeters)
            assertNull(captured.relativeSpeedMetersPerSecond)
            assertThrows(UnsupportedOperationException::class.java) {
                (observed as MutableMap).clear()
            }
            tolerated.lifecycleState = RailObjectLifecycleState.REMOVED
            store.unregister(tolerated.ref)
            assertEquals(RailObjectLifecycleState.ACTIVE, captured.snapshot.lifecycleState)
            assertSame(tolerated, observed.getValue(tolerated.ref).railObject)
            assertFalse(observeTrackObjects(train, path, store, 0.5, 3.0).containsKey(tolerated.ref))
        }
    }

    @Test
    fun `trace does not read objects and later observation retains traced branch while continuation stays live`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val approach = world.createRailForTest(straight(0.0, 40.0))
            val straight = world.createRailForTest(straight(40.0, 100.0))
            val branch = world.createRailForTest(straight(40.0, 100.0, 20.0))
            configureTurnoutForTest(approach, listOf(straight, branch))
            val train = createTrainWithConfig("observer", approach, world, listOf(5.0))
            val store = RailObjectStore()
            var reads = 0
            val target = object : RailObject {
                override val ref = RailObjectRef("test", "late-read")
                override val lifecycleState = RailObjectLifecycleState.ACTIVE
                override val location: RailLocationWithFacing get() {
                    reads++
                    return RailLocationWithFacing(straight.viewAB, 5.0)
                }
            }
            store.register(target)
            reads = 0

            val path = traceSelectedTrackAhead(world, RailPositionSnapshot(approach.id, ViewDirection.AB, 30.0), 30.0)
            assertEquals(0, reads)
            assertEquals(listOf(approach.id, straight.id), path.segments.map { it.railView.rail.id })
            world.onOwnerForTest { approach.viewAB.selectNextRail(branch.viewAB) }
            assertNotSame(path.segments[1].railView, path.segments[0].railView.nextActiveRail)

            val observed = observeTrackObjects(train, path, store, 0.0, 0.0)
            assertSame(target, observed.getValue(target.ref).railObject)
            assertEquals(15.0, observed.getValue(target.ref).distanceMeters)
            assertEquals(2, reads) // interval filtering, then immutable snapshot capture
            val fresh = traceSelectedTrackAhead(world, RailPositionSnapshot(approach.id, ViewDirection.AB, 30.0), 30.0)
            assertEquals(listOf(approach.id, branch.id), fresh.segments.map { it.railView.rail.id })
            assertFalse(observeTrackObjects(train, fresh, store, 0.0, 0.0).containsKey(target.ref))
        }
    }

    @Test
    fun `source order own ID exclusion relative speed and live endpoint capability are preserved`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val rail = world.createRailForTest(straight(0.0, 100.0))
            val observer = createTrainWithConfig("observer", rail, world, listOf(5.0))
            val other = createTrainWithConfig("other", rail, world, listOf(5.0), carIdPrefix = "other-")
            world.onOwnerForTest { other.setLocation(RailLocationWithFacing(rail.viewAB, 50.0)); other.commit() }
            val controller = object : TrainController(other) { init { speed = 7.0 } }
            other.setControllerForTest(controller)
            world.onOwnerForTest { controller.commit() }
            val signal = OperationalSpeedLimitSignal(RailObjectRef("test", "static"),
                RailLocationWithoutFacing(rail, 30.0), ViewDirection.AB, null)
            rail.signals.add(signal)
            val stored = TestObject("stored", RailLocationWithFacing(rail.viewAB, 25.0))
            val store = RailObjectStore().also { it.register(stored); it.register(signal) }
            val path = traceSelectedTrackAhead(world, RailPositionSnapshot(rail.id, ViewDirection.AB, 0.0), 80.0)

            val observed = observeTrackObjects(observer, path, store, 0.0, 3.0)
            assertEquals(listOf(signal.ref, other.head.ref, other.tail.ref, stored.ref), observed.keys.toList())
            assertEquals(4.0, observed.getValue(other.head.ref).relativeSpeedMetersPerSecond)
            assertSame(signal, observed.getValue(signal.ref).railObject) // same-instance duplicate from store
            assertFalse(observed.containsKey(observer.head.ref))
            val observerWithOtherId = object : Train by observer { override val id = other.id }
            val byId = observeTrackObjects(observerWithOtherId, path, store, 0.0, 3.0)
            assertFalse(byId.containsKey(other.head.ref))
            assertTrue(byId.containsKey(observer.head.ref))

            val endpoint = observed.getValue(other.head.ref).railObject as TrainEndRailObject
            val tail = other.tail as TrainEndRailObject
            assertSame(other, endpoint.trainOrNull)
            assertTrue(endpoint.isHead)
            assertFalse(tail.isHead)
            world.onOwnerForTest { other.head.reverse() }
            assertTrue(endpoint.isHead)
            assertSame(other, endpoint.trainOrNull)
            assertEquals(-3.0, observeTrackObjects(observer, path, store, 0.0, 3.0)
                .getValue(other.head.ref).relativeSpeedMetersPerSecond)
            world.onOwnerForTest { other.head.destroy().toCompletableFuture().join() }
            assertNull(endpoint.trainOrNull)
            assertEquals(RailObjectLifecycleState.ACTIVE, observed.getValue(other.head.ref).snapshot.lifecycleState)
            assertFalse(observeTrackObjects(observer, path, store, 0.0, 3.0).containsKey(other.head.ref))
        }
    }

    @Test
    fun `loop revisits keep nearest object and distinct live instances fail without a partial result`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            val curves = PrimeCurveGenerator.buildCircle(140.0 / kotlin.math.PI, 2, step = 0.1)
            val first = world.createRailForTest(curves[0])
            val second = world.createRailForTest(curves[1])
            world.onOwnerForTest {
                RailView.connect(first.viewAB, second.viewBA)
                RailView.connect(second.viewAB, first.viewBA)
            }
            val train = createTrainWithConfig("observer", first, world, listOf(5.0))
            val signal = OperationalSpeedLimitSignal(RailObjectRef("test", "loop-object"),
                RailLocationWithoutFacing(first, 25.0), ViewDirection.AB, null)
            first.signals.add(signal)
            val store = RailObjectStore().also { it.register(signal) }
            val lap = first.curve.totalArcLength + second.curve.totalArcLength
            val path = traceSelectedTrackAhead(world, RailPositionSnapshot(first.id, ViewDirection.AB, 20.0), lap + 30.0)
            assertEquals(listOf(first.id, second.id, first.id), path.segments.map { it.railView.rail.id })
            val observed = observeTrackObjects(train, path, store, 0.0, 0.0)
            assertEquals(listOf(signal.ref), observed.keys.toList())
            assertEquals(5.0, observed.getValue(signal.ref).distanceMeters)

            val duplicate = TestObject(signal.ref.id, RailLocationWithFacing(second.viewAB, 5.0))
            val conflictingStore = RailObjectStore().also { it.register(duplicate) }
            val failure = assertThrows(IllegalStateException::class.java) {
                observeTrackObjects(train, path, conflictingStore, 0.0, 0.0)
            }
            assertEquals("duplicate RailObjectRef ${signal.ref} refers to distinct live objects", failure.message)
            assertSame(duplicate, conflictingStore.get(duplicate.ref))
            assertSame(signal, observed.getValue(signal.ref).railObject)
        }
    }

    private class TestObject(id: String, override val location: RailLocationWithFacing) : RailObject {
        override val ref = RailObjectRef("test", id)
        override var lifecycleState = RailObjectLifecycleState.ACTIVE
    }

    private fun straight(start: Double, end: Double, endZ: Double = 0.0): PrimeCurve {
        val a = Vector3d(start, 0.0, 0.0)
        val b = Vector3d(end, 0.0, endZ)
        val tangent = Vector3d(b).sub(a).normalize()
        return PrimeCurve.fromPoints(listOf(
            Curve.RawPoint(a, tangent, Vector3d(0.0, 1.0, 0.0), 0.0),
            Curve.RawPoint(b, tangent, Vector3d(0.0, 1.0, 0.0), a.distance(b))
        ))
    }
}
