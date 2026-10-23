package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSegmentSnapshot
import work.art1st.javarailways.basic.api.ato.snapshot.RouteSnapshot
import work.art1st.javarailways.basic.api.observation.RailwayObservation
import work.art1st.javarailways.basic.api.route.*
import work.art1st.javarailways.basic.infrastructure.admission.RouteResourceAdmissionRuntime
import work.art1st.javarailways.core.api.physics.infrastructure.InfrastructureOwner
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.simpleato.api.depot.*

class FixedFormationOperationTest {
    @Test
    fun `single real split publication retains ledger and original resource claims until observed outputs are held`() {
        Fixture().use { f ->
            val claims = f.world.infrastructureCoordination.claimSnapshots()
            val operation = f.owner { FixedFormationOperation.startSplit(f.world, f.depots, f.plan, f.service, f.failures::add) }
            f.owner { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            repeat(3) { assertEquals(FixedFormationOperation.Progress.Pending, f.owner { operation.progress() }) }
            assertEquals(1, f.preparations)
            assertEquals(2, f.world.allTrains.size)
            f.owner {
                f.observation = RailwayObservation.fromCore("split-operation", f.world.latestWorldFrame())
                f.runtime.consumeObservation(f.observation)
            }
            val held = assertInstanceOf(FixedFormationOperation.Progress.Held::class.java, f.owner { operation.progress() })
            assertEquals(FormationChangePhase.OUTPUTS_HELD, held.snapshot.phase)
            assertEquals(f.plan.ledgerChange.outputs.map { it.owner.trainId }.toSet(), held.snapshot.outputs.map { it.trainId }.toSet())
            assertTrue(f.world.allTrains.all { (it as CoreTrain).movementHeld })
            assertEquals(claims.map { it.owner to it.footprint.resources },
                f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint.resources })
            assertEquals(listOf(f.plan.ledgerChange), f.depots.unresolvedFormationChanges())
            assertEquals("source-assignment", f.depots.rollingStock("source")!!.assignmentId)
            assertTrue(f.failures.isEmpty(), f.failures.toString())
        }
    }

    @Test
    fun `ledger preflight failure aborts unsealed Basic preparation without changing source or claims`() {
        Fixture().use { f ->
            val original = f.train.publishedFrame
            val claims = f.world.infrastructureCoordination.claimSnapshots()
            val bad = FixedUnitSplitPlan.prepare(f.event, "wrong-owner", original, "wrong-assignment",
                f.grant, f.depots, "child", mapOf("first" to "next", "rest" to "hold"))
            assertThrows(IllegalArgumentException::class.java) {
                f.owner { FixedFormationOperation.startSplit(f.world, f.depots, bad, f.service, f.failures::add) }
            }
            assertSame(original, f.train.publishedFrame)
            assertEquals(1, f.world.allTrains.size)
            assertTrue(f.depots.unresolvedFormationChanges().isEmpty())
            assertEquals(claims.map { it.owner to it.footprint.resources },
                f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint.resources })
            val resumed = f.owner { f.runtime.request(f.requester, f.intent.copy(desiredAuthority = RouteBoundary(30.0))) }
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, resumed, resumed.toString())
            assertTrue(f.failures.isEmpty())
        }
    }

    @Test
    fun `output owners commit while departure waits and HOLD remains independently fenced after finish`() {
        Fixture().use { f ->
            val operation = f.publishSplit()
            val intents = f.bindOutputs(operation)
            val installed = arrayListOf<String>()
            val handoff = f.owner { FixedFormationOutputHandoff.begin(f.world, f.depots, f.plan.ledgerChange,
                operation.handle, f.service, f.outputRecords(), { output, grant ->
                    assertTrue(f.world.allTrains.all { (it as CoreTrain).movementHeld })
                    assertEquals(output.owner.trainId, grant.trainId)
                    installed += output.owner.trainId
                }, f.failures::add) }
            assertEquals(2, installed.size)
            assertTrue(f.depots.unresolvedFormationChanges().isEmpty())
            assertEquals(f.plan.ledgerChange.outputs.map { FixedFormationHeldOutput(it.owner, it.disposition) }.toSet(),
                f.depots.heldFormationOutputs().toSet())
            assertInstanceOf(FixedFormationOutputHandoff.Progress.Waiting::class.java, f.owner { handoff.progress() })
            assertTrue(f.world.allTrains.all { (it as CoreTrain).movementHeld })
            val front = intents.single { it.trainId == f.plan.outputs.getValue("first").trainId }
            assertInstanceOf(RouteAdmissionResult.Granted::class.java, f.owner {
                f.service.requestFormationOutput(operation.handle, front.copy(desiredAuthority = RouteBoundary(30.0))) })
            assertEquals(FixedFormationOutputHandoff.Progress.Completed, f.owner { handoff.progress() })
            assertEquals(FixedFormationOutputHandoff.Progress.Completed, f.owner { handoff.progress() })
            assertFalse((f.world.getTrain(front.trainId) as CoreTrain).movementHeld)
            val held = f.plan.outputs.getValue("rest")
            assertTrue((f.world.getTrain(held.trainId) as CoreTrain).movementHeld)
            assertEquals(listOf(FixedFormationHeldOutput(held)), f.depots.heldFormationOutputs())
            assertTrue(f.depots.unresolvedFormationChanges().isEmpty())
            assertEquals(2, installed.size)
            assertTrue(f.failures.isEmpty(), f.failures.toString())
        }
    }

    @Test
    fun `failed output installation retains source ledger and every physical fence`() {
        Fixture().use { f ->
            val operation = f.publishSplit()
            f.bindOutputs(operation)
            val claims = f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint.resources }
            assertThrows(IllegalStateException::class.java) {
                f.owner { FixedFormationOutputHandoff.begin(f.world, f.depots, f.plan.ledgerChange,
                    operation.handle, f.service, f.outputRecords(), { _, _ -> error("controller installation failed") }, f.failures::add) }
            }
            assertEquals(listOf(f.plan.ledgerChange), f.depots.unresolvedFormationChanges())
            assertTrue(f.world.allTrains.all { (it as CoreTrain).movementHeld })
            assertEquals(claims, f.world.infrastructureCoordination.claimSnapshots().map { it.owner to it.footprint.resources })
            assertTrue(f.failures.single().contains("controller installation failed"))
        }
    }

    private class Fixture : AutoCloseable {
        val fixture = TestWorldFixture.create()
        val world = fixture.world
        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0, 1))
        val train = createTrainWithConfig("source", rail, world, listOf(10.0, 10.0))
        val requester = Any()
        val failures = arrayListOf<String>()
        var preparations = 0
        var observation = RailwayObservation.fromCore("split-operation", world.latestWorldFrame())
        val runtime = RouteResourceAdmissionRuntime.create("split-operation", world,
            world.infrastructureCoordination, Any(), { observation }, { emptyList() }, { _, _ -> null },
            { _, _ -> null }, authorizeFormationRoute = { _, _ -> FormationRoutePolicyResult.Accepted(emptyList()) }, stallWorld = { detail, _ -> failures += detail })
        val intent = RouteResourceIntent("test:source", InfrastructureOwner("test:source", 0), train.id,
            observation.stamp, RouteSnapshot("test:route", 0, world.topologyState.revision,
                listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, 10.0, 200.0))), RouteBoundary(20.0))
        val grant = owner { (runtime.request(requester, intent) as RouteAdmissionResult.Granted).grant }
        val depots = DepotStore().also { store ->
            val depot = DepotId("D")
            val point = StablingPointRef(depot, "p")
            store.addStablingPoint(depot, "D", DepotStablingPoint(point, "point"))
            val cars = train.cars.map { it.id }
            cars.forEachIndexed { index, car -> store.registerFixedUnit(FixedEmuUnit("unit-$index", depot, listOf(car), "emu"), cars) }
            store.registerRollingStock(RollingStockRecord(train.id, depot, "emu", emptySet(), 2, 20.0, 30.0, emptySet()), point)
            val allocation = checkNotNull(store.reservePhysicallySelected(train.id, point, DepotTrainSelector(depot), "source-assignment", cars))
            check(store.confirmInService(allocation)); check(store.releaseDeparture(allocation))
        }
        val event = TimetableFormationEvent.Split("split", "source-duty", 1,
            TimetableFormationOutput("first", FormationOutputDisposition.Duty("source-duty")),
            TimetableFormationOutput("rest", FormationOutputDisposition.Hold))
        val plan = FixedUnitSplitPlan.prepare(event, "cycle-0/split", train.publishedFrame,
            "source-assignment", grant, depots, "child", mapOf("first" to "source-assignment", "rest" to "hold-assignment"))
        val service = object : RouteResourceAdmissionService {
            override fun prepareFormationChange(intent: FixedFormationChangeIntent): FormationChangeResult {
                preparations++
                return runtime.prepareFormationChange(requester, intent)
            }
            override fun sealFormationPublication(handle: FormationChangeHandle) = runtime.sealFormationPublication(requester, handle)
            override fun formationChangeState(handle: FormationChangeHandle) = runtime.formationChangeState(requester, handle)
            override fun abortFormationChange(handle: FormationChangeHandle) = runtime.abortFormationChange(requester, handle)
            override fun bindFormationOutputs(handle: FormationChangeHandle, intents: List<RouteResourceIntent>) = runtime.bindFormationOutputs(requester, handle, intents)
            override fun requestFormationOutput(handle: FormationChangeHandle, intent: RouteResourceIntent) = runtime.requestFormationOutput(requester, handle, intent)
            override fun resumeFormationOutput(handle: FormationChangeHandle, grantId: RouteGrantId) = runtime.resumeFormationOutput(requester, handle, grantId)
            override fun request(intent: RouteResourceIntent) = runtime.request(requester, intent)
            override fun progress(handle: RouteAdmissionHandle) = runtime.progress(requester, handle)
            override fun suspendReleaseForReversal(grantId: RouteGrantId) = runtime.suspendReleaseForReversal(requester, grantId)
            override fun rebaseAfterReversal(intent: RouteResourceIntent) = runtime.rebaseAfterReversal(requester, intent)
            override fun endGrant(grantId: RouteGrantId) = runtime.endGrant(requester, grantId)
        }
        fun publishSplit(): FixedFormationOperation {
            val operation = owner { FixedFormationOperation.startSplit(world, depots, plan, service, failures::add) }
            owner {
                world.physicsCoordinator.drainBetweenFramesOperations()
                observation = RailwayObservation.fromCore("split-operation", world.latestWorldFrame())
                runtime.consumeObservation(observation)
            }
            assertInstanceOf(FixedFormationOperation.Progress.Held::class.java, owner { operation.progress() })
            return operation
        }
        fun bindOutputs(operation: FixedFormationOperation): List<RouteResourceIntent> {
            val intents = plan.ledgerChange.outputs.map { output ->
                val frame = world.getTrain(output.owner.trainId)!!.publishedFrame!!
                RouteResourceIntent("test:output/${frame.trainId}", if (frame.trainId == train.id) intent.owner
                    else InfrastructureOwner("test:child", 0), frame.trainId, observation.stamp,
                    RouteSnapshot("test:output-route/${frame.trainId}", 1, world.topologyState.revision,
                        listOf(RouteSegmentSnapshot(rail.id, ViewDirection.AB, frame.tail.arcLengthInAbMeters, 200.0))),
                    RouteBoundary(frame.head.arcLengthInAbMeters - frame.tail.arcLengthInAbMeters))
            }
            assertInstanceOf(FormationChangeResult.Accepted::class.java, owner { service.bindFormationOutputs(operation.handle, intents) })
            return intents
        }
        fun outputRecords(): List<RollingStockRecord> {
            val original = depots.rollingStock(train.id)!!.record
            return plan.ledgerChange.outputs.map { output ->
                val physical = world.getTrain(output.owner.trainId)!!
                original.copy(trainId = physical.id, carCount = physical.cars.size, lengthMeters = physical.cars.sumOf { it.length })
            }
        }
        fun <T> owner(action: () -> T): T = world.onOwnerForTest(action)
        override fun close() { try { runtime.close() } finally { fixture.close() } }
    }
}
