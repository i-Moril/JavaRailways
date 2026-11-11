package work.art1st.javarailways.simpleato.persistence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.core.api.event.TrainSplitEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import work.art1st.javarailways.core.util.TickManagerImpl
import work.art1st.javarailways.simpleato.api.depot.*
import work.art1st.javarailways.simpleato.simpleAtoWorldData
import work.art1st.javarailways.simpleato.testing.TestSimpleATOWorldDataRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FormationRecoverySnapshotTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5])
    fun `real Core snapshot across publication commit and finished HOLD restores exact independent fences`(stage: Int) {
        val published = stage > 0
        PhysicsCoordinator(1, automaticFallback = false).use { physics ->
        TestWorldFixture.create(physics).use { sourceFixture ->
            val source = sourceFixture.world
            TestSimpleATOWorldDataRegistry.register(source, BasicWorldData())
            val depot = DepotId("home")
            fun configure(world: CoreWorld) {
                world.simpleAtoWorldData.depots.addStablingPoint(depot, "Home", DepotStablingPoint(StablingPointRef(depot, "one"), "home-one"))
            }
            configure(source)
            val rail = createStraightRailFromSegments(source, PrimeCurveGenerator.buildStraight(200.0, 1))
            val train = createTrainWithConfig("combined", rail, source, listOf(10.0, 10.0))
            val cars = train.cars.map { it.id }
            val store = source.simpleAtoWorldData.depots
            cars.forEachIndexed { index, id -> store.registerFixedUnit(FixedEmuUnit("unit-$index", depot, listOf(id), "emu"), cars) }
            store.registerRollingStock(RollingStockRecord(train.id, depot, "test/emu", emptySet(), 2, 20.0, 20.0, emptySet()), StablingPointRef(depot, "one"))
            val allocation = checkNotNull(store.reservePhysicallySelected(train.id, StablingPointRef(depot, "one"), DepotTrainSelector(depot), "incoming", cars))
            assertTrue(store.confirmInService(allocation)); assertTrue(store.releaseDeparture(allocation))
            val change = FixedFormationChange("split-at-station", FixedFormationChangeKind.SPLIT,
                listOf(FixedFormationOwner(train.id, "incoming", cars)), listOf(
                    FixedFormationOutput(FixedFormationOwner(train.id, "outgoing", cars.take(1)), FixedFormationDisposition.CONTINUE_DUTY),
                    FixedFormationOutput(FixedFormationOwner("separated", "held", cars.drop(1)), FixedFormationDisposition.HOLD)))
            store.prepareFixedFormationChange(change, mapOf(train.id to cars), mapOf(train.id to train.isReversed))
            if (published) {
                MainRailEventBus.register(object : EventListener {
                    @HandlesEvent fun decide(event: TrainSplitEvent) { event.requestMovementHold() }
                }).use {
                    val future = train.splitBefore(cars.last(), "separated")
                    source.onOwnerForTest { physics.drainBetweenFramesOperations() }
                    future.get(5, TimeUnit.SECONDS)
                }
            }
            if (stage >= 2) {
                val outputs = change.outputs.map { it.owner }
                val committed = store.commitFixedFormationChange(change,
                    source.allTrains.associate { it.id to it.cars.map { car -> car.id } },
                    outputs.map { owner -> RollingStockRecord(owner.trainId, depot, if (stage == 2) null else "test/emu", emptySet(),
                        owner.carIds.size, 10.0, 20.0, emptySet()) }, source.allTrains.associate { it.id to it.isReversed })
                if (stage == 3 || stage == 5) store.finishFixedFormationChange(committed,
                    source.allTrains.associate { it.id to it.cars.map { car -> car.id } },
                    source.allTrains.associate { it.id to it.isReversed }, outputs,
                    if (stage == 5) listOf(outputs.first()) else emptyList())
            }
            if (stage == 4) {
                source.onOwnerForTest {
                    val hold = assertInstanceOf(work.art1st.javarailways.core.api.physics.train.FormationMovementHoldRestoration.Accepted::class.java,
                        source.restoreFormationMovementHold(train.id, train.cars.map { it.id })).hold
                    assertTrue(source.releaseFormationMovementHold(hold))
                }
                train.setControllerForTest(object : work.art1st.javarailways.core.api.control.TrainController(train) {
                    override fun tick(deltaT: Double) { speed = 0.2; moveControlledTrain(speed * deltaT * 0.001, speed) }
                })
                physics.start()
                physics.stepSimulation(50.0).await()
                assertTrue(train.publishedFrame.speedMetersPerSecond > 0.0, "checkpoint must contain actual moving committed output")
            }
            val infrastructure = source.saveInfrastructure(0L)
            val epoch = source.latestWorldFrame().physicsEpoch
            val coreRuntime = source.saveRuntime(0L, epoch)
            val operational = SimpleATORuntimeSnapshotCodec.decode(SimpleATORuntimeSnapshotCodec.encode(SimpleATORuntimeSnapshotV1.capture(source, 0L, epoch)))
            TickManagerImpl(Executors.newSingleThreadScheduledExecutor(), 0L).use { tick ->
                PhysicsCoordinator(1, automaticFallback = false).use { restoredPhysics ->
                    val restored = IsolatedSimulationThreadMarker.run { CoreWorld.load(infrastructure, coreRuntime, CoreWorld.SnapshotDependency(tick, restoredPhysics)) }
                    TestSimpleATOWorldDataRegistry.register(restored, BasicWorldData())
                    configure(restored)
                    val savedHeads = restored.allTrains.associate { it.id to it.publishedFrame!!.head }
                    // Core's current save boundary contains pose/direction, not controller or
                    // velocity. Even a genuinely moving checkpoint loads stopped before ticks.
                    if (stage == 4) assertTrue(restored.allTrains.all { body -> body.publishedFrame!!.let { frame ->
                        frame.speedMetersPerSecond == 0.0 && frame.cars.all { car -> (car.bogies + car.state).all {
                            it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
                        } }
                    } })
                    restored.onOwnerForTest { operational.restore(restored) }
                    assertEquals(savedHeads, restored.allTrains.associate { it.id to it.publishedFrame!!.head }, "recovery may clear motion but never change saved pose")
                    assertTrue(restored.allTrains.all { body -> body.publishedFrame!!.let { frame ->
                        frame.speedMetersPerSecond == 0.0 && frame.cars.all { car -> (car.bogies + car.state).all {
                            it.velocityX == 0.0 && it.velocityY == 0.0 && it.velocityZ == 0.0
                        } }
                    } })
                    val ledger = restored.simpleAtoWorldData.depots
                    if (stage == 2) assertTrue(ledger.allRollingStock().all { it.record.modelPackageId == null },
                        "mixed aggregate model absence must survive actual Core and operational save/load")
                    if (stage == 3 || stage == 5) {
                        assertTrue(ledger.recoveredFormationHolds().isEmpty())
                        assertEquals(if (stage == 5) change.outputs.map { it.owner } else listOf(change.outputs.last().owner),
                            ledger.heldFormationOutputs().map { it.owner })
                        if (stage == 5) {
                            val pending = ledger.heldFormationOutputs().single { it.disposition == FixedFormationDisposition.CONTINUE_DUTY }
                            assertThrows(IllegalStateException::class.java) { ledger.validatePendingFormationOutput(pending.owner,
                                restored.allTrains.associate { it.id to it.cars.map { car -> car.id } },
                                restored.allTrains.associate { it.id to it.isReversed }) }
                        }
                    } else {
                        val held = ledger.recoveredFormationHolds().single()
                        assertEquals(if (published) RecoveredFormationComposition.EXPECTED_OUTPUTS else RecoveredFormationComposition.UNCHANGED_SOURCES, held.composition)
                        assertEquals(if (stage >= 2) FixedFormationPhase.OUTPUT_OWNERS_COMMITTED else FixedFormationPhase.SOURCES_HELD,
                            held.change.phase)
                    }
                    assertEquals(if (published) 2 else 1, restored.allTrains.size)
                    assertEquals(store.allFixedUnitAssignments(), restored.simpleAtoWorldData.depots.allFixedUnitAssignments())
                    assertTrue(restored.simpleAtoWorldData.dispatcher.recoveryRequiredRuns().isEmpty())
                    restored.onOwnerForTest {
                        restored.allTrains.forEach { body ->
                            val core = body as CoreTrain
                            if (stage == 3 && core.id == train.id) {
                                assertFalse(core.movementHeld, "finished continuing peer must not inherit the separate HOLD output fence")
                                return@forEach
                            }
                            assertTrue(core.movementHeld)
                            core.setMovementHeld(false)
                            assertTrue(core.movementHeld, "configuration release must not release unresolved formation")
                            val position = core.publishedFrame.head
                            core.moveTrainForward(1.0)
                            assertEquals(position, core.publishedFrame.head)
                        }
                    }
                    TestSimpleATOWorldDataRegistry.unregister(restored)
                }
            }
            TestSimpleATOWorldDataRegistry.unregister(source)
        }
        }
    }
}
