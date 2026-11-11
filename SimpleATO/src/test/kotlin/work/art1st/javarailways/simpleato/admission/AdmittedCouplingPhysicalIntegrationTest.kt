package work.art1st.javarailways.simpleato.admission

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.control.HandleTrainActuator
import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.event.CollisionEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.PhysicsRailEventBus
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.api.physics.train.CouplingApproachInspection
import work.art1st.javarailways.core.api.physics.train.CouplingApproachRequest
import work.art1st.javarailways.core.api.physics.train.TrainEndSide
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.timetable.*
import work.art1st.javarailways.simpleato.api.ato.AtoSafetyProfile
import work.art1st.javarailways.simpleato.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.simpleato.api.mission.*
import work.art1st.javarailways.simpleato.api.restriction.CurrentSpeedRestriction
import work.art1st.javarailways.simpleato.api.restriction.TrainRestrictionSnapshot
import work.art1st.javarailways.simpleato.api.route.FrozenRouteBinding
import work.art1st.javarailways.simpleato.controller.SimpleATO
import work.art1st.javarailways.simpleato.restriction.TrainRestrictionSnapshotStore
import work.art1st.javarailways.simpleato.routing.FrozenMissionRoute
import work.art1st.javarailways.simpleato.routing.FrozenRouteSelectionResult
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry

/** Real Basic capability, SimpleATO, handle dynamics, Core collision and structural publication. */
@OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
class AdmittedCouplingPhysicalIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `real contact grant obeys independent zero cap then physically couples`(curved: Boolean) {
        Fixture(curved).use { f ->
            val before = f.moving.publishedFrame.head
            f.red(true)
            f.physics.start()
            repeat(12) { f.tick() }
            assertEquals(before, f.moving.publishedFrame.head)
            assertEquals(0.0, f.moving.publishedFrame.speedMetersPerSecond)
            assertEquals(2, f.world.allTrains.size)
            val owners = f.world.infrastructureCoordination.claimSnapshots().map { it.owner }.toSet()
            f.red(false)
            var peakSpeed = 0.0
            var collisions = 0
            val trace = mutableListOf<String>()
            PhysicsRailEventBus.register(object : EventListener {
                @HandlesEvent
                fun collision(event: CollisionEvent) {
                    collisions++
                }
            }).use {
                var steps = 0
                while (f.world.allTrains.size == 2 && steps++ < 500) {
                    f.tick()
                    if (steps < 20 || steps % 50 == 0) {
                        val diagnostics = f.ato.runtimeDiagnostics
                        trace += "step=$steps head=${f.moving.publishedFrame.head} speed=${f.moving.speed} " +
                            "selected=${f.actuator.selectedHandle} " +
                            "device=${f.actuator.dynamicsDeviceStateFor(f.moving.cars.first())} " +
                            "cap=${diagnostics.permittedSpeedMetersPerSecond} " +
                            "remaining=${diagnostics.movementAuthorityLimitMeters - (diagnostics.projectedProgressMeters ?: 0.0)}"
                    }
                    peakSpeed = maxOf(peakSpeed, f.moving.publishedFrame.speedMetersPerSecond)
                    assertTrue(f.moving.publishedFrame.speedMetersPerSecond <= CAP + 0.01)
                }
            }
            assertTrue(peakSpeed > CAP * 0.5, "real handle dynamics must accelerate the physical body")
            assertTrue(collisions > 0, "Core must observe actual contact\n${trace.joinToString("\n")}")
            assertEquals(1, f.world.allTrains.size, f.ato.runtimeDiagnostics.toString())
            assertSame(f.moving, f.world.allTrains.single())
            assertEquals(setOf("moving-car", "target-car"), f.moving.cars.map { it.id }.toSet())
            assertTrue(f.moving.movementHeld)
            assertEquals(0.0, f.moving.publishedFrame.speedMetersPerSecond)
            assertEquals(owners, f.world.infrastructureCoordination.claimSnapshots().map { it.owner }.toSet())
            assertEquals(FormationChangePhase.OUTPUTS_HELD, f.owner {
                assertInstanceOf(FormationChangeResult.Accepted::class.java,
                    f.runtime.formationChangeState(f.requester, f.handle)).snapshot.phase
            })
            assertInstanceOf(FixedFormationOperation.Progress.Held::class.java, f.owner { f.operation.progress() })
            assertEquals(listOf(f.operation.ledgerChange), f.depots.unresolvedFormationChanges())
            assertEquals(setOf("unit-moving", "unit-target"), f.depots.allFixedUnitAssignments().map { it.unitId }.toSet())
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @Test
    fun `changed peer invalidates contact exemption before actual movement`() {
        Fixture(false).use { f ->
            f.red(true)
            f.physics.start()
            repeat(4) { f.tick() }
            val before = f.moving.publishedFrame.head
            f.owner {
                f.target.setLocation(RailLocationWithFacing(f.rail.viewAB, 110.6))
                f.target.commitMaintenancePlacement()
                f.refresh()
            }
            assertFalse(f.owner { f.world.validateCouplingApproach(f.proof) })
            f.red(false)
            repeat(30) { f.tick() }
            assertEquals(before, f.moving.publishedFrame.head)
            assertEquals(0.0, f.moving.publishedFrame.speedMetersPerSecond)
            assertEquals(2, f.world.allTrains.size)
            assertTrue(f.ato.runtimeDiagnostics.permittedSpeedMetersPerSecond == 0.0)
            assertTrue(f.stalls.any { it == "coupling target moved or approaching speed exceeded its admitted cap" },
                "Basic must report the changed sealed peer while the controller holds: ${f.stalls}")
        }
    }

    private class Fixture(curved: Boolean) : AutoCloseable {
        val physics = PhysicsCoordinator(1, automaticFallback = false)
        private val physical = TestWorldFixture.create(physics)
        val world = physical.world
        private val coordinator = AtoWorldCoordinator(WORLD, reportSink = {})
        init { TestSimpleATOWorldDataRegistry.register(world, BasicWorldData(safetyInterlock = coordinator.safetyInterlock), coordinator) }
        val rail = createStraightRailFromSegments(world, if (curved) PrimeCurveGenerator.buildCircle(30.0, 1, 0.02)
            else PrimeCurveGenerator.buildStraight(200.0, 1))
        private fun train(id: String, head: Double): CoreTrain = owner {
            val anchor = RailLocationWithFacing(rail.viewAB, head)
            val car = CoreCar.create("$id-car", CoreBogie.create(anchor), CoreBogie.create(anchor),
                length = 10.0, bogieAOffset = 3.0, bogieBOffset = -3.0,
                connectorLengthAtA = 0.3, connectorLengthAtB = 0.3, world = world)
            CoreTrain.create(id, listOf(car), world).also {
                it.setLocation(anchor)
                it.commitMaintenancePlacement()
            }
        }
        val moving = train("moving", 99.0)
        val target = train("target", 110.5)
        val actuator = HandleTrainActuator(moving, Dynamics, listOf(CarDynamics), 9.8)
        val ato = SimpleATO(moving, world, actuator,
            AtoSafetyProfile(accelerationMetersPerSecondSquared = 1.0,
                serviceDecelerationMetersPerSecondSquared = 2.0, reactionTimeSeconds = 0.0,
                safetyDistanceMeters = 0.1), inspectionMaximumLeadTimeSeconds = { 0.0 })
        init {
            moving.setControllerForTest(ato)
            target.setControllerForTest(SimpleATO(target, world,
                HandleTrainActuator(target, Dynamics, listOf(CarDynamics), 9.8), inspectionMaximumLeadTimeSeconds = { 0.0 }))
        }
        val requester = Any()
        val stalls = arrayListOf<String>()
        private val signals = TrainRestrictionSnapshotStore(WORLD)
        private var signalRevision = -1L
        var observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame())
        val runtime = RouteResourceAdmissionRuntime.create(WORLD, world, world.infrastructureCoordination,
            Any(), { observation }, { emptyList() }, { _, _ -> null }, { _, _ -> null },
            authorizeFormationRoute = { _, _ -> FormationRoutePolicyResult.Accepted(emptyList()) },
            stallWorld = { reason, _ -> stalls += reason })
        val depots = DepotStore().also { store ->
            listOf(moving, target).forEach { source ->
                val depot = DepotId("home-${source.id}")
                val point = StablingPointRef(depot, "p")
                store.addStablingPoint(depot, depot.value, DepotStablingPoint(point, "point-${source.id}"))
                val cars = source.cars.map { it.id }
                store.registerFixedUnit(FixedEmuUnit("unit-${source.id}", depot, cars, "emu"), cars)
                store.registerRollingStock(RollingStockRecord(source.id, depot, "emu", emptySet(), cars.size,
                    source.cars.sumOf { it.length }, 2.0, emptySet()), point)
                val allocation = checkNotNull(store.reservePhysicallySelected(source.id, point,
                    DepotTrainSelector(depot), "assignment-${source.id}", cars))
                check(store.confirmInService(allocation)); check(store.releaseDeparture(allocation))
            }
        }
        private val service = object : RouteResourceAdmissionService {
            override fun prepareFormationChange(intent: FixedFormationChangeIntent) = runtime.prepareFormationChange(requester, intent)
            override fun sealFormationPublication(handle: FormationChangeHandle) = runtime.sealFormationPublication(requester, handle)
            override fun formationChangeState(handle: FormationChangeHandle) = runtime.formationChangeState(requester, handle)
            override fun abortFormationChange(handle: FormationChangeHandle) = runtime.abortFormationChange(requester, handle)
            override fun request(intent: RouteResourceIntent) = runtime.request(requester, intent)
            override fun progress(handle: RouteAdmissionHandle) = runtime.progress(requester, handle)
            override fun suspendReleaseForReversal(grantId: RouteGrantId) = runtime.suspendReleaseForReversal(requester, grantId)
            override fun rebaseAfterReversal(intent: RouteResourceIntent) = runtime.rebaseAfterReversal(requester, intent)
            override fun endGrant(grantId: RouteGrantId) = runtime.endGrant(requester, grantId)
        }
        val proof = owner { assertInstanceOf(CouplingApproachInspection.Accepted::class.java,
            world.inspectCouplingApproach(CouplingApproachRequest(moving.id, TrainEndSide.A, target.id, TrainEndSide.B))).proof }
        private val movingOwner = InfrastructureOwner("test:owner/moving", 1)
        val operation = owner {
            fun source(train: CoreTrain, sourceOwner: InfrastructureOwner): RouteGrantSnapshot {
                val frame = train.publishedFrame
                return assertInstanceOf(RouteAdmissionResult.Granted::class.java, runtime.request(requester,
                    RouteResourceIntent("test:request/${train.id}", sourceOwner, train.id, observation.stamp,
                        RouteSnapshot("test:source/${train.id}", 1, world.topologyState.revision,
                            listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, frame.tail.arcLengthInAbMeters,
                                rail.curve.totalArcLength))), RouteBoundary(frame.head.arcLengthInAbMeters - frame.tail.arcLengthInAbMeters)))).grant
            }
            val approaching = source(moving, movingOwner)
            val stationary = source(target, InfrastructureOwner("test:owner/target", 1))
            val event = TimetableFormationEvent.Couple("join", "moving-duty", "moving-duty", "target-duty",
                FormationStationaryEnd.TAIL, FormationRendezvous("approach", "target"), CAP,
                TimetableFormationOutput("combined", FormationOutputDisposition.Duty("onward")))
            val plan = FixedUnitCouplePlan.prepare(event, "cycle-0/join", proof, "assignment-moving", "assignment-target",
                approaching, stationary, "assignment-combined", depots)
            FixedFormationOperation.startCouple(world, depots, plan, service, stalls::add)
        }
        val handle get() = operation.handle
        private val route = RouteSnapshot("test:contact", 2, world.topologyState.revision,
            listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, proof.approachingFrame.tail.arcLengthInAbMeters,
                proof.contactHead.arcLengthInAbMeters)))
        private val grant = owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
            runtime.requestCouplingApproach(requester, handle, RouteResourceIntent("test:request/contact", movingOwner,
                moving.id, observation.stamp, route, RouteBoundary(route.totalLengthMeters)))).grant }
        private val selection = FrozenRouteSelectionResult.Selected(moving.id, observation.stamp, route,
            work.art1st.javarailways.simpleato.routing.RouteOperatingDirectionLimit.calculate(
                observation.stamp.worldId, route, world.topologyState, world.basicWorldData.operatingDirections.snapshot(
                    world.topologyState, route.segments.map { it.railId })))
        private val admission = RouteAuthorityAdmissionClient(moving.id, "test:consumer", movingOwner,
            service, ato.trainRestrictionPublisher)
        init { owner {
            val distance = proof.contactHead.arcLengthInAbMeters - proof.approachingFrame.head.arcLengthInAbMeters
            val origin = AtoMissionOrigin.FormationContact(operation.ledgerChange.eventId, proof.approachingFrame.head)
            val mission = AtoMission("contact", 2, origin, listOf(AtoMissionLeg(0,
                AtoMissionPoint.Stop(WayPointRef("contact")), PlannedRoute("test:movement", 2,
                    ExpandedRoute(origin.id, "contact", listOf(RouteSegment(RailViewRef(rail.id, ViewDirection.AB),
                        proof.approachingFrame.head.arcLengthInAbMeters, proof.contactHead.arcLengthInAbMeters)))))))
            assertInstanceOf(AtoMissionSubmissionResult.Accepted::class.java, ato.submitMission(mission))
            ato.setMovementAuthority(mission.revision, distance)
            ato.bindTrainRestrictionRoute(observation.stamp,
                FrozenMissionRoute(selection, proof.approachingFrame.head.arcLengthInAbMeters - proof.approachingFrame.tail.arcLengthInAbMeters, 0.0),
                mission.revision, signals)
            assertNotNull(grant.couplingContactAuthority, "descriptor must be issued by actual Basic runtime")
            admission.adoptFormationGrant(selection, grant)
        } }
        fun <T> owner(action: () -> T): T = world.onOwnerForTest(action)
        fun refresh() { observation = RailwayObservation.fromCore(WORLD, world.latestWorldFrame()) }
        fun red(blocked: Boolean) = owner {
            signals.publish(TrainRestrictionSnapshot("test:independent-red", ++signalRevision, moving.id,
                observation.stamp, FrozenRouteBinding.from(route), if (blocked)
                    listOf(CurrentSpeedRestriction("test:red-zero", 0.0)) else emptyList()))
            signals.completeEpoch(observation.stamp, setOf(moving.id))
        }
        fun tick() {
            physics.stepSimulation(50.0).await()
            owner {
                physics.drainBetweenFramesOperations()
                refresh()
                runtime.consumeObservation(observation)
            }
            assertFalse(coordinator.isStalled, coordinator.lastStall.toString())
        }
        override fun close() {
            try { runtime.close() } finally {
                TestSimpleATOWorldDataRegistry.unregister(world)
                try { physical.close() } finally { physics.close() }
            }
        }
    }
    private object Dynamics : TrainDynamics.TrainProfile {
        override val maxSpeed = 2.0
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
        override val brakeMaxForce = 20_000.0
        override val motorAdhesionMassRatio = 1.0
        override val brakeAdhesionMassRatio = 1.0
        override fun motorForceAt(speed: Double) = 10_000.0
        override fun resistanceForceAt(speed: Double, defaultResistance: (Double) -> Double) = 0.0
    }
    private companion object {
        const val WORLD = "physical-ato-contact"
        const val CAP = 0.2
    }
}
