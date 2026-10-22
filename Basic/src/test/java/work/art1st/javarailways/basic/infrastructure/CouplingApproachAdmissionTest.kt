package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.control.TrainController
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

@OptIn(work.art1st.javarailways.core.api.util.annotation.AsyncEventApi::class)
class CouplingApproachAdmissionTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource("false,0.0,false", "false,0.3,false", "true,0.3,false", "false,0.0,true", "true,0.3,true")
    fun `admitted exact low speed contact physically couples while both original owners remain held`(curved: Boolean, connectorLength: Double, stopOnContact: Boolean) {
        Fixture(curved, connectorLength).use { f ->
            val handle = f.prepare()
            val grant = f.owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                f.runtime.requestCouplingApproach(f.requester, handle, f.approachIntent())).grant }
            assertEquals(0.2, grant.maximumSpeedMetersPerSecond)
            assertEquals(grant.committedAuthority, checkNotNull(grant.couplingContactAuthority).contactBoundary)
            val publication = f.runtime.activeAdmissions().single { it.owner == grant.owner }
            assertEquals(grant.routeId, publication.route.routeId)
            assertEquals(FormationRoutePurpose.COUPLING_APPROACH, checkNotNull(publication.formationContext).purpose)
            val originalOwners = f.world.infrastructureCoordination.claimSnapshots().map { it.owner }.toSet()
            var movedWithZeroFinalSpeed = false
            f.moving.setControllerForTest(object : TrainController(f.moving) {
                override fun tick(deltaT: Double) {
                    speed = checkNotNull(grant.maximumSpeedMetersPerSecond)
                    check(f.world.validatePublishedCouplingApproach(f.proof))
                    val requested = speed * deltaT * 0.001
                    val remaining = f.world.clampCouplingApproachDistance(f.proof, Double.MAX_VALUE)
                    val distance = minOf(remaining, requested)
                    if (stopOnContact && remaining <= requested + 1.0e-8) {
                        speed = 0.0
                        movedWithZeroFinalSpeed = distance > 0.0
                    }
                    moveControlledTrain(distance, speed)
                }
            })
            run {
                f.coordinator.start()
                var steps = 0
                while (f.world.allTrains.size == 2 && steps++ < 200) {
                    f.coordinator.stepSimulation(50.0).await()
                    f.owner {
                        f.coordinator.drainBetweenFramesOperations()
                        f.refresh()
                        f.runtime.consumeObservation(f.observation)
                    }
                }
            }
            assertEquals(stopOnContact, movedWithZeroFinalSpeed)
            assertEquals(1, f.world.allTrains.size)
            assertSame(f.moving, f.world.allTrains.single())
            assertTrue(f.moving.movementHeld)
            assertEquals(originalOwners, f.world.infrastructureCoordination.claimSnapshots().map { it.owner }.toSet())
            assertEquals(FormationChangePhase.OUTPUTS_HELD, f.owner {
                assertInstanceOf(FormationChangeResult.Accepted::class.java,
                    f.runtime.formationChangeState(f.requester, handle)).snapshot.phase
            })
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 0.3])
    fun `wrong boundary unsupported policy and intervening third party never widen source permission`(connectorLength: Double) {
        Fixture(false, connectorLength).use { f ->
            val handle = f.prepare()
            val original = f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint }
            val request = f.approachIntent()
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java, f.owner {
                f.runtime.requestCouplingApproach(f.requester, handle,
                    request.copy(desiredAuthority = RouteBoundary(request.desiredAuthority.distanceFromRouteStartMeters - 0.1)))
            })
            f.policyAllowed = false
            assertInstanceOf(RouteAdmissionResult.Rejected::class.java, f.owner {
                f.runtime.requestCouplingApproach(f.requester, handle, request)
            })
            f.policyAllowed = true
            val blocker = createTrainWithConfig("third", f.rail, f.world, listOf(0.1))
            f.owner {
                blocker.setLocation(RailLocationWithFacing(f.rail.viewAB, f.moving.publishedFrame.head.arcLengthInAbMeters + 0.3))
                blocker.commitMaintenancePlacement()
                f.refresh()
            }
            assertEquals(RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED, f.owner {
                assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                    f.runtime.requestCouplingApproach(f.requester, handle,
                        request.copy(observationStamp = f.observation.stamp))).failure.code
            })
            assertEquals(original, f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint })
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(InvalidContact::class)
    fun `only current READY exact pair contact may request physical linking`(mode: InvalidContact) {
        Fixture(false).use { f ->
            val handle = f.prepare()
            if (mode != InvalidContact.NO_READY_GRANT) f.owner {
                assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                    f.runtime.requestCouplingApproach(f.requester, handle, f.approachIntent()))
            }
            val moving = if (mode == InvalidContact.UNRELATED_PAIR) f.train("unrelated-moving", 10.0) else f.moving
            val target = if (mode == InvalidContact.UNRELATED_PAIR) f.train("unrelated-target", 10.0) else f.target
            f.owner {
                if (mode == InvalidContact.UNRELATED_PAIR) {
                    moving.setLocation(RailLocationWithFacing(f.rail.viewAB, 49.0)); moving.commitMaintenancePlacement()
                    target.setLocation(RailLocationWithFacing(f.rail.viewAB, 60.5)); target.commitMaintenancePlacement()
                } else if (mode == InvalidContact.STALE_PEER) {
                    target.setLocation(RailLocationWithFacing(f.rail.viewAB, 111.5)); target.commitMaintenancePlacement()
                }
                f.refresh()
            }
            val originals = f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint }
            var collisions = 0
            var requestedLink = false
            PhysicsRailEventBus.register(object : EventListener {
                @HandlesEvent fun observe(event: CollisionEvent) {
                    if (event.world === f.world && setOf(event.trainAId, event.trainBId) == setOf(moving.id, target.id)) {
                        collisions++
                        requestedLink = requestedLink || event.resolution == work.art1st.javarailways.core.api.event.CollisionResolution.LINK
                    }
                }
            }).use {
                // Deliberately unauthorized actuator exercises the collision fence independently
                // of the admission-aware controller; the test never requests a link itself.
                moving.setControllerForTest(object : TrainController(moving) {
                    override fun tick(deltaT: Double) { speed = 0.2; moveControlledTrain(speed * deltaT * 0.001, speed) }
                })
                f.coordinator.start()
                var steps = 0
                while (!moving.isDerailed && steps++ < 500) {
                    f.coordinator.stepSimulation(50.0).await()
                    f.owner { f.coordinator.drainBetweenFramesOperations(); f.refresh(); f.runtime.consumeObservation(f.observation) }
                }
            }
            assertTrue(collisions > 0, "fixture must reach an actual collision")
            assertFalse(requestedLink, "no exact current READY permission may authorize this collision")
            assertTrue(moving.isDerailed)
            assertEquals(if (mode == InvalidContact.UNRELATED_PAIR) 4 else 2, f.world.allTrains.size)
            assertEquals(originals, f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint })
        }
    }

    enum class InvalidContact { NO_READY_GRANT, STALE_PEER, UNRELATED_PAIR }

    private class Fixture(curved: Boolean, private val connectorLength: Double = 0.3) : AutoCloseable {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val physical = TestWorldFixture.create(coordinator)
        val world = physical.world
        val rail = createStraightRailFromSegments(world, if (curved) PrimeCurveGenerator.buildCircle(30.0, 1, 0.02)
            else PrimeCurveGenerator.buildStraight(200.0, 1))
        val moving = train("moving", 10.0)
        val target = train("target", 10.0)
        fun train(id: String, length: Double): CoreTrain = owner {
            val anchor = RailLocationWithFacing(rail.viewAB, 20.0)
            val car = CoreCar.create("$id-car", CoreBogie.create(anchor), CoreBogie.create(anchor),
                length = length, bogieAOffset = length * 0.3, bogieBOffset = -length * 0.3,
                connectorLengthAtA = connectorLength, connectorLengthAtB = connectorLength, world = world)
            CoreTrain.create(id, listOf(car), world).also {
                it.setLocation(anchor); it.commitMaintenancePlacement()
            }
        }
        val requester = Any()
        val stalls = arrayListOf<String>()
        var policyAllowed = true
        lateinit var observation: RailwayObservation
        init {
            owner {
                moving.setLocation(RailLocationWithFacing(rail.viewAB, 99.0)); moving.commitMaintenancePlacement()
                target.setLocation(RailLocationWithFacing(rail.viewAB, 110.5)); target.commitMaintenancePlacement()
            }
            refresh()
        }
        val runtime = RouteResourceAdmissionRuntime.create("contact", world, world.infrastructureCoordination,
            Any(), { observation }, { emptyList() }, { _, _ -> null }, { _, _ -> null },
            authorizeFormationRoute = { _, _ -> if (policyAllowed) FormationRoutePolicyResult.Accepted(emptyList())
                else FormationRoutePolicyResult.Rejected(RouteAdmissionFailure(RouteAdmissionFailureCode.INVALID_CONTRIBUTION,
                    "policy rejected contact")) }, stallWorld = { reason, _ -> stalls += reason })
        val proof = owner { assertInstanceOf(CouplingApproachInspection.Accepted::class.java, world.inspectCouplingApproach(
            CouplingApproachRequest(moving.id, TrainEndSide.A, target.id, TrainEndSide.B))).proof }

        fun <T> owner(action: () -> T): T = world.onOwnerForTest(action)
        fun refresh() { observation = RailwayObservation.fromCore("contact", world.latestWorldFrame()) }
        fun prepare(): FormationChangeHandle = owner {
            fun source(train: CoreTrain): RouteGrantSnapshot {
                val frame = train.publishedFrame
                return assertInstanceOf(RouteAdmissionResult.Granted::class.java, runtime.request(requester,
                    RouteResourceIntent("test:request/${train.id}", InfrastructureOwner("test:owner/${train.id}", 1),
                        train.id, observation.stamp, RouteSnapshot("test:route/${train.id}", 1, world.topologyState.revision,
                            listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, frame.tail.arcLengthInAbMeters,
                                rail.curve.totalArcLength))),
                        RouteBoundary(frame.head.arcLengthInAbMeters - frame.tail.arcLengthInAbMeters)))).grant
            }
            val a = source(moving)
            val b = source(target)
            val intent = FixedFormationChangeIntent.Couple("test:couple", a.grantId, b.grantId, proof,
                FormationTrainIdentity(moving.id, target.cars.map { it.id } + moving.cars.map { it.id }, false), 0.2)
            val prepared = assertInstanceOf(FormationChangeResult.Accepted::class.java,
                runtime.prepareFormationChange(requester, intent)).snapshot
            assertInstanceOf(FormationChangeResult.Accepted::class.java, runtime.sealFormationPublication(requester, prepared.handle))
            prepared.handle
        }
        fun approachIntent() = RouteResourceIntent("test:request/contact", InfrastructureOwner("test:owner/moving", 1),
            moving.id, observation.stamp, RouteSnapshot("test:route/contact", 2, world.topologyState.revision,
                listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, proof.approachingFrame.tail.arcLengthInAbMeters,
                    proof.contactHead.arcLengthInAbMeters))),
            RouteBoundary(proof.contactHead.arcLengthInAbMeters - proof.approachingFrame.tail.arcLengthInAbMeters))
        override fun close() { try { runtime.close() } finally { try { physical.close() } finally { coordinator.close() } } }
    }
}
