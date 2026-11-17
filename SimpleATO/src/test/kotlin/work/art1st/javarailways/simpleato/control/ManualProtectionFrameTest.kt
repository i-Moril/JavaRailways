package work.art1st.javarailways.simpleato.control

import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.VehicleControlCapabilities
import work.art1st.javarailways.basic.rail.OperationalSpeedLimitSignal
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.core.api.physics.rail.RailObjectLifecycleState
import work.art1st.javarailways.core.api.physics.rail.RailObjectRef
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.control.signal.Signal
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.RailLocationWithoutFacing
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.TrainEnd
import java.util.concurrent.CompletableFuture
import work.art1st.javarailways.basic.control.VehicleControlCapabilityModel
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitEntry
import work.art1st.javarailways.basic.rail.SpeedLimitMetadata
import work.art1st.javarailways.basic.rail.SpeedLimitType
import work.art1st.javarailways.basic.rail.infrastructureMetadata
import work.art1st.javarailways.basic.testing.TestBasicWorldDataRegistry
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.train.TestWorldFixture
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.physics.train.onOwnerForTest

class ManualProtectionFrameTest {
    @Test
    fun `unfinished same-side junction remains a zero speed target before the physical endpoint`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val approach = world.createRailForTest(straight(0.0, 150.0))
                val from = Vector3d(150.0, 64.0, 0.0)
                val to = Vector3d(100.0, 64.0, 10.0)
                val tangent = Vector3d(to).sub(from).normalize()
                val branch = world.createRailForTest(PrimeCurve.fromPoints(listOf(
                    Curve.RawPoint(from, tangent, Vector3d(0.0, 1.0, 0.0), 0.0),
                    Curve.RawPoint(to, tangent, Vector3d(0.0, 1.0, 0.0), from.distance(to))
                )))
                world.onOwnerForTest { CoreRail.connect(approach, branch) }
                val train = createTrainWithConfig("manual-incomplete-junction", approach, world, listOf(10.0, 10.0))
                val equipment = ReferenceEquipmentFixture()
                val capabilities = VehicleControlCapabilityModel(equipment.profile, equipment.cars, 9.80665)
                    .snapshot(0.0, equipment.carInputs)

                val frame = manualProtectionFrame(train, capabilities)

                assertTrue(approach.viewAB.nextRails.isEmpty())
                val stop = frame.protectionTargets.single { it.id == "manual/observed-boundary" }
                assertEquals(150.0 - train.publishedFrame.head.arcLengthInAbMeters, stop.distanceMeters, 1e-9)
                assertEquals(0.0, stop.targetSpeedMetersPerSecond)
                assertTrue(frame.referenceTargets.any { it.id == stop.id && it.targetSpeedMetersPerSecond == 0.0 })
                assertFalse(train.isDerailed)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `current native speed restriction survives short rail between bogies under actual body`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val before = world.createRailForTest(straight(0.0, 24.0))
                val short = world.createRailForTest(straight(24.0, 25.0))
                val after = world.createRailForTest(straight(25.0, 150.0))
                world.onOwnerForTest {
                    CoreRail.connect(before, short)
                    CoreRail.connect(short, after)
                }
                short.infrastructureMetadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(
                    listOf(SpeedLimitEntry(1.5, SpeedLimitType.SUBGRADE))))
                // The real two-car placement spans x=10..30. Its bogies are at x=10,20,30,
                // while this one-metre rail lies strictly inside the leading car body.
                val train = createTrainWithConfig("manual-body-speed", before, world, listOf(10.0, 10.0))
                assertFalse(train.isDerailed)
                val published = train.publishedFrame
                assertTrue(published.bodyTraversal.any { it.railId == short.id })
                assertTrue(train.cars.flatMap { it.bogies }.none { it.location.rail === short })
                assertSame(after, train.headLocation.rail, "the short rail is behind the forward scan")
                val equipment = ReferenceEquipmentFixture()
                val capabilities = VehicleControlCapabilityModel(equipment.profile, equipment.cars, 9.80665)
                    .snapshot(0.0, equipment.carInputs)

                val frame = manualProtectionFrame(train, capabilities)

                val target = frame.protectionTargets.single { it.id == "infrastructure/body/${short.id}" }
                assertEquals(0.0, target.distanceMeters)
                assertEquals(1.5, target.targetSpeedMetersPerSecond)
                assertTrue(frame.referenceTargets.any {
                    it.id == target.id && it.distanceMeters == 0.0 && it.targetSpeedMetersPerSecond == 1.5
                })
                assertEquals(0.0, frame.mostAdverseGrade)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `missing published body evidence is rejected rather than assuming bogie coverage`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = world.createRailForTest(straight(0.0, 150.0))
                val train = createTrainWithConfig("manual-missing-body", rail, world, listOf(10.0, 10.0))
                val missing = object : Train by train {
                    override val publishedFrame: TrainFrameSnapshot? get() = null
                }
                val equipment = ReferenceEquipmentFixture()
                val capabilities = VehicleControlCapabilityModel(equipment.profile, equipment.cars, 9.80665)
                    .snapshot(0.0, equipment.carInputs)
                assertThrows(IllegalArgumentException::class.java) { manualProtectionFrame(missing, capabilities) }
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `manual loop ends at caller owned 256 segment budget before its distance horizon`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val curves = PrimeCurveGenerator.buildCircle(140.0 / kotlin.math.PI, 2, step = 0.1)
                val first = world.createRailForTest(curves[0])
                val second = world.createRailForTest(curves[1])
                world.onOwnerForTest {
                    RailView.connect(first.viewAB, second.viewBA)
                    RailView.connect(second.viewAB, first.viewBA)
                }
                val train = createTrainWithConfig("manual-capped-loop", first, world, listOf(10.0))
                val caps = capabilities().copy(maximumSpeedMetersPerSecond = 5000.0)

                val frame = manualProtectionFrame(train, caps)

                val stop = frame.protectionTargets.single { it.id == "manual/observed-boundary" }
                val expected = 128.0 * (first.curve.totalArcLength + second.curve.totalArcLength) -
                    train.publishedFrame.head.arcLengthInAbMeters
                assertEquals(expected, stop.distanceMeters, 1e-7)
                assertTrue(stop.distanceMeters < caps.maximumSpeedMetersPerSecond * 30.0)
                assertEquals(0.0, stop.targetSpeedMetersPerSecond)
                assertFalse(train.isDerailed)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `manual reads each segment metadata then static sources then live dynamic ends before next segment`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val first = world.createRailForTest(straight(0.0, 100.0))
                val second = world.createRailForTest(straight(100.0, 200.0))
                world.onOwnerForTest { CoreRail.connect(first, second) }
                val observer = createTrainWithConfig("manual-observer", first, world, listOf(10.0))
                val other = createTrainWithConfig("manual-other", second, world, listOf(10.0), carIdPrefix = "other-")
                val calls = mutableListOf<String>()
                lateinit var transientEnd: TrainEnd
                first.signals.add(QuerySignal("first", RailLocationWithoutFacing(first, 40.0)) {
                    calls += "first-static"
                    transientEnd = TrainEnd(RailLocationWithFacing(first.viewAB, 40.0), other, TrainEnd.Type.HEAD)
                    // This later rail's metadata must not have been read before this callback.
                    second.infrastructureMetadata = RailInfrastructureMetadata(speedLimits = SpeedLimitMetadata(
                        listOf(SpeedLimitEntry(2.0, SpeedLimitType.SUBGRADE))))
                })
                second.signals.add(QuerySignal("second", RailLocationWithoutFacing(second, 30.0)) {
                    calls += "second-static"
                    // The earlier rail's dynamic collection must already have been consumed.
                    world.onOwnerForTest { transientEnd.destroy().toCompletableFuture().join() }
                })

                val frame = manualProtectionFrame(observer, capabilities())

                assertEquals(listOf("first-static", "second-static"), calls)
                assertEquals(RailObjectLifecycleState.REMOVED, transientEnd.lifecycleState)
                val earlierEnd = frame.protectionTargets.single { it.id == "following/${transientEnd.ref.id}/0" }
                assertEquals(20.0, earlierEnd.distanceMeters)
                assertEquals(0.0, earlierEnd.targetSpeedMetersPerSecond)
                val laterLimit = frame.protectionTargets.single { it.id == "infrastructure/${second.id}/1" }
                assertEquals(80.0, laterLimit.distanceMeters)
                assertEquals(2.0, laterLimit.targetSpeedMetersPerSecond)
                assertTrue(frame.protectionTargets.indexOf(earlierEnd) < frame.protectionTargets.indexOf(laterLimit))
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `manual own exclusion uses exact instance and never imports Basic store signals`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = world.createRailForTest(straight(0.0, 150.0))
                val train = createTrainWithConfig("manual-identity", rail, world, listOf(10.0))
                val storeOnly = OperationalSpeedLimitSignal(RailObjectRef("test", "store-only"),
                    RailLocationWithoutFacing(rail, 30.0), ViewDirection.AB,
                    SpeedLimitMetadata(listOf(SpeedLimitEntry(1.0, SpeedLimitType.SUBGRADE))))
                world.basicWorldData.railObjects.register(storeOnly)
                val sameIdDifferentInstance = object : Train by train {}
                val caps = capabilities().copy(maximumSpeedMetersPerSecond = 1.0)

                val own = manualProtectionFrame(train, caps)
                val distinct = manualProtectionFrame(sameIdDifferentInstance, caps)

                assertFalse(own.protectionTargets.any { it.id.startsWith("following/") })
                val observedHead = distinct.protectionTargets.single { it.id.startsWith("following/") }
                assertEquals("following/${train.head.ref.id}/0", observedHead.id)
                assertEquals(0.0, observedHead.distanceMeters)
                assertEquals(0.0, observedHead.targetSpeedMetersPerSecond)
                assertFalse(distinct.protectionTargets.any { it.id.contains("store-only") })
                assertSame(storeOnly, world.basicWorldData.railObjects.get(storeOnly.ref))
                assertEquals(100.0, distinct.protectionTargets.single { it.id == "manual/observed-boundary" }.distanceMeters)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `manual endpoint obstacles include both segment bounds without automatic stop tolerance`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = world.createRailForTest(straight(0.0, 150.0))
                val remote = world.createRailForTest(straight(200.0, 350.0))
                val observer = createTrainWithConfig("manual-bounds", rail, world, listOf(10.0))
                val other = createTrainWithConfig("manual-remote-owner", remote, world, listOf(10.0), carIdPrefix = "remote-")
                val ends = listOf(19.999, 20.0, 120.0, 120.001).map { arc ->
                    TrainEnd(RailLocationWithFacing(rail.viewAB, arc), other, TrainEnd.Type.HEAD)
                }

                val frame = manualProtectionFrame(observer, capabilities().copy(maximumSpeedMetersPerSecond = 1.0))

                val obstacles = frame.protectionTargets.filter { it.id.startsWith("following/") }
                assertEquals(listOf(0.0, 100.0), obstacles.map { it.distanceMeters })
                assertTrue(obstacles.all { it.targetSpeedMetersPerSecond == 0.0 })
                assertTrue(ends.all { it.lifecycleState == RailObjectLifecycleState.ACTIVE })
                assertEquals(100.0, frame.protectionTargets.last().distanceMeters)
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    @Test
    fun `manual transition limits retain current release direction and inclusive upcoming semantics`() {
        TestWorldFixture.create().use { fixture ->
            val world = fixture.world
            TestBasicWorldDataRegistry.getOrCreate(world)
            try {
                val rail = world.createRailForTest(straight(0.0, 150.0))
                val train = createTrainWithConfig("manual-speed-transitions", rail, world, listOf(10.0))
                val head = train.head.location.arcLength
                fun signal(id: String, arc: Double, limit: Double?, direction: ViewDirection = ViewDirection.AB) =
                    OperationalSpeedLimitSignal(RailObjectRef("test", id), RailLocationWithoutFacing(rail, arc),
                        direction, limit?.let { SpeedLimitMetadata(listOf(SpeedLimitEntry(it, SpeedLimitType.SUBGRADE))) })
                val current = signal("current", head - 2.0, 3.0)
                val upcoming = signal("upcoming", head + 4.0, 5.0)
                val wrongDirection = signal("opposite", head + 5.0, 0.1, ViewDirection.BA)
                val futureRelease = signal("future-release", head + 6.0, null)
                rail.signals.addAll(listOf(current, upcoming, wrongDirection, futureRelease))

                val limited = manualProtectionFrame(train, capabilities())
                assertEquals(3.0, limited.protectionTargets.single {
                    it.id == "infrastructure/current-transition" }.targetSpeedMetersPerSecond)
                val ahead = limited.protectionTargets.single { it.id == "infrastructure/${upcoming.ref.id}/0" }
                assertEquals(4.0, ahead.distanceMeters)
                assertEquals(5.0, ahead.targetSpeedMetersPerSecond)
                assertFalse(limited.protectionTargets.any { it.id.contains(wrongDirection.ref.id) })
                assertFalse(limited.protectionTargets.any { it.id.contains(futureRelease.ref.id) })

                val currentRelease = signal("current-release", head - 1.0, null)
                rail.signals += currentRelease
                val released = manualProtectionFrame(train, capabilities())
                assertFalse(released.protectionTargets.any { it.id == "infrastructure/current-transition" })
                assertTrue(released.protectionTargets.any { it.id == ahead.id })
                assertSame(currentRelease, rail.viewAB.staticRailObjects.single { it.ref == currentRelease.ref })
            } finally {
                TestBasicWorldDataRegistry.unregister(world)
            }
        }
    }

    private class QuerySignal(id: String, location: RailLocationWithoutFacing, val queried: () -> Unit) :
        Signal(RailObjectRef("test", id), location) {
        override val lifecycleState = RailObjectLifecycleState.ACTIVE
        override val velocity = Vector3d()
        override fun appliesTo(direction: ViewDirection): Boolean {
            queried()
            return false
        }
        override fun destroy() = CompletableFuture.completedFuture<Void>(null)
    }

    private fun capabilities(): VehicleControlCapabilities {
        val equipment = ReferenceEquipmentFixture()
        return VehicleControlCapabilityModel(equipment.profile, equipment.cars, 9.80665)
            .snapshot(0.0, equipment.carInputs)
    }

    private fun straight(start: Double, end: Double): PrimeCurve = PrimeCurve.fromPoints(listOf(
        Curve.RawPoint(Vector3d(start, 64.0, 0.0), Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
        Curve.RawPoint(Vector3d(end, 64.0, 0.0), Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0), end - start, Curve.SegmentType.LINE_END)
    ))
}
