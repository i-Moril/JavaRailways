package work.art1st.javarailways.basic.infrastructure

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.api.physics.infrastructure.StableInfrastructureResource
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import java.util.concurrent.TimeUnit

class FormationOutputAdmissionTest {
    @Test
    fun `resumed output appends exact corridor while original body protection remains held`() {
        Fixture(provider = true).use { f ->
            val base = f.body(f.train)
            val short = base.copy(route = RouteSnapshot(base.route.routeId, base.route.revision,
                base.route.topologyRevision, listOf(base.route.segments.single().copy(endArcLengthMeters = 40.0))))
            f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(short, f.body(f.child)))) }
            val firstResult = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle,
                short.copy(desiredAuthority = RouteBoundary(short.route.totalLengthMeters))) }
            val first = assertInstanceOf(RouteAdmissionResult.Granted::class.java, firstResult, firstResult.toString()).grant
            f.controller(f.train)
            f.owner { f.accept(f.runtime.resumeFormationOutput(f.requester, f.handle, first.grantId)) }
            val retained = f.claims()
            val continuation = short.copy(route = RouteSnapshot(short.route.routeId, short.route.revision + 1,
                short.route.topologyRevision, short.route.segments + RouteSegmentSnapshot(f.rail.id, ViewDirection.AB, 40.0, 200.0)),
                desiredAuthority = RouteBoundary(60.0))
            f.shortenClearance = true
            val shortened = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle, continuation) }
            val denied = assertInstanceOf(RouteAdmissionResult.Rejected::class.java, shortened, shortened.toString())
            assertTrue(denied.failure.detail.contains("shortened retained original"), denied.failure.detail)
            assertEquals(retained, f.claims(), "invalid provider evidence must not change existing claims")
            f.shortenClearance = false
            f.extensionPreparation = true
            val pendingResult = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle, continuation) }
            val pending = assertInstanceOf(RouteAdmissionResult.Pending::class.java, pendingResult, pendingResult.toString())
            f.preparationState = ResourcePreparationState.ABORTED_SAFE
            f.owner { assertInstanceOf(RouteAdmissionResult.Rejected::class.java, f.runtime.progress(f.requester, pending.handle)) }
            assertEquals(retained, f.claims())
            f.preparationState = ResourcePreparationState.READY
            val granted = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle, continuation) }
            assertSame(first.grantId, assertInstanceOf(RouteAdmissionResult.Granted::class.java, granted, granted.toString()).grant.grantId)
            assertTrue(f.child.movementHeld)
            f.coordinator.start()
            repeat(25) { f.step() }
            assertTrue(f.train.publishedFrame.head.arcLengthInAbMeters > 40.0)
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @Test
    fun `held body selects exact departure after safe abort and second output can select later`() {
        Fixture(provider = true).use { f ->
            fun bodyOnly(train: CoreTrain): RouteResourceIntent {
                val intent = f.body(train)
                val segment = intent.route.segments.single()
                return intent.copy(route = RouteSnapshot(intent.route.routeId, intent.route.revision,
                    intent.route.topologyRevision, listOf(segment.copy(endArcLengthMeters = train.publishedFrame.head.arcLengthInAbMeters))))
            }
            val frontBody = bodyOnly(f.train)
            val rearBody = bodyOnly(f.child)
            fun departure(body: RouteResourceIntent, authority: Double): RouteResourceIntent {
                val old = body.route
                val end = old.segments.last().endArcLengthMeters
                return body.copy(observationStamp = f.observation.stamp,
                    route = RouteSnapshot(old.routeId, old.revision + 1, old.topologyRevision,
                        old.segments + RouteSegmentSnapshot(f.rail.id, ViewDirection.AB, end, 200.0)),
                    desiredAuthority = RouteBoundary(authority))
            }
            val bound = f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle,
                listOf(frontBody, rearBody))) }
            val originals = f.claims()
            val chosen = departure(frontBody, 80.0)
            val altered = chosen.copy(route = RouteSnapshot(chosen.route.routeId, chosen.route.revision,
                chosen.route.topologyRevision, chosen.route.segments.mapIndexed { index, segment ->
                    if (index == 0) segment.copy(startArcLengthMeters = segment.startArcLengthMeters - 1.0) else segment
                }))
            f.owner { assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                f.runtime.requestFormationOutput(f.requester, f.handle, altered)) }
            assertEquals(originals, f.claims())
            f.extensionPreparation = true
            val pending = f.owner { assertInstanceOf(RouteAdmissionResult.Pending::class.java,
                f.runtime.requestFormationOutput(f.requester, f.handle, chosen)) }
            assertTrue(f.train.movementHeld && f.child.movementHeld)
            f.preparationState = ResourcePreparationState.ABORTED_SAFE
            f.owner { assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                f.runtime.progress(f.requester, pending.handle)) }
            assertEquals(originals, f.claims(), "safe abort preserves the original body grant and source claims")
            f.preparationState = ResourcePreparationState.READY
            val front = f.owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                f.runtime.requestFormationOutput(f.requester, f.handle, chosen)).grant }
            assertSame(bound.outputGrants.single { it.trainId == f.train.id }.grantId, front.grantId)
            f.controller(f.train)
            f.owner { f.accept(f.runtime.resumeFormationOutput(f.requester, f.handle, front.grantId)) }
            f.coordinator.start()
            repeat(40) { f.step() }
            assertTrue(f.train.publishedFrame.head.arcLengthInAbMeters > 60.0,
                "installed progress cursor must include the selected suffix")
            assertTrue(f.child.movementHeld)
            val blocked = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle, departure(rearBody, 40.0)) }
            assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT,
                assertInstanceOf(RouteAdmissionResult.Rejected::class.java, blocked, blocked.toString()).failure.code,
                "the front output still owns its complete acquired interval even after its body moves clear")
            val extension = f.owner { f.runtime.request(f.requester, chosen.copy(
                observationStamp = f.observation.stamp, desiredAuthority = RouteBoundary(160.0))) }
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, extension, extension.toString())
            repeat(50) { f.step() }
            assertTrue(f.train.publishedFrame.tail.arcLengthInAbMeters > 100.0)
            val rearResult = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle, departure(rearBody, 40.0)) }
            val rear = assertInstanceOf(RouteAdmissionResult.Granted::class.java, rearResult, rearResult.toString()).grant
            f.owner { f.accept(f.runtime.resumeFormationOutput(f.requester, f.handle, rear.grantId)) }
            assertFalse(f.child.movementHeld, "second deferred output can choose after its sibling departs")
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `changed held output after ready cannot release stale authority`(reverse: Boolean) {
        Fixture().use { f ->
            f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle,
                listOf(f.body(f.train), f.body(f.child)))) }
            val front = f.request(f.train, 40.0)
            val protected = f.claims()
            f.owner {
                if (reverse) f.train.reverse()
                else {
                    f.train.setLocation(work.art1st.javarailways.core.physics.rail.RailLocationWithFacing(
                        f.rail.viewAB, f.train.publishedFrame.head.arcLengthInAbMeters + 0.1))
                    f.train.commitMaintenancePlacement()
                }
                // No intervening consume: release must validate its own physical preconditions.
                assertInstanceOf(FormationChangeResult.Rejected::class.java,
                    f.runtime.resumeFormationOutput(f.requester, f.handle, front.grantId))
            }
            assertTrue(f.train.movementHeld && f.child.movementHeld)
            assertEquals(protected, f.claims())
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @Test
    fun `both split bodies retain originals until both clear then normal owner lifecycle resumes`() {
        Fixture().use { f ->
            val originals = f.claims()
            assertInstanceOf(FormationChangeResult.Rejected::class.java, f.owner {
                f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(f.body(f.train)))
            })
            assertEquals(originals, f.claims())
            val wrongTail = f.body(f.train).let { intent ->
                val segment = intent.route.segments.single()
                intent.copy(route = RouteSnapshot(intent.route.routeId, intent.route.revision, intent.route.topologyRevision, listOf(segment.copy(startArcLengthMeters = segment.startArcLengthMeters - 1.0))),
                    desiredAuthority = RouteBoundary(intent.desiredAuthority.distanceFromRouteStartMeters - 1.0))
            }
            assertInstanceOf(FormationChangeResult.Rejected::class.java, f.owner {
                f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(wrongTail, f.body(f.child)))
            })
            assertEquals(originals, f.claims())
            val bound = f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle,
                listOf(f.body(f.train), f.body(f.child)))) }
            assertEquals(FormationChangePhase.OUTPUTS_BOUND, bound.phase)
            assertEquals(2, bound.outputGrants.size)
            assertEquals(originals, f.claims())
            assertTrue(f.train.movementHeld && f.child.movementHeld)
            assertInstanceOf(FormationChangeResult.Rejected::class.java, f.owner {
                f.runtime.resumeFormationOutput(f.requester, f.handle, bound.outputGrants.first().grantId)
            })
            val front = f.request(f.train, 40.0)
            assertSame(bound.outputGrants.single { it.trainId == f.train.id }.grantId, front.grantId)
            assertEquals(bound.outputGrants.single { it.trainId == f.train.id }.owner, front.owner)
            assertNull(front.couplingContactAuthority)
            assertNull(front.maximumSpeedMetersPerSecond)
            f.controller(f.train)
            f.owner { f.accept(f.runtime.resumeFormationOutput(f.requester, f.handle, front.grantId)) }
            assertFalse(f.train.movementHeld)
            assertTrue(f.child.movementHeld)
            f.coordinator.start()
            repeat(12) { f.step() }
            assertEquals(FormationChangePhase.OUTPUTS_ACTIVE, f.phase())
            assertTrue(f.world.infrastructureCoordination.claimSnapshots().any { it.footprint.resources.containsAll(originals.single().second) },
                "stationary child must retain the whole exact original resource")
            val blocked = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle, f.body(f.child).copy(desiredAuthority = RouteBoundary(40.0))) }
            assertEquals(RouteAdmissionFailureCode.PHYSICALLY_OCCUPIED,
                assertInstanceOf(RouteAdmissionResult.Rejected::class.java, blocked).failure.code)
            val protected = f.owner { f.runtime.requestFormationOutput(f.requester, f.handle,
                f.body(f.child).copy(desiredAuthority = RouteBoundary(21.0))) }
            assertEquals(RouteAdmissionFailureCode.RESOURCE_CONFLICT,
                assertInstanceOf(RouteAdmissionResult.Rejected::class.java, protected).failure.code)
            assertTrue(f.stalls.isEmpty(), "ordinary contention must not stall the world")
            f.request(f.train, 80.0)
            repeat(30) { f.step() }
            // Extending again must not reacquire the already-cleared prefix behind the front.
            f.request(f.train, 100.0)
            val rear = f.request(f.child, 40.0)
            f.controller(f.child)
            f.owner { f.accept(f.runtime.resumeFormationOutput(f.requester, f.handle, rear.grantId)) }
            var steps = 0
            while (f.phase() != FormationChangePhase.COMPLETED && steps++ < 30) f.step()
            assertEquals(FormationChangePhase.COMPLETED, f.phase())
            assertTrue(f.world.infrastructureCoordination.claimSnapshots().none {
                it.footprint.resources.any { resource -> resource in originals.single().second }
            })
            // The survivor retains the same logical owner, grant and monotonic route. It can now
            // extend through the ordinary service rather than reacquiring/renaming its old claim.
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, f.owner {
                f.runtime.request(f.requester, f.body(f.train).copy(desiredAuthority = RouteBoundary(120.0)))
            })
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @Test
    fun `continuing output detaches while HOLD stays protected and can prepare a second real split`() {
        Fixture(frontCars = 2, childCars = 2).use { f ->
            val originalResources = f.claims().single().second
            f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(f.body(f.train), f.body(f.child)))) }
            val ready = f.request(f.train, 60.0)
            f.controller(f.train)
            f.owner { f.accept(f.runtime.resumeFormationOutput(f.requester, f.handle, ready.grantId)) }
            f.coordinator.start()
            repeat(22) { f.step() }
            assertEquals(FormationChangePhase.OUTPUTS_ACTIVE, f.phase())
            assertTrue(f.child.movementHeld)
            val next = f.owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                f.runtime.request(f.requester, f.body(f.train).copy(desiredAuthority = RouteBoundary(80.0)))).grant }
            assertEquals(ready.owner, next.owner)
            assertTrue(f.claims().any { it.second.containsAll(originalResources) })
            val childFrame = f.child.publishedFrame
            val unprepared = f.child.splitBefore(f.child.cars.last().id, "illegal-child")
            f.owner { f.coordinator.drainBetweenFramesOperations() }
            assertThrows(java.util.concurrent.ExecutionException::class.java) { unprepared.get(5, TimeUnit.SECONDS) }
            assertSame(childFrame, f.child.publishedFrame)
            assertFalse(f.owner { f.runtime.endGrant(f.requester, next.grantId) }, "occupied own body still needs its own grant")
            f.train.setControllerForTest(object : TrainController(f.train) {
                override fun tick(deltaT: Double) { speed = 0.0; moveControlledTrain(0.0, 0.0) }
            })
            f.step()
            val identity = FormationTrainIdentity(f.train.id, f.train.cars.map { it.id }, false)
            val second = FixedFormationChangeIntent.Split("test:second-split", next.grantId, identity, f.train.cars.last().id,
                FormationTrainIdentity(f.train.id, identity.carIds.take(1), false),
                FormationTrainIdentity("grandchild", identity.carIds.drop(1), false))
            val prepared = f.owner { f.accept(f.runtime.prepareFormationChange(f.requester, second)) }
            f.owner { f.accept(f.runtime.sealFormationPublication(f.requester, prepared.handle)) }
            val publication = f.train.splitBefore(second.beforeCarId, second.separated.trainId)
            f.owner { f.coordinator.drainBetweenFramesOperations() }
            publication.get(5, TimeUnit.SECONDS)
            f.refresh()
            f.owner { f.runtime.consumeObservation(f.observation) }
            assertEquals(FormationChangePhase.OUTPUTS_HELD,
                f.owner { f.accept(f.runtime.formationChangeState(f.requester, prepared.handle)).phase })
            assertEquals(FormationChangePhase.OUTPUTS_ACTIVE, f.phase(), "old group must not observe detached source membership")
            assertTrue(f.child.movementHeld)
            assertTrue(f.claims().any { it.second.containsAll(originalResources) })
            assertTrue(f.runtime.requesterActivity(f.requester).isNotEmpty())
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @Test
    fun `output provider must explicitly declare every retained clearance exactly once`() {
        Fixture(provider = true).use { f ->
            val before = f.claims()
            f.completeClearance = false
            assertInstanceOf(FormationChangeResult.Rejected::class.java, f.owner {
                f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(f.body(f.train), f.body(f.child)))
            })
            assertEquals(before, f.claims())
            assertTrue(f.train.movementHeld && f.child.movementHeld)
            f.completeClearance = true
            f.duplicateClearance = true
            assertInstanceOf(FormationChangeResult.Rejected::class.java, f.owner {
                f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(f.body(f.train), f.body(f.child)))
            })
            assertEquals(before, f.claims())
            f.duplicateClearance = false
            f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(f.body(f.train), f.body(f.child)))) }
            assertEquals(before, f.claims())
            assertTrue(f.stalls.isEmpty())
        }
    }

    @Test
    fun `delayed output READY accepts real safe tail advance without reacquiring cleared body prefix`() {
        Fixture(provider = true).use { f ->
            f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle, listOf(f.body(f.train), f.body(f.child)))) }
            val ready = f.request(f.train, 40.0)
            f.controller(f.train)
            f.owner { f.accept(f.runtime.resumeFormationOutput(f.requester, f.handle, ready.grantId)) }
            f.coordinator.start()
            f.step()
            f.extensionPreparation = true
            val pending = f.owner { assertInstanceOf(RouteAdmissionResult.Pending::class.java,
                f.runtime.requestFormationOutput(f.requester, f.handle, f.body(f.train).copy(desiredAuthority = RouteBoundary(60.0)))) }
            assertSame(ready, pending.committedGrant)
            val stillPending = f.owner { assertInstanceOf(RouteAdmissionResult.Pending::class.java,
                f.runtime.progress(f.requester, pending.handle)) }
            assertSame(ready, stillPending.committedGrant)
            assertTrue(f.world.infrastructureCoordination.claimSnapshots().any { f.extensionResource in it.footprint.resources })
            val oldTail = f.train.publishedFrame.tail.arcLengthInAbMeters
            f.step()
            assertTrue(f.train.publishedFrame.tail.arcLengthInAbMeters > oldTail)
            assertEquals(ready.committedAuthority, f.runtime.activeAdmissions().single { it.owner == ready.owner }.committedAuthority)
            f.preparationState = ResourcePreparationState.READY
            val granted = f.owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                f.runtime.progress(f.requester, pending.handle)).grant }
            assertSame(ready.grantId, granted.grantId)
            assertEquals(ready.owner, granted.owner)
            assertEquals(ready.routeId, granted.routeId)
            assertEquals(ready.routeRevision, granted.routeRevision)
            assertEquals(RouteBoundary(60.0), granted.committedAuthority)
            assertEquals(f.observation.stamp, granted.observationStamp)
            assertEquals(granted, f.owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                f.runtime.progress(f.requester, pending.handle)).grant })
            assertTrue(f.child.movementHeld)
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `pending output abort rolls back only safe delta and unsafe outcome retains protection`(unsafe: Boolean) {
        Fixture(provider = true).use { f ->
            f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle,
                listOf(f.body(f.train), f.body(f.child)))) }
            val ready = f.request(f.train, 40.0)
            val originalsAndCommitted = f.claims()
            f.extensionPreparation = true
            val pending = f.owner { assertInstanceOf(RouteAdmissionResult.Pending::class.java,
                f.runtime.requestFormationOutput(f.requester, f.handle,
                    f.body(f.train).copy(desiredAuthority = RouteBoundary(60.0)))) }
            assertSame(ready, pending.committedGrant)
            val reserved = f.claims()
            assertTrue(reserved.any { f.extensionResource in it.second })
            f.owner { assertInstanceOf(RouteAdmissionResult.Pending::class.java,
                f.runtime.progress(f.requester, pending.handle)) }
            assertEquals(1, f.preparationStarts)
            f.preparationState = if (unsafe) ResourcePreparationState.UNSAFE else ResourcePreparationState.ABORTED_SAFE
            val terminal = f.owner { assertInstanceOf(RouteAdmissionResult.Rejected::class.java,
                f.runtime.progress(f.requester, pending.handle)) }
            assertSame(terminal, f.owner { f.runtime.progress(f.requester, pending.handle) })
            assertEquals(1, f.preparationStarts)
            assertTrue(f.train.movementHeld && f.child.movementHeld)
            assertEquals(ready.committedAuthority,
                f.runtime.activeAdmissions().single { it.owner == ready.owner }.committedAuthority)
            if (unsafe) {
                assertEquals(reserved, f.claims())
                assertEquals(FormationChangePhase.RECOVERY_REQUIRED, f.phase())
                assertEquals(1, f.stalls.size)
                f.owner { assertInstanceOf(FormationChangeResult.Rejected::class.java,
                    f.runtime.resumeFormationOutput(f.requester, f.handle, ready.grantId)) }
            } else {
                assertEquals(RouteAdmissionFailureCode.PREPARATION_ABORTED_SAFE, terminal.failure.code)
                assertEquals(originalsAndCommitted, f.claims())
                assertTrue(f.claims().none { f.extensionResource in it.second })
                assertTrue(f.stalls.isEmpty())
            }
        }
    }

    @Test
    fun `held output reverses with exact body rebase and no resource release or reacquisition`() {
        Fixture().use { f ->
            val bound = f.owner { f.accept(f.runtime.bindFormationOutputs(f.requester, f.handle,
                listOf(f.body(f.train), f.body(f.child)))) }
            val old = bound.outputGrants.single { it.trainId == f.child.id }
            val protected = f.claims()
            f.owner {
                assertInstanceOf(RouteGrantReversalResult.Rejected::class.java,
                    f.runtime.suspendReleaseForReversal(Any(), old.grantId))
                assertInstanceOf(RouteGrantReversalResult.Accepted::class.java,
                    f.runtime.suspendReleaseForReversal(f.requester, old.grantId))
                val busy = assertInstanceOf(FormationChangeResult.Rejected::class.java,
                    f.runtime.resumeFormationOutput(f.requester, f.handle, old.grantId))
                assertEquals(RouteAdmissionFailureCode.REQUEST_BUSY, busy.failure.code)
                f.child.reverse()
            }
            f.coordinator.start()
            f.coordinator.stepSimulation(50.0).await()
            f.owner { f.refresh(); f.runtime.consumeObservation(f.observation) }
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
            assertEquals(protected, f.claims())
            val frame = f.child.publishedFrame
            val length = f.rail.curve.totalArcLength
            val intent = f.body(f.child).copy(route = RouteSnapshot("reversed-child", 3, f.world.topologyState.revision,
                listOf(RouteSegmentSnapshot(f.rail.id, ViewDirection.BA,
                    length - frame.tail.arcLengthInAbMeters, length - frame.head.arcLengthInAbMeters))),
                desiredAuthority = RouteBoundary(frame.tail.arcLengthInAbMeters - frame.head.arcLengthInAbMeters))
            val rejected = f.owner { f.runtime.rebaseAfterReversal(f.requester,
                intent.copy(desiredAuthority = RouteBoundary(intent.desiredAuthority.distanceFromRouteStartMeters - 1.0))) }
            assertInstanceOf(RouteGrantReversalResult.Rejected::class.java, rejected)
            assertTrue(f.child.movementHeld)
            assertEquals(protected, f.claims())
            val rebound = f.owner { assertInstanceOf(RouteGrantReversalResult.Accepted::class.java,
                f.runtime.rebaseAfterReversal(f.requester, intent)).grant }
            assertSame(old.grantId, rebound.grantId)
            assertEquals(old.owner, rebound.owner)
            assertEquals(protected, f.claims())
            val state = f.owner { f.accept(f.runtime.formationChangeState(f.requester, f.handle)) }
            assertTrue(state.outputs.single { it.trainId == f.child.id }.reversed)
            assertFalse(state.outputs.single { it.trainId == f.train.id }.reversed)
            assertTrue(f.child.movementHeld && f.train.movementHeld)
            f.owner { f.refresh(); f.runtime.consumeObservation(f.observation) }
            assertTrue(f.stalls.isEmpty(), f.stalls.toString())
        }
    }

    private class Fixture(frontCars: Int = 1, childCars: Int = 1, provider: Boolean = false) : AutoCloseable {
        val coordinator = PhysicsCoordinator(1, automaticFallback = false)
        val physical = TestWorldFixture.create(coordinator)
        val world = physical.world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0, 1))
        val train = createTrainWithConfig("source", rail, world, List(frontCars + childCars) { 10.0 })
        val requester = Any()
        val stalls = arrayListOf<String>()
        var observation = RailwayObservation.fromCore("outputs", world.latestWorldFrame())
        val yardResource = StableInfrastructureResource("test:yard", "held")
        val extensionResource = StableInfrastructureResource("test:device", "extension")
        private val contributor = Any()
        private val resourceRegistration = world.infrastructureCoordination.registerResourceValidator {
            provider && (it == yardResource || it == extensionResource)
        }
        var completeClearance = true
        var shortenClearance = false
        var duplicateClearance = false
        var extensionPreparation = false
        var preparationStarts = 0
        var preparationState = ResourcePreparationState.PREPARING
        private val ticket = object : ResourcePreparationTicket {
            override fun snapshot() = ResourcePreparationSnapshot(preparationState)
            override fun abort() { preparationState = ResourcePreparationState.ABORTED_SAFE }
        }
        val runtime = RouteResourceAdmissionRuntime.create("outputs", world, world.infrastructureCoordination,
            Any(), { observation }, { intent -> buildList {
                if (provider) add(contributor to ContributedRouteResource(yardResource, RouteBoundary(minOf(
                    intent.desiredAuthority.distanceFromRouteStartMeters,
                    maxOf(0.0, 30.0 - intent.route.segments.first().startArcLengthMeters)))))
                if (extensionPreparation && intent.formationContext?.purpose == FormationRoutePurpose.OUTPUT_DEPARTURE &&
                    intent.desiredAuthority.distanceFromRouteStartMeters > 40.0)
                    add(contributor to ContributedRouteResource(extensionResource, intent.desiredAuthority,
                        ResourcePreparationDescriptor("test:prepare", emptyMap())))
            } }, { _, _ -> null }, { resource, _ -> if (resource == extensionResource) ({ preparationStarts++; ticket }) else null },
            authorizeFormationRoute = { context, intent ->
                val declared = if (!completeClearance) emptyList() else context.retainedStableResources.map {
                    FormationResourceClearance(it, RouteBoundary(minOf(intent.route.totalLengthMeters,
                        maxOf(0.0, 30.0 - intent.route.segments.first().startArcLengthMeters -
                            (if (shortenClearance) 1.0 else 0.0)))))
                }
                FormationRoutePolicyResult.Accepted(if (duplicateClearance) declared + declared else declared)
            },
            stallWorld = { reason, _ -> stalls += reason })
        val handle: FormationChangeHandle
        val child: CoreTrain
        private val bodies = linkedMapOf<String, RouteResourceIntent>()
        init {
            val source = owner { assertInstanceOf(RouteAdmissionResult.Granted::class.java,
                runtime.request(requester, RouteResourceIntent("test:source", InfrastructureOwner("test:source", 1), train.id,
                    observation.stamp, RouteSnapshot("test:source", 1, world.topologyState.revision,
                        listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 10.0, 200.0))), RouteBoundary(train.cars.sumOf { it.length })))).grant }
            handle = owner {
                val prepared = accept(runtime.prepareFormationChange(requester, FixedFormationChangeIntent.Split("test:split",
                    source.grantId, FormationTrainIdentity(train.id, train.cars.map { it.id }, false), train.cars[frontCars].id,
                    FormationTrainIdentity(train.id, train.cars.take(frontCars).map { it.id }, false),
                    FormationTrainIdentity("child", train.cars.drop(frontCars).map { it.id }, false))))
                accept(runtime.sealFormationPublication(requester, prepared.handle))
                prepared.handle
            }
            val future = train.splitBefore(train.cars[frontCars].id, "child")
            owner { coordinator.drainBetweenFramesOperations() }
            child = future.get(5, TimeUnit.SECONDS) as CoreTrain
            refresh()
            owner { runtime.consumeObservation(observation) }
            assertEquals(FormationChangePhase.OUTPUTS_HELD, phase())
            listOf(train, child).forEach { output ->
                val frame = output.publishedFrame
                bodies[output.id] = RouteResourceIntent("test:output/${output.id}",
                    InfrastructureOwner(if (output === train) "test:source" else "test:child", 1), output.id,
                    observation.stamp, RouteSnapshot("test:output/${output.id}", 2, world.topologyState.revision,
                        listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, frame.tail.arcLengthInAbMeters, 200.0))),
                    RouteBoundary(frame.head.arcLengthInAbMeters - frame.tail.arcLengthInAbMeters))
            }
        }
        fun <T> owner(action: () -> T): T = world.onOwnerForTest(action)
        fun body(output: CoreTrain) = checkNotNull(bodies[output.id]).copy(observationStamp = observation.stamp)
        fun refresh() { observation = RailwayObservation.fromCore("outputs", world.latestWorldFrame()) }
        fun accept(result: FormationChangeResult) = assertInstanceOf(FormationChangeResult.Accepted::class.java, result, result.toString()).snapshot
        fun phase() = owner { accept(runtime.formationChangeState(requester, handle)).phase }
        fun claims() = world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint.resources }
        fun request(output: CoreTrain, authority: Double) = owner {
            val result = runtime.requestFormationOutput(requester, handle, body(output).copy(desiredAuthority = RouteBoundary(authority)))
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, result, result.toString()).grant
        }
        fun controller(output: CoreTrain) { output.setControllerForTest(object : TrainController(output) {
            override fun tick(deltaT: Double) { speed = 1.0; moveControlledTrain(speed * deltaT * 0.001, speed) }
        }) }
        fun step() {
            repeat(5) {
                coordinator.stepSimulation(200.0).await()
                owner { coordinator.drainBetweenFramesOperations(); refresh(); runtime.consumeObservation(observation) }
            }
        }
        override fun close() { try { runtime.close() } finally { try { resourceRegistration.close(); physical.close() } finally { coordinator.close() } } }
    }
}
