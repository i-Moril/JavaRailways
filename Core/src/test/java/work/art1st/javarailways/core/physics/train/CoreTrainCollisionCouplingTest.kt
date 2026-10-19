package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import org.junit.jupiter.params.provider.EnumSource
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.physics.curve.Curve
import work.art1st.javarailways.core.api.physics.curve.PrimeCurve
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.TrainLinkEvent
import work.art1st.javarailways.core.api.event.TrainLinkSuccessEvent
import work.art1st.javarailways.core.api.event.processor.*
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.api.physics.train.TrainFrameSnapshot
import work.art1st.javarailways.core.api.physics.rail.RailInterval
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpoint
import work.art1st.javarailways.core.api.physics.rail.topology.RailEndpointRef
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.api.util.annotation.AsyncEventApi
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.CoreRail
import work.art1st.javarailways.core.physics.rail.RailView
import work.art1st.javarailways.core.physics.createRailForTest
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class CoreTrainCollisionCouplingTest {
    @Test
    @OptIn(AsyncEventApi::class)
    fun `unselected competing turnout branch does not create a collision link and next epoch remains healthy`() {
        val owner = Thread.currentThread()
        ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        }).use {
            val coordinator = PhysicsCoordinator(1, automaticFallback = false)
            val world = createTestWorld(coordinator)
            (world.tickManager as AutoCloseable).use {
                run {
                    coordinator.use {
                        fun rail(from: Vector3d, to: Vector3d): CoreRail =
                            world.createRailForTest(PrimeCurve.fromPoints(listOf(
                                Curve.RawPoint(from, Vector3d(to).sub(from).normalize(), Vector3d(0.0, 1.0, 0.0),
                                    0.0, Curve.SegmentType.LINE_START),
                                Curve.RawPoint(to, Vector3d(to).sub(from).normalize(), Vector3d(0.0, 1.0, 0.0),
                                    from.distance(to), Curve.SegmentType.LINE_END)
                            )))

                        val origin = Vector3d(0.0, 0.0, 0.0)
                        val stem = rail(Vector3d(-30.0, 0.0, 0.0), origin)
                        val selected = rail(origin, Vector3d(30.0, 0.0, 0.0))
                        val competing = rail(origin, Vector3d(30.0 * kotlin.math.cos(5 * Math.PI / 6), 0.0,
                            30.0 * kotlin.math.sin(5 * Math.PI / 6)))
                        RailView.connect(stem.viewAB, selected.viewBA)
                        RailView.connect(stem.viewAB, competing.viewBA)
                        stem.viewAB.selectNextRail(selected.viewAB)
                        selected.viewBA.selectNextRail(stem.viewBA)
                        competing.viewBA.selectNextRail(stem.viewBA)

                        fun train(id: String, rail: CoreRail, towardJunction: Boolean): CoreTrain {
                            val view = if (towardJunction) rail.viewBA else rail.viewAB
                            val anchor = RailLocationWithFacing(view, view.curve.totalArcLength)
                            val car = CoreCar.create("$id-car", CoreBogie.create(anchor), CoreBogie.create(anchor),
                                length = 10.0, bogieAOffset = 3.0, bogieBOffset = -3.0,
                                connectorLengthAtA = 0.3, connectorLengthAtB = 0.3, world = world)
                            return world.onOwnerForTest { CoreTrain(id, listOf(car), world, emptyMap()).also {
                                it.setLocation(anchor)
                                it.commitMaintenancePlacement()
                            } }
                        }
                        val moving = train("selected-route", selected, towardJunction = true)
                        val parked = train("unselected-route", competing, towardJunction = true)
                        val controllerTicks = AtomicInteger()
                        moving.setControllerForTest(object : TrainController(moving) {
                            override fun tick(deltaT: Double) {
                                controllerTicks.incrementAndGet()
                                speed = 0.0
                                moveControlledTrain(0.0, 0.0)
                            }
                        })
                        val events = AtomicInteger()
                        PhysicsRailEventBus.register(object : EventListener {
                            @HandlesEvent
                            fun collision(event: CollisionEvent) {
                                events.incrementAndGet()
                                event.requestLink()
                            }
                        }).use {
                            world.rebuildSweptOccupancyIndex(0L)
                            val frame = world.publishWorldFrame(0L)
                            assertEquals(RailEndpointRef(selected.id, RailEndpoint.A),
                                frame.activeContinuations.selectedContinuation(RailEndpointRef(stem.id, RailEndpoint.B)))
                            val movingEnvelope = world.collisionEnvelope(moving.id)!!
                            val parkedEnvelope = world.collisionEnvelope(parked.id)!!
                            val endpointsTouch = movingEnvelope.ends.values.any { own ->
                                parkedEnvelope.ends.values.any(own::touches)
                            }
                            val bridgeExists = movingEnvelope.ends.any { (ownSide, own) ->
                                parkedEnvelope.ends.any { (otherSide, _) ->
                                    movingEnvelope.bridge(ownSide, parkedEnvelope, otherSide) != null
                                }
                            }
                            val broad = world.querySweptCollision(moving)
                            val endDetails = listOf(movingEnvelope, parkedEnvelope).map { envelope ->
                                envelope.ends.mapValues { (_, end) ->
                                    "${end.position} outward=${end.outward} connector=${end.connector}"
                                }
                            }
                            assertTrue(endpointsTouch, "fixture must be a real three-dimensional coupler candidate: $endDetails")
                            assertFalse(bridgeExists, "competing branch has no bridge on the selected captured route")
                            assertTrue(broad.checkedCandidates > 0, "fixture must reach the shared rail-bucket broad phase")
                            assertNull(broad.collidingTrainId)
                            assertEquals(0, events.get())
                            assertSame(frame.activeContinuations, frame.occupancy.collisionActiveContinuations)

                            coordinator.start()
                            coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                            assertEquals(1, controllerTicks.get(), "first epoch must run the moving train controller")
                            assertEquals(0, events.get(), "false connector candidate must not freeze or emit collision")
                            assertEquals(setOf(moving, parked), world.allTrains.toSet())
                            assertFalse(moving.isDerailed)
                            assertFalse(parked.isDerailed)
                            val next = coordinator.stepSimulation(50.0)
                            next.completion.get(5, TimeUnit.SECONDS)
                            assertEquals(2L, next.epoch)
                            assertEquals(2, controllerTicks.get(), "next epoch must remain healthy and run controller")
                            assertEquals(0, events.get())
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("A,A,false", "A,B,false", "B,A,false", "B,B,false",
        "A,A,true", "A,B,true", "B,A,true", "B,B,true")
    fun `body collision corrects recorded overshoot without moving stationary anchor`(
        movingEnd: TrainEndSide, fixedEnd: TrainEndSide, movingMaster: Boolean
    ) = scenario(movingEnd, fixedEnd, movingMaster, failPreparation = false)

    @Test
    fun `failed controller preparation leaves collision positions intact before fallback derail`() =
        scenario(TrainEndSide.B, TrainEndSide.A, movingMaster = true, failPreparation = true)

    @Test
    fun `changed structural topology rejects captured collision evidence`() =
        scenario(TrainEndSide.A, TrainEndSide.B, true, false, staleTopology = true)

    @ParameterizedTest
    @ValueSource(strings = ["straight", "curve", "slope", "short-rails"])
    fun `low speed coupler contact links before bodies overlap without blocking the gap`(shape: String) =
        scenario(TrainEndSide.A, TrainEndSide.B, true, false, lowSpeed = true, shape = shape)

    @Test
    fun `both moving participants are rejected despite subsequent freeze`() =
        scenario(TrainEndSide.A, TrainEndSide.B, true, false, bothMoving = true)

    @Test
    fun `prepared collision index replaces removed side and finds the next obstacle`() =
        scenario(TrainEndSide.A, TrainEndSide.B, true, false, thirdObstacle = true)

    @Test
    fun `cleared reverse selection does not change the captured correction path`() =
        scenario(TrainEndSide.B, TrainEndSide.A, true, false, clearReverse = true)

    @Test
    fun `three dimensional slope correction installs complete candidate geometry`() =
        scenario(TrainEndSide.B, TrainEndSide.A, true, false, shape = "slope")

    @Test
    fun `curved correction preserves rigid axle and coupler lengths`() =
        scenario(TrainEndSide.A, TrainEndSide.B, true, false, shape = "curve")

    @Test
    fun `moving step ending at zero speed is not used as the stationary anchor`() =
        scenario(TrainEndSide.B, TrainEndSide.A, true, false, brakedStep = true)

    @Test
    fun `between frames rename preserves valid collision coupling evidence`() =
        scenario(TrainEndSide.A, TrainEndSide.B, true, false, renameBeforeCollision = true)

    @Test
    fun `link success observes shared publication and queued rename waits for next owner batch`() =
        scenario(TrainEndSide.B, TrainEndSide.A, true, false, renameOnSuccess = true)

    @Test
    fun `collision decision veto follows terminal owner fallback without a post tick task`() =
        scenario(TrainEndSide.A, TrainEndSide.B, true, false, veto = true)

    @ParameterizedTest
    @EnumSource(WorldExecutionMode::class)
    fun `collision coupling uses the same domain path in both world modes`(
        mode: WorldExecutionMode
    ) = scenario(TrainEndSide.A, TrainEndSide.B, true, false, executionMode = mode)

    @OptIn(AsyncEventApi::class)
    private fun scenario(movingEnd: TrainEndSide, fixedEnd: TrainEndSide,
                         movingMaster: Boolean, failPreparation: Boolean,
                         staleTopology: Boolean = false, lowSpeed: Boolean = false,
                         bothMoving: Boolean = false, thirdObstacle: Boolean = false,
                         clearReverse: Boolean = false, shape: String = "straight", brakedStep: Boolean = false,
                         renameBeforeCollision: Boolean = false, renameOnSuccess: Boolean = false,
                         veto: Boolean = false,
                         executionMode: WorldExecutionMode =
                             WorldExecutionMode.LIVE) {
        val owner = Thread.currentThread()
        ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === owner
        }).use {
            val coordinator = PhysicsCoordinator(2, automaticFallback = false)
            val world = try { createTestWorld(coordinator, executionMode) } catch (failure: Throwable) {
                coordinator.close()
                throw failure
            }
            (world.tickManager as AutoCloseable).use {
                run {
                    coordinator.use {
                        val slopeTangent = Vector3d(0.8, 0.48, 0.36)
                        val slopeNormal = Vector3d(0.0, 1.0, 0.0)
                            .sub(Vector3d(slopeTangent).mul(0.48)).normalize()
                        val curves = when (shape) {
                            "short-rails" -> List(82) { index ->
                                val start = if (index == 0) 0.0 else 98.0 + (index - 1) * 0.05
                                val end = when (index) { 0 -> 98.0; 81 -> 300.0; else -> start + 0.05 }
                                PrimeCurve.fromPoints(listOf(
                                    Curve.RawPoint(Vector3d(start, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                                        Vector3d(0.0, 1.0, 0.0), 0.0, Curve.SegmentType.LINE_START),
                                    Curve.RawPoint(Vector3d(end, 0.0, 0.0), Vector3d(1.0, 0.0, 0.0),
                                        Vector3d(0.0, 1.0, 0.0), end - start, Curve.SegmentType.LINE_END)
                                ))
                            }
                            "curve" -> PrimeCurveGenerator.buildCircle(30.0, 1, step = 0.02)
                            "slope" -> List(3) { segment -> PrimeCurve.fromPoints(listOf(
                                Curve.RawPoint(Vector3d(slopeTangent).mul(segment * 100.0),
                                    Vector3d(slopeTangent), Vector3d(slopeNormal), 0.0, Curve.SegmentType.LINE_START),
                                Curve.RawPoint(Vector3d(slopeTangent).mul((segment + 1) * 100.0),
                                    Vector3d(slopeTangent), Vector3d(slopeNormal), 100.0, Curve.SegmentType.LINE_END)
                            )) }
                            else -> PrimeCurveGenerator.buildStraight(300.0, 3)
                        }
                        val rail = createStraightRailFromSegments(world, curves)
                        fun train(id: String, count: Int): CoreTrain {
                            val anchor = RailLocationWithFacing(rail.viewAB, 40.0)
                            val cars = List(count) { index -> CoreCar.create("$id-$index",
                                CoreBogie.create(anchor), CoreBogie.create(anchor), length = 10.0,
                                bogieAOffset = 3.0, bogieBOffset = -3.0, floorHeight = 0.4,
                                connectorLengthAtA = 0.3, connectorLengthAtB = 0.3, world = world) }
                            return world.onOwnerForTest { CoreTrain(id, cars, world, emptyMap()) }
                        }
                        val moving = train("moving", 2)
                        val initialHead = if (lowSpeed) 99.3 else 99.0
                        val movingHead = RailLocationWithFacing(rail.viewAB, 0.0)
                            .forward(initialHead - if (movingEnd == TrainEndSide.B) 20.6 else 0.0)!!
                        moving.world.onOwnerForTest {
                            moving.setLocation(if (movingEnd == TrainEndSide.B) movingHead.reversed else movingHead)
                            moving.commitMaintenancePlacement()
                        }
                        world.onOwnerForTest {
                            if (movingEnd == TrainEndSide.B) moving.reverse()
                            moving.commitMaintenancePlacement()
                        }
                        val fixed = train("stationary", 1)
                        val fixedHead = RailLocationWithFacing(rail.viewAB, 0.0).forward(
                            if (fixedEnd == TrainEndSide.A) 100.5 else 110.5)!!
                        fixed.world.onOwnerForTest {
                            fixed.setLocation(if (fixedEnd == TrainEndSide.A) fixedHead.reversed else fixedHead)
                            fixed.commitMaintenancePlacement()
                        }
                        val third = if (thirdObstacle) train("third", 1).also {
                            it.world.onOwnerForTest {
                                it.setLocation(RailLocationWithFacing(rail.viewAB, 0.0).forward(120.25)!!)
                                it.commitMaintenancePlacement()
                            }
                        } else null
                        if (bothMoving) fixed.setControllerForTest(object : TrainController(fixed) {
                            override fun tick(deltaT: Double) {
                                speed = 1.0
                                moveControlledTrain(0.0, 1.0)
                            }
                        })
                        val originalMovingCars = moving.cars.toList()
                        val originalFixedCars = fixed.cars.toList()
                        val fixedBefore = fixed.publishedFrame
                        val calls = AtomicInteger()
                        val controller = object : TrainController(moving) {
                            val handle = "P2"
                            override fun tick(deltaT: Double) {
                                calls.incrementAndGet()
                                speed = if (brakedStep) 0.0 else if (lowSpeed) 0.2 else 40.0
                                moveControlledTrain(if (lowSpeed) 0.01 else 2.0, speed)
                            }
                            override fun prepareConsistChange(cars: List<Car>, counterpart: TrainController?): List<PreparedControllerStateUpdate> {
                                if (failPreparation) error("test controller rejected preparation")
                                return emptyList()
                            }
                        }
                        moving.setControllerForTest(controller)
                        var observedEnds: Pair<TrainEndSide, TrainEndSide>? = null
                        var success: TrainFrameSnapshot? = null
                        var successes = 0
                        var coherentAtSuccess = false
                        var deferredRename: CompletableFuture<Unit>? = null
                        val collisions = AtomicInteger()
                        PhysicsRailEventBus.register(object : EventListener {
                            @HandlesEvent
                            fun collision(event: CollisionEvent) {
                                collisions.incrementAndGet()
                                event.requestLink()
                            }
                        }).use {
                            MainRailEventBus.register(object : EventListener {
                                @HandlesEvent
                                fun decide(event: TrainLinkEvent) {
                                    observedEnds = event.firstEnd to event.secondEnd
                                    if (movingMaster) event.requestMasterTrain(moving)
                                    if (veto) event.reject("test fixed unit")
                                }
                                @HandlesEvent
                                fun succeeded(event: TrainLinkSuccessEvent) {
                                    successes++
                                    success = event.train.publishedFrame
                                    val snapshot = world.latestWorldFrame()
                                    coherentAtSuccess = snapshot.trains.any { it === success } &&
                                        snapshot.trains.none { it.trainId == event.removedTrain.id } &&
                                        snapshot.occupancy.blockingTrainIds(success!!.occupiedIntervals).contains(event.train.id)
                                    if (renameOnSuccess) deferredRename = coordinator.submitBetweenFrames {
                                        world.renameTrain(event.train as CoreTrain, "After link")
                                    }
                                }
                            }).use {
                                coordinator.start()
                                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                                var collisionStep = moving.publishedFrame
                                val movedDistance = initialHead + if (lowSpeed) 0.01 else 2.0
                                val expectedHead = RailLocationWithFacing(rail.viewAB, 0.0).forward(movedDistance)!!
                                assertEquals(0.0, moving.headLocation.position.distance(expectedHead.position), 0.005)
                                assertTrue(collisionStep.headTraversal.isNotEmpty())
                                if (renameBeforeCollision) {
                                    val previousWorldFrame = world.latestWorldFrame()
                                    val renamed = coordinator.submitBetweenFrames {
                                        world.renameTrain(moving, "Renamed incoming")
                                        world.renameTrain(fixed, "Renamed stationary")
                                    }
                                    coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                                    renamed.get(5, TimeUnit.SECONDS)
                                    assertSame(collisionStep, moving.publishedFrame)
                                    assertSame(previousWorldFrame, world.latestWorldFrame())
                                    assertEquals("Renamed incoming", moving.name)
                                }
                                if (lowSpeed) {
                                    var steps = 0
                                    while (collisions.get() == 0 && steps++ < 150) {
                                        collisionStep = moving.publishedFrame
                                        coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                                    }
                                    val gapStart = moving.head.location.forward(0.05)!!
                                    val gap = gapStart.peekCurve(0.4).map { segment ->
                                        val length = segment.railView.curve.totalArcLength
                                        fun canonical(arc: Double) = if (segment.railView.viewDirection == ViewDirection.AB) arc else length - arc
                                        val start = canonical(segment.startArcLength)
                                        val end = canonical(segment.endArcLength)
                                        RailInterval(segment.railView.rail.id, minOf(start, end), maxOf(start, end))
                                    }
                                    assertTrue(world.latestWorldFrame().occupancy.blockingTrainIds(gap).isEmpty(),
                                        "collision-only connector paths must not occupy ordinary block intervals")
                                    assertTrue(moving.headLocation.position.distance(fixed.tailLocation.position) > 0.5)
                                } else coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                                assertEquals(1, collisions.get())
                                val frozenMoving = moving.publishedFrame
                                val callsAtFreeze = calls.get()
                                if (staleTopology) createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(50.0, 1))
                                if (clearReverse) fixed.head.location.rail.viewBA.activeRail = null
                                coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                                coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                                assertEquals(callsAtFreeze, calls.get(), "collision freeze must not consume another command")
                                val rejectedBeforeDecision = staleTopology || bothMoving
                                if (rejectedBeforeDecision) assertNull(observedEnds)
                                else assertEquals(movingEnd to fixedEnd, observedEnds)
                                if (failPreparation || rejectedBeforeDecision || veto) {
                                    assertEquals(0, successes)
                                    assertEquals(2, world.allTrains.size)
                                    assertEquals(frozenMoving.head, moving.publishedFrame.head)
                                    assertEquals(frozenMoving.tail, moving.publishedFrame.tail)
                                    assertEquals(frozenMoving.cars, moving.publishedFrame.cars)
                                    assertEquals(originalMovingCars, moving.cars)
                                    originalMovingCars.forEach { assertSame(moving, it.train) }
                                    assertTrue(moving.isDerailed)
                                    assertTrue(fixed.isDerailed)
                                } else {
                                    val master = if (movingMaster) moving else fixed
                                    assertEquals(1, successes)
                                    assertTrue(coherentAtSuccess, "success listener must observe complete shared publication")
                                    if (renameOnSuccess) {
                                        assertFalse(requireNotNull(deferredRename).isDone)
                                        assertNotEquals("After link", master.name)
                                        coordinator.processBetweenFrames { coordinator.drainBetweenFramesOperations() }
                                        requireNotNull(deferredRename).get(5, TimeUnit.SECONDS)
                                        assertEquals("After link", master.name)
                                    }
                                    if (renameBeforeCollision) {
                                        assertEquals("Renamed incoming", master.publishedFrame.trainName)
                                        assertFalse(master.isDerailed)
                                    }
                                    assertEquals(if (third == null) setOf(master) else setOf(master, third), world.allTrains.toSet())
                                    assertSame(master.publishedFrame, success)
                                    assertEquals(3, master.cars.size)
                                    assertEquals(0.0, master.speed)
                                    assertTrue(master.publishedFrame.cars.all { car ->
                                        (listOf(car.state) + car.bogies).all {
                                            it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
                                        }
                                    })
                                    val movedFront = originalMovingCars.let {
                                        if (movingEnd == TrainEndSide.B) it.last() else it.first()
                                    }
                                    if (shape == "straight") assertEquals(94.9, movedFront.position.x(), 0.005)
                                    val contactDirection = Vector3d(movedFront.direction)
                                        .mul(if (movingEnd == TrainEndSide.B) -1.0 else 1.0)
                                    val fixedState = fixedBefore.cars.single().state
                                    val fixedContact = fixedState.positionVector().add(fixedState.directionVector()
                                        .mul(if (fixedEnd == TrainEndSide.A) 5.0 else -5.0))
                                    val movingContact = Vector3d(movedFront.position).add(contactDirection.mul(5.0))
                                    assertEquals(0.6, movingContact.distance(fixedContact), Curve.TOLERANCE)
                                    val publishedFront = master.publishedFrame.cars.single { it.carId == movedFront.id }
                                    assertEquals(0.0, movedFront.position.distance(publishedFront.state.positionVector()), 1e-9)
                                    originalMovingCars.forEach { car ->
                                        assertSame(master, car.train)
                                        val frame = master.publishedFrame.cars.single { it.carId == car.id }
                                        assertEquals(0.0, car.position.distance(frame.state.positionVector()), 1e-9)
                                        assertEquals(0.0, car.direction.distance(frame.state.directionVector()), 1e-9)
                                        assertEquals(0.0, car.normal.distance(frame.state.normalVector()), 1e-9)
                                        val firstAxle = Vector3d(car.bogieA.position).add(Vector3d(car.bogieA.normal).mul(0.4))
                                        val secondAxle = Vector3d(car.bogieB.position).add(Vector3d(car.bogieB.normal).mul(0.4))
                                        assertEquals(6.0, firstAxle.distance(secondAxle), Curve.TOLERANCE)
                                        car.bogies.zip(frame.bogies).forEach { (bogie, value) ->
                                            assertEquals(0.0, bogie.position.distance(value.positionVector()), 1e-9)
                                            assertEquals(0.0, bogie.direction.distance(value.directionVector()), 1e-9)
                                            assertEquals(0.0, bogie.normal.distance(value.normalVector()), 1e-9)
                                        }
                                    }
                                    originalFixedCars.forEach { assertSame(master, it.train) }
                                    if (movingMaster) {
                                        assertSame(controller, master.controller)
                                        assertEquals("P2", controller.handle)
                                        assertEquals(0.0, controller.speed)
                                    }
                                }
                                val fixedCar = originalFixedCars.single()
                                val fixedState = fixedBefore.cars.single().state
                                assertEquals(0.0, fixedCar.position.distance(fixedState.positionVector()), 0.0)
                                assertEquals(0.0, fixedCar.direction.distance(fixedState.directionVector()), 0.0)
                                assertEquals(0.0, fixedCar.normal.distance(fixedState.normalVector()), 0.0)
                                fixedCar.bogies.zip(fixedBefore.cars.single().bogies).forEach { (bogie, frame) ->
                                    assertEquals(0.0, bogie.position.distance(frame.positionVector()), 0.0)
                                    assertEquals(0.0, bogie.direction.distance(frame.directionVector()), 0.0)
                                    assertEquals(0.0, bogie.normal.distance(frame.normalVector()), 0.0)
                                }
                                if (third != null) {
                                    assertEquals("moving", world.querySweptCollision(third).collidingTrainId)
                                    coordinator.stepSimulation(50.0).completion.get(5, TimeUnit.SECONDS)
                                    assertEquals(2, collisions.get(), "removed train ID must not mask the next obstacle")
                                    assertEquals(1, calls.get())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
