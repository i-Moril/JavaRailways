package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.PhysicsMaintenance
import work.art1st.javarailways.core.api.physics.rail.RailPositionSnapshot
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.PreparedTrainTeleport
import work.art1st.javarailways.core.api.physics.train.TrainTeleportInspection
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.render.RenderableFrameSnapshot
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.physics.tick.PhysicsStalledException
import work.art1st.javarailways.core.testing.IsolateGlobalHalt
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.util.concurrent.TimeUnit

class PhysicsMaintenanceTeleportTest {
    @ParameterizedTest
    @CsvSource("AB,false", "AB,true", "BA,false", "BA,true")
    fun `inspection leaves original geometry and installs complete consist in requested facing`(
        facing: ViewDirection,
        reversed: Boolean
    ) = withWorld { world ->
        val rail = straight(world)
        val train = createTrainWithConfig("multi", rail, world, listOf(10.0, 8.0), connectorLength = 0.4)
        if (reversed) world.onOwnerForTest { train.reverse() }
        val oldFrame = train.publishedFrame
        val oldWorld = world.latestWorldFrame()
        val oldCars = train.cars.map(CoreCar::capturePlacementState)
        world.drainPublishedTrainFrames()
        val target = RailPositionSnapshot(rail.id, facing, 120.0)
        owner {
            world.withPhysicsStalled { maintenance ->
                assertEquals(PhysicsCoordinatorStatus.STALLED, world.physicsCoordinator.status)
                val candidate = ready(maintenance, train, target)
                assertSame(oldFrame, train.publishedFrame)
                assertSame(oldWorld, world.latestWorldFrame())
                assertEquals(oldCars, train.cars.map(CoreCar::capturePlacementState))
                assertEquals(reversed, train.isReversed)
                assertTrue(world.drainPublishedTrainFrames().isEmpty())
                assertEquals(120.0, candidate.headLocation.arcLengthInAbMeters, 1e-8)
                assertEquals(if (facing == ViewDirection.AB) 101.2 else 138.8,
                    candidate.tailLocation.arcLengthInAbMeters, 0.005)
                val expectedIntervals = candidate.occupiedRailIntervals
                maintenance.teleport(candidate)
                assertSame(oldFrame, train.publishedFrame)
                assertSame(oldWorld, world.latestWorldFrame())
                assertTrue(world.drainPublishedTrainFrames().isEmpty())
                assertEquals(expectedIntervals, candidate.occupiedRailIntervals)
            }
        }
        val frame = train.publishedFrame
        assertFalse(frame.reversed)
        assertEquals(facing, frame.head.facing)
        assertEquals(120.0, frame.head.arcLengthInAbMeters, 1e-8)
        assertEquals(oldFrame.physicsEpoch, frame.physicsEpoch)
        assertEquals(oldFrame.revision + 1, frame.revision)
        assertTrue(frame.headTraversal.isEmpty() && frame.tailTraversal.isEmpty())
        assertTrue(frame.cars.all { car -> stopped(car.state) &&
            car.bogies.all { stopped(it) } })
        assertSame(frame, world.latestWorldFrame().trains.single())
        assertTrue(world.latestWorldFrame().occupancy.blockingTrainIds(frame.occupiedIntervals).contains(train.id))
        assertSame(frame, world.drainPublishedTrainFrames().single())
    }

    @Test
    fun `failed inspection changes neither live geometry nor publication`() = withWorld { world ->
        val rail = straight(world)
        val train = createTrainWithConfig("unplaceable", rail, world, listOf(10.0, 8.0))
        val frame = train.publishedFrame
        val oldWorld = world.latestWorldFrame()
        val cars = train.cars.map(CoreCar::capturePlacementState)
        world.drainPublishedTrainFrames()
        owner {
            world.withPhysicsStalled { maintenance ->
                assertTrue(maintenance.inspectTeleport(train, RailPositionSnapshot(rail.id, ViewDirection.AB, 2.0))
                    is TrainTeleportInspection.Rejected)
                assertSame(frame, train.publishedFrame)
                assertEquals(cars, train.cars.map(CoreCar::capturePlacementState))
            }
        }
        assertSame(frame, train.publishedFrame)
        assertSame(oldWorld, world.latestWorldFrame())
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
    }

    @Test
    fun `scope failure restores every owned train without publishing a partial world`() = withWorld { world ->
        val rail = straight(world)
        val first = createTrainWithConfig("first", rail, world, listOf(10.0))
        val second = createTrainWithConfig("second", rail, world, listOf(8.0))
        val oldFrames = listOf(first.publishedFrame, second.publishedFrame)
        val oldCars = listOf(first, second).map { it.cars.map(CoreCar::capturePlacementState) }
        val oldWorld = world.latestWorldFrame()
        world.drainPublishedTrainFrames()
        owner {
            assertThrows(IllegalStateException::class.java) {
                world.withPhysicsStalled { maintenance ->
                    val a = ready(maintenance, first, RailPositionSnapshot(rail.id, ViewDirection.AB, 100.0))
                    val b = ready(maintenance, second, RailPositionSnapshot(rail.id, ViewDirection.BA, 150.0))
                    maintenance.teleport(a)
                    maintenance.teleport(b)
                    error("caller rejected its batch")
                }
            }
        }
        listOf(first, second).forEachIndexed { index, train ->
            assertSame(oldFrames[index], train.publishedFrame)
            assertEquals(oldCars[index], train.cars.map(CoreCar::capturePlacementState))
        }
        assertSame(oldWorld, world.latestWorldFrame())
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
    }

    @Test
    fun `caught duplicate candidate failure cannot publish the earlier teleport`() = withWorld { world ->
        val rail = straight(world)
        val train = createTrainWithConfig("duplicate", rail, world, listOf(10.0))
        val oldFrame = train.publishedFrame
        val oldWorld = world.latestWorldFrame()
        owner {
            assertThrows(IllegalStateException::class.java) {
                world.withPhysicsStalled { maintenance ->
                    val candidate = ready(maintenance, train, RailPositionSnapshot(rail.id, ViewDirection.AB, 120.0))
                    maintenance.teleport(candidate)
                    assertThrows(IllegalStateException::class.java) { maintenance.teleport(candidate) }
                }
            }
        }
        assertSame(oldFrame, train.publishedFrame)
        assertSame(oldWorld, world.latestWorldFrame())
        assertEquals(oldFrame.head, RailPositionSnapshot.capture(train.head.location, train.head.location.facing))
    }

    @Test
    fun `expired capability and foreign scope candidate cannot mutate a train`() = withWorld { world ->
        val rail = straight(world)
        val train = createTrainWithConfig("expired", rail, world, listOf(10.0))
        lateinit var scope: PhysicsMaintenance
        lateinit var candidate: PreparedTrainTeleport
        val oldFrame = train.publishedFrame
        owner {
            world.withPhysicsStalled { maintenance ->
                scope = maintenance
                candidate = ready(maintenance, train, RailPositionSnapshot(rail.id, ViewDirection.AB, 120.0))
            }
            assertThrows(IllegalStateException::class.java) { scope.world }
            assertThrows(IllegalStateException::class.java) { candidate.headLocation }
            assertThrows(IllegalArgumentException::class.java) {
                world.withPhysicsStalled { maintenance -> maintenance.teleport(candidate) }
            }
        }
        assertSame(oldFrame, train.publishedFrame)
    }

    @Test
    fun `nested maintenance publishes both single train changes only on outer exit`() = withWorld { world ->
        val rail = straight(world)
        val first = createTrainWithConfig("nested-first", rail, world, listOf(10.0))
        val second = createTrainWithConfig("nested-second", rail, world, listOf(8.0))
        val oldWorld = world.latestWorldFrame()
        val oldFirst = first.publishedFrame
        val oldSecond = second.publishedFrame
        world.drainPublishedTrainFrames()
        owner {
            world.withPhysicsStalled { outer ->
                val secondTarget = ready(outer, second, RailPositionSnapshot(rail.id, ViewDirection.AB, 150.0))
                world.withPhysicsStalled { inner ->
                    inner.teleport(ready(inner, first, RailPositionSnapshot(rail.id, ViewDirection.AB, 100.0)))
                }
                assertSame(oldFirst, first.publishedFrame)
                assertSame(oldSecond, second.publishedFrame)
                assertSame(oldWorld, world.latestWorldFrame())
                assertTrue(world.drainPublishedTrainFrames().isEmpty())
                outer.teleport(secondTarget)
            }
        }
        assertEquals(100.0, first.publishedFrame.head.arcLengthInAbMeters, 1e-8)
        assertEquals(150.0, second.publishedFrame.head.arcLengthInAbMeters, 1e-8)
        assertEquals(2, world.drainPublishedTrainFrames().size)
        assertTrue(world.latestWorldFrame().trains.containsAll(listOf(first.publishedFrame, second.publishedFrame)))
    }

    @Test
    fun `teleport clears physical motion while next epoch retains existing controller input`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            TestWorldFixture.create(coordinator).use { fixture ->
                try {
                    val world = fixture.world
                    val rail = straight(world)
                    val train = createTrainWithConfig("moving", rail, world, listOf(10.0, 8.0))
                    train.setControllerForTest(object : TrainController(train) {
                        override fun tick(deltaT: Double) {
                            speed = 4.0
                            super.tick(deltaT)
                        }
                    })
                    coordinator.start()
                    coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                    owner {
                        world.withPhysicsStalled { maintenance ->
                            maintenance.teleport(ready(maintenance, train,
                                RailPositionSnapshot(rail.id, ViewDirection.AB, 120.0)))
                        }
                    }
                    val placed = train.publishedFrame
                    assertEquals(120.0, placed.head.arcLengthInAbMeters, 1e-8)
                    assertTrue(placed.cars.all { stopped(it.state) })
                    val next = coordinator.stepSimulation(50.0)
                    next.completion.get(5, TimeUnit.SECONDS)
                    assertEquals(next.epoch, train.publishedFrame.physicsEpoch)
                    assertEquals(120.2, train.publishedFrame.head.arcLengthInAbMeters, 0.005)
                    assertTrue(train.publishedFrame.cars.all { it.state.velocityX > 3.9 })
                } finally {
                    coordinator.close()
                }
            }
        }
    }

    @Test
    @IsolateGlobalHalt
    fun `failed restoration after an external publication preserves original failure and halts physics`() {
        PhysicsCoordinator(1, automaticFallback = false).use { coordinator ->
            TestWorldFixture.create(coordinator).use { fixture ->
                try {
                    val world = fixture.world
                    val rail = straight(world)
                    val train = createTrainWithConfig("published-before-failure", rail, world, listOf(10.0))
                    val originalFailure = IllegalStateException("maintenance caller failed after publication")
                    lateinit var installed: TrainFrameSnapshot
                    coordinator.start()
                    val thrown = assertThrows(IllegalStateException::class.java) {
                        owner {
                            world.withPhysicsStalled { maintenance ->
                                maintenance.teleport(ready(maintenance, train,
                                    RailPositionSnapshot(rail.id, ViewDirection.AB, 120.0)))
                                train.commitMaintenancePlacement()
                                installed = train.publishedFrame
                                throw originalFailure
                            }
                        }
                    }
                    assertSame(originalFailure, thrown)
                    assertTrue(thrown.suppressed.any { it.message?.contains("after publication") == true })
                    assertSame(installed, train.publishedFrame)
                    assertSame(installed, world.latestWorldFrame().trains.single())
                    assertEquals(120.0, installed.head.arcLengthInAbMeters, 1e-8)
                    assertEquals(PhysicsCoordinatorStatus.STALLED, coordinator.status)
                    val halted = assertThrows(PhysicsStalledException::class.java) { coordinator.stepSimulation(50.0) }
                    assertSame(originalFailure, halted.cause)
                    assertSame(installed, train.publishedFrame)
                } finally {
                    coordinator.close()
                }
            }
        }
    }

    @Test
    fun `stale candidate rejection does not roll back an external staged placement`() = withWorld { world ->
        val rail = straight(world)
        val train = createTrainWithConfig("stale-source", rail, world, listOf(10.0))
        val oldFrame = train.publishedFrame
        world.drainPublishedTrainFrames()
        owner {
            assertThrows(IllegalStateException::class.java) {
                world.withPhysicsStalled { maintenance ->
                    val candidate = ready(maintenance, train, RailPositionSnapshot(rail.id, ViewDirection.AB, 120.0))
                    train.setLocation(rail.viewAB.getRailLocationAt(90.0))
                    assertThrows(IllegalStateException::class.java) { maintenance.teleport(candidate) }
                    assertEquals(90.0, train.head.activeLocation.arcLength, 1e-8)
                }
            }
        }
        assertEquals(90.0, train.head.activeLocation.arcLength, 1e-8)
        assertSame(oldFrame, train.publishedFrame)
        assertTrue(world.drainPublishedTrainFrames().isEmpty())
        world.onOwnerForTest { train.commitMaintenancePlacement() }
        assertEquals(90.0, train.publishedFrame.head.arcLengthInAbMeters, 1e-8)
    }

    @Test
    fun `inspection follows the selected backward branch without enumerating another viable branch`() = withWorld { world ->
        val curves = PrimeCurveGenerator.buildStraight(200.0, segmentCount = 2)
        val first = world.createRailForTest(curves[0])
        val other = world.createRailForTest(curves[0])
        val stem = world.createRailForTest(curves[1])
        owner {
            CoreRail.connect(first, stem)
            CoreRail.connect(other, stem)
            stem.viewBA.selectNextRail(first.viewBA)
        }
        val train = createTrainWithConfig("selected", stem, world, listOf(10.0, 8.0))
        val topology = world.topologyState
        val selected = stem.viewBA.activeRail
        owner {
            world.withPhysicsStalled { maintenance ->
                val candidate = ready(maintenance, train, RailPositionSnapshot(stem.id, ViewDirection.AB, 8.0))
                assertEquals(setOf(first.id, stem.id), candidate.occupiedRailIntervals.map { it.railId }.toSet())
                assertSame(topology, world.topologyState)
                assertSame(selected, stem.viewBA.activeRail)
                maintenance.teleport(candidate)
            }
        }
        assertEquals(first.id, train.publishedFrame.tail.railId)
        assertSame(selected, stem.viewBA.activeRail)
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `mixed installed cars keep physical model and bogie directions when normalizing reversed train`(
        reversed: Boolean
    ) = withWorld { world ->
        val rail = straight(world)
        val train = world.onOwnerForTest {
            val anchor = rail.viewAB.getRailLocationAt(60.0)
            val first = CoreCar.create("mixed-first", CoreBogie.create(anchor), CoreBogie.create(anchor),
                length = 10.0, floorHeight = 0.0, connectorLengthAtA = 0.2, connectorLengthAtB = 0.7, world = world)
            val second = CoreCar.create("mixed-second", CoreBogie.create(anchor), CoreBogie.create(anchor),
                length = 8.0, floorHeight = 0.0, connectorLengthAtA = 0.3, connectorLengthAtB = 0.9, world = world)
            second.reverse()
            CoreTrain.create("mixed", listOf(first, second), world).also {
                it.setLocation(rail.viewAB.getRailLocationAt(60.0))
                it.commitMaintenancePlacement()
            }
        }
        if (reversed) world.onOwnerForTest { train.reverse() }
        owner {
            world.withPhysicsStalled { maintenance ->
                val candidate = ready(maintenance, train, RailPositionSnapshot(rail.id, ViewDirection.BA, 80.0))
                assertEquals(99.6, candidate.tailLocation.arcLengthInAbMeters, 0.005)
                maintenance.teleport(candidate)
            }
        }
        assertFalse(train.isReversed)
        assertEquals(listOf(false, true), train.cars.map { it.isReversed })
        val first = train.publishedFrame.cars[0]
        val second = train.publishedFrame.cars[1]
        assertEquals(-1.0, first.state.directionX, 1e-8)
        assertEquals(1.0, second.state.directionX, 1e-8)
        assertTrue(first.bogies.all { it.directionX < -0.99 })
        assertTrue(second.bogies.all { it.directionX > 0.99 })
        assertEquals(6.0, first.bogies[0].positionVector().distance(first.bogies[1].positionVector()), 0.005)
        assertEquals(4.8, second.bogies[0].positionVector().distance(second.bogies[1].positionVector()), 0.005)
    }

    @Test
    fun `curved placement preserves actual axle chords and published target intervals`() = withWorld { world ->
        val rail = createLoopRailFromSegments(world, PrimeCurveGenerator.buildCircle(100.0, step = 0.1))
        val train = createTrainWithConfig("curve", rail, world, listOf(10.0, 8.0), isLoop = true, connectorLength = 0.2)
        owner {
            world.withPhysicsStalled { maintenance ->
                val candidate = ready(maintenance, train, RailPositionSnapshot(rail.id, ViewDirection.AB, 100.0))
                val intervals = candidate.occupiedRailIntervals
                maintenance.teleport(candidate)
                assertTrue(intervals.isNotEmpty())
            }
        }
        val frame = train.publishedFrame
        assertEquals(100.0, frame.head.arcLengthInAbMeters, 1e-8)
        listOf(10.0, 8.0).forEachIndexed { index, spacing ->
            val car = frame.cars[index]
            assertEquals(spacing, car.bogies[0].positionVector().distance(car.bogies[1].positionVector()), 0.005)
        }
        assertEquals(frame.occupiedIntervals, world.latestWorldFrame().trains.single().occupiedIntervals)
        assertFalse(train.isDerailed)
    }

    private fun stopped(frame: RenderableFrameSnapshot): Boolean =
        frame.velocityX == 0.0 && frame.velocityY == 0.0 && frame.velocityZ == 0.0

    private fun ready(maintenance: PhysicsMaintenance, train: CoreTrain, target: RailPositionSnapshot): PreparedTrainTeleport =
        when (val inspection = maintenance.inspectTeleport(train, target)) {
            is TrainTeleportInspection.Ready -> inspection.placement
            is TrainTeleportInspection.Rejected -> error(inspection.reason)
        }

    private fun straight(world: CoreWorld): CoreRail =
        createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(200.0, segmentCount = 1))

    private fun <T> owner(action: () -> T): T = IsolatedSimulationThreadMarker.run(action)

    private fun withWorld(action: (CoreWorld) -> Unit) {
        TestWorldFixture.create().use { fixture -> action(fixture.world) }
    }
}
