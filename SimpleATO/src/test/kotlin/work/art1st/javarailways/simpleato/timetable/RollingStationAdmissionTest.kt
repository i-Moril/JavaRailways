package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.WayPoint
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.destination.DestinationRole
import work.art1st.javarailways.simpleato.api.destination.OperationalDestination
import work.art1st.javarailways.simpleato.api.destination.OperationalDestinationService
import work.art1st.javarailways.simpleato.api.station.StationId
import work.art1st.javarailways.simpleato.api.timetable.*
import work.art1st.javarailways.simpleato.api.inspection.*
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.inspection.InspectionRuntime
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

/** Real Basic occupancy/claims, published Core physics, and handle-driven ATO movement.
 * The straight-track equipment profile is idealized; this is not real rolling-stock calibration.
 */
class RollingStationAdmissionTest {
    @Test
    fun `occupied distant station permits rolling approach then safe waiting and tail clear resumption`() {
        Fixture().use { f ->
            f.begin()
            f.physics.start()
            f.until(1200, "leader must berth before the following service starts") {
                f.status("front").state == TimetableServiceRunState.DWELLING
            }
            assertTrue(f.status("front").berthHeld)
            assertEquals(610.0, f.front.publishedFrame.head.arcLengthInAbMeters, 0.5)
            assertEquals(0.0, f.front.publishedFrame.speedMetersPerSecond)

            f.startRear()
            f.until(500, "rear must start while the destination berth is still occupied") {
                f.rear.publishedFrame.head.arcLengthInAbMeters > 25.0
            }
            assertEquals(TimetableServiceRunState.DWELLING, f.status("front").state)
            assertTrue(f.status("front").berthHeld)
            assertTrue(f.status("rear").berthHeld, "both calls may remain registered without exclusive permission")
            val first = f.requests.first { it.intent.trainId == f.rear.id }
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, first.result)
            assertTrue(first.intent.desiredAuthority.distanceFromRouteStartMeters < 100.0,
                "stationary startup must not reserve the roughly 600 metre mission")
            assertTrue(first.intent.route.totalLengthMeters > 580.0)
            assertTrue(f.movingClearanceRequests > 0, "moving requests must exercise dispatcher frame/body capture")

            f.until(3200, "rear must encounter occupancy and stop within its previous grant") {
                f.requests.any { it.intent.trainId == f.rear.id &&
                    (it.result as? RouteAdmissionResult.Rejected)?.failure?.code == RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED } &&
                    f.rear.publishedFrame.head.arcLengthInAbMeters > 500.0 &&
                    f.rear.publishedFrame.speedMetersPerSecond == 0.0
            }
            assertEquals(TimetableServiceRunState.DWELLING, f.status("front").state)
            val stoppedHead = f.rear.publishedFrame.head.arcLengthInAbMeters
            val lastGrant = f.latestRearGrant()
            val routeStart = lastGrant.intent.route.segments.first().startArcLengthMeters
            val boundary = routeStart + (lastGrant.result as RouteAdmissionResult.Granted)
                .grant.committedAuthority.distanceFromRouteStartMeters
            assertTrue(stoppedHead <= boundary + 1e-6, "ATO must not cross its committed authority")
            assertTrue(stoppedHead < f.front.publishedFrame.tail.arcLengthInAbMeters,
                "rear must stop before the actual occupied train body")
            repeat(100) { f.tick() }
            assertEquals(stoppedHead, f.rear.publishedFrame.head.arcLengthInAbMeters, 1e-6)
            assertEquals(0.0, f.rear.publishedFrame.speedMetersPerSecond)

            val requestCountBeforeClear = f.requests.size
            f.until(6000, "rear must resume after the leader departs and clears the actual resources") {
                f.rear.publishedFrame.head.arcLengthInAbMeters > stoppedHead + 1.0
            }
            assertTrue(f.front.publishedFrame.tail.arcLengthInAbMeters > 610.0,
                "the blocked station approach is released by physical tail clearance")
            assertTrue(f.requests.drop(requestCountBeforeClear).any {
                it.intent.trainId == f.rear.id && it.result is RouteAdmissionResult.Granted
            })
            assertFalse(f.status("rear").message.orEmpty().contains("PHYSICALLY_OCCUPIED"),
                "successful admission must clear the obsolete resource rejection")
            f.until(1800, "rear must complete its station call without a world stall") {
                f.status("rear").state == TimetableServiceRunState.COMPLETED
            }
            assertEquals(610.0, f.rear.publishedFrame.head.arcLengthInAbMeters, 0.5)
        }
    }

    private data class Request(val intent: RouteResourceIntent, val result: RouteAdmissionResult)

    private class Fixture : AutoCloseable {
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        private val fixture = TestWorldFixture.create(physics)
        private val world = fixture.world
        private val coordinator = AtoWorldCoordinator(WORLD, reportSink = {})
        init {
            TestSimpleATOWorldDataRegistry.register(world,
                BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator)
        }
        private val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(1000.0, segmentCount = 1, step = 1.0))
        val front = createTrainWithConfig("101N", rail, world, listOf(10.0), carIdPrefix = "101N/car-")
        val rear = createTrainWithConfig("103P", rail, world, listOf(10.0), carIdPrefix = "103P/car-")
        init {
            world.onOwnerForTest {
                front.setLocation(RailLocationWithFacing(rail.viewAB, 600.0))
                front.commitMaintenancePlacement()
                rear.setLocation(RailLocationWithFacing(rail.viewAB, 20.0))
                rear.commitMaintenancePlacement()
            }
        }
        private var observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame())
        private val failures = arrayListOf<String>()
        private val requester = Any()
        private val runtime = RouteResourceAdmissionRuntime.create(WORLD, world, world.infrastructureCoordination,
            Any(), { observation }, { emptyList() }, { _, _ -> null }, { _, _ -> null },
            stallWorld = { detail, _ -> failures += detail })
        val requests = arrayListOf<Request>()
        var movingClearanceRequests = 0
        private val service = object : RouteResourceAdmissionService {
            override fun request(intent: RouteResourceIntent): RouteAdmissionResult {
                if (intent.trainId == rear.id && rear.publishedFrame.speedMetersPerSecond > 0.0 &&
                    rear.publishedFrame.head.arcLengthInAbMeters < 100.0) {
                    movingClearanceRequests++
                    assertTrue(intent.desiredAuthority.distanceFromRouteStartMeters < intent.route.totalLengthMeters,
                        "a moving train with entry clearance must not lose its body and request the whole route")
                }
                val before = runtime.activeAdmissions().singleOrNull { it.trainId == intent.trainId }?.committedAuthority
                val result = runtime.request(requester, intent)
                requests += Request(intent, result)
                if (result is RouteAdmissionResult.Rejected) {
                    assertEquals(before, runtime.activeAdmissions().singleOrNull { it.trainId == intent.trainId }?.committedAuthority,
                        "a rejected extension must retain exactly the previously committed boundary")
                }
                return result
            }
            override fun progress(handle: RouteAdmissionHandle) = runtime.progress(requester, handle)
            override fun suspendReleaseForReversal(grantId: RouteGrantId) = runtime.suspendReleaseForReversal(requester, grantId)
            override fun rebaseAfterReversal(intent: RouteResourceIntent) = runtime.rebaseAfterReversal(requester, intent)
            override fun endGrant(grantId: RouteGrantId) = runtime.endGrant(requester, grantId)
        }
        private val signals = TrainRestrictionSnapshotStore(WORLD)
        private val inspection = InspectionRuntime(signals) { _, _, failure -> throw failure }
        init {
            val revision = java.util.concurrent.atomic.AtomicLong()
            inspection.replaceSources(listOf(InspectionSource("test:clearance", { input ->
                val movingRear = input.trainId == rear.id
                InspectionDeclarationBinding("test:clearance", input.trainId, FrozenRouteBinding.from(input.route),
                    1L, 1L, if (movingRear) listOf(InspectionDeclaration("test:entry", rail.id,
                        ViewDirection.AB, 50.0, 0.0)) else emptyList(),
                    entryClearances = if (movingRear) listOf(InspectionEntryClearance("test:entry", 50.0, 60.0)) else emptyList())
            }, { input -> listOf(TrainRestrictionSnapshot("test:clearance", revision.incrementAndGet(), input.perception.train.trainId,
                input.observationStamp, input.route, emptyList())) })))
        }
        private val profile = AtoSafetyProfile(accelerationMetersPerSecondSquared = 1.0,
            serviceDecelerationMetersPerSecondSquared = 1.0, reactionTimeSeconds = 1.0, safetyDistanceMeters = 5.0)
        private val dispatcher = TimetableDispatcher(authorityStoppingMarginMeters = profile.safetyDistanceMeters,
            routeAdmissionContextProvider = TimetableRouteAdmissionContextProvider { TimetableRouteAdmissionContext(observation, service) },
            signalRestrictionSnapshots = signals, inspectionRuntime = inspection,
            infrastructureDependencyService = acceptingInfrastructureDependencyService(),
            atoFactory = TimetableAtoFactory { train, owningWorld -> SimpleATO(train, owningWorld,
                HandleTrainActuator(train, Dynamics, List(train.cars.size) { CarDynamics }, 9.8),
                profile, inspection::maximumDeclaredLeadTimeSeconds) })
        init {
            world.onOwnerForTest {
                world.basicWorldData.wayPoints.add(WayPoint("front-origin", rail.id, 600.0))
                world.basicWorldData.wayPoints.add(WayPoint("rear-origin", rail.id, 20.0))
                world.basicWorldData.wayPoints.add(WayPoint("exit", rail.id, 900.0))
                OperationalDestinationService(world).register(OperationalDestination("central@one", rail.id,
                    610.0, ViewDirection.AB, DestinationRole.StationBerthRole(StationId("central"),
                        berthId = "one", doorSide = DoorSide.NONE)))
                world.simpleAtoWorldData.timetables.add(Timetable("rolling", services = listOf(
                    TimetableService("front", TrainSelector.TrainId(front.id), listOf(
                        TimetableStep.Check("front-origin"),
                        TimetableStep.At(0, TimetableAction.CallStation("central", "one", minimumDwellTicks = 4000)),
                        TimetableStep.At(0, TimetableAction.GoTo("central@one")),
                        TimetableStep.At(0, TimetableAction.GoTo("exit")))),
                    TimetableService("rear", TrainSelector.TrainId(rear.id), listOf(
                        TimetableStep.Check("rear-origin"),
                        TimetableStep.At(0, TimetableAction.CallStation("central", "one", minimumDwellTicks = 0)),
                        TimetableStep.At(0, TimetableAction.GoVia(listOf("front-origin", "central@one"))))))))
            }
        }

        fun begin() = world.onOwnerForTest {
            val epoch = dispatcher.beginTimetable(world, "rolling")
            assertInstanceOf(TimetableEpochStartResult.Started::class.java, epoch, epoch.toString())
            val result = dispatcher.startService(world, "rolling", "front")
            assertInstanceOf(TimetableServiceStartResult.Started::class.java, result, result.toString())
        }

        fun startRear() = world.onOwnerForTest {
            val result = dispatcher.startService(world, "rolling", "rear")
            assertInstanceOf(TimetableServiceStartResult.Started::class.java, result, result.toString())
        }

        fun status(service: String) = checkNotNull(dispatcher.status(TimetableServiceRunId("rolling", service)))
        fun latestRearGrant() = requests.last { it.intent.trainId == rear.id && it.result is RouteAdmissionResult.Granted }

        fun until(limit: Int, reason: String, condition: () -> Boolean) {
            repeat(limit) {
                if (condition()) return
                tick()
            }
            assertTrue(condition(), "$reason; ${dispatcher.statuses()}")
        }

        fun tick() {
            physics.stepSimulation(50.0).await()
            world.onOwnerForTest {
                physics.drainBetweenFramesOperations()
                observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame())
                runtime.consumeObservation(observation)
                dispatcher.tick(world)
            }
            assertTrue(failures.isEmpty(), failures.toString())
            assertFalse(coordinator.isStalled, coordinator.lastStall.toString())
            assertTrue(rear.publishedFrame.head.arcLengthInAbMeters < front.publishedFrame.tail.arcLengthInAbMeters,
                "published physical bodies must never overlap")
        }

        override fun close() {
            try { runtime.close() } finally {
                TestSimpleATOWorldDataRegistry.unregister(world)
                fixture.close()
                physics.close()
            }
        }
    }

    private object Dynamics : TrainDynamics.TrainProfile {
        override val maxSpeed = 35.0
        override val powerHandles = listOf("P1")
        override val brakeHandles = listOf("B1", "EB")
        override val brakeApplyTime = 0.0
        override val brakeReleaseTime = 0.0
        override val adhesionCoefficient = 1.0
        override val tractionJerk = 100.0
        override val brakeJerk = 100.0
        override fun normalizedPowerScale(handle: String) = if (handle == "P1") 1.0 else 0.0
        override fun normalizedBrakeScale(handle: String) = if (handle in brakeHandles) 1.0 else 0.0
        override fun resistanceForceAt(speed: Double) = 0.0
    }

    private object CarDynamics : TrainDynamics.CarProfile {
        override val mass = 10_000.0
        override val effectiveMass = mass
        override val brakeMaxForce = 10_000.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = 10_000.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }

    private companion object { const val WORLD = "rolling-station" }
}
