package work.art1st.javarailways.bukkit.operation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.*
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.core.api.physics.WorldExecutionMode
import work.art1st.javarailways.core.api.util.plugin.ExtensionData
import work.art1st.javarailways.core.api.util.plugin.Plugin
import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinator
import work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@OptIn(ExtensionData::class)
class SimulationTrainOperationGatewayTest {
    @Test
    fun `real per-car JS state is independent between cars and same-source target worlds`() {
        val source = definition("""
            let count = 0;
            defineAction('step', function() { count++; return action(count === 1 ? 'first' : 'later'); });
        """, listOf(action("first"), action("later")))
        fixture(listOf(source, source)).use { a -> fixture(listOf(source, source)).use { b ->
            assertSucceeded(a.execute(TrainOperation.ExecuteModelAction("step")))
            assertEquals(listOf("first"), a.animations(0))
            assertEquals(listOf("first"), a.animations(1))
            assertSucceeded(a.execute(TrainOperation.ExecuteModelAction("step")))
            assertEquals(listOf("first", "later"), a.animations(0))
            assertTrue(b.effectSnapshots().all { it.isEmpty() })
            assertSucceeded(b.execute(TrainOperation.ExecuteModelAction("step")))
            assertEquals(listOf("first"), b.animations(0))
            a.gateway.close()
            assertSucceeded(b.execute(TrainOperation.ExecuteModelAction("step")))
            assertEquals(listOf("first", "later"), b.animations(1))
        } }
    }

    @Test
    fun `all accepted delays wait for owner commits and cancelled tickets stay cancelled`() {
        val delayed = TrainActionConfig("delayed", animations = listOf(
            TrainActionAnimationConfig("first", at = 0.1), TrainActionAnimationConfig("last", at = 0.3)
        ), sounds = listOf(TrainActionSoundConfig("rail:bell", at = 0.2)))
        fixture(listOf(definition(null, listOf(delayed), mapOf("first" to emptySet(), "last" to emptySet())))).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("delayed"))
            f.advance(0)
            f.advance(100, commit = false)
            assertTrue(f.effects(0).isEmpty())
            assertEquals(TrainOperationTicketState.PENDING, ticket.snapshot().state)
            f.commit()
            assertEquals(listOf("first"), f.animations(0))
            f.advance(100)
            assertTrue(f.effects(0).any { it is SimulationBehaviorEffect.SoundStarted })
            assertEquals(TrainOperationTicketState.PENDING, ticket.snapshot().state)
            ticket.cancel()
            f.advance(101)
            assertEquals(listOf("first", "last"), f.animations(0))
            assertEquals(TrainOperationTicketState.CANCELLED, ticket.snapshot().state)
        }
    }

    @Test
    fun `target close and real car destruction terminate pending work without affecting another target`() {
        val delayed = TrainActionConfig("later", animations = listOf(TrainActionAnimationConfig("later", at = 1.0)))
        val source = definition(null, listOf(delayed))
        fixture(listOf(source)).use { a -> fixture(listOf(source)).use { b ->
            val aTicket = a.execute(TrainOperation.ExecuteModelAction("later"))
            val bTicket = b.execute(TrainOperation.ExecuteModelAction("later"))
            a.gateway.close()
            assertEquals(TrainOperationTicketState.FAILED, aTicket.snapshot().state)
            assertTrue(a.executeResult(TrainOperation.CloseDoors(DoorSide.BOTH)) is TrainOperationExecutionResult.Rejected)
            b.advance(0)
            b.advance(1001)
            assertSucceeded(bTicket)
            assertTrue(a.effects(0).isEmpty())
        } }
        fixture(listOf(source)).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("later"))
            val destroyed = f.world.onOwnerForTest { f.train.destroy() }
            f.world.onOwnerForTest { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            destroyed.toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            f.advance(0)
            f.advance(2000)
            assertTrue(f.effects(0).isEmpty())
        }
    }

    @Test
    fun `custom actions select actual supporters and retain real rejection semantics`() {
        val supporter = definition("defineAction('bell', function() { return action('sound'); });",
            listOf(TrainActionConfig("sound", sounds = listOf(TrainActionSoundConfig("rail:bell")))))
        val other = definition(null, listOf(action("other")))
        fixture(listOf(supporter, other)).use { f ->
            assertSucceeded(f.execute(TrainOperation.ExecuteModelAction("bell")))
            assertTrue(f.effects(0).single() is SimulationBehaviorEffect.SoundStarted)
            assertTrue(f.effects(1).isEmpty())
            assertEquals(TrainOperationTicketState.FAILED, f.execute(TrainOperation.ExecuteModelAction("absent")).snapshot().state)
            assertEquals(TrainOperationTicketState.FAILED, f.execute(TrainOperation.OpenDoors(DoorSide.LEFT)).snapshot().state)
            assertTrue(f.executeResult(TrainOperation.SetCabLights(true)) is TrainOperationExecutionResult.Rejected)
            assertTrue(f.executeResult(TrainOperation.Announce("hello")) is TrainOperationExecutionResult.Rejected)
        }
        val rejected = definition("defineAction('bell', function() { return false; });", emptyList())
        fixture(listOf(supporter, rejected)).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("bell"))
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            assertTrue(f.effects(0).single() is SimulationBehaviorEffect.SoundStarted)
        }
    }

    @Test
    fun `unknown animation scenes and script initialization effects fail instead of succeeding`() {
        val wrong = definition(null, listOf(TrainActionConfig("bad", animations = listOf(TrainActionAnimationConfig("door", "missing", at = 0.1)))),
            mapOf("door" to setOf("open", "closed")))
        fixture(listOf(wrong)).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("bad"))
            assertEquals(TrainOperationTicketState.PENDING, ticket.snapshot().state)
            f.advance(0)
            f.advance(101)
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            assertTrue(f.effects(0).isEmpty())
        }
        val badScript = definition("sound('forbidden'); function door() {}", emptyList())
        fixture(listOf(badScript)).use { f ->
            assertEquals(TrainOperationTicketState.FAILED, f.execute(TrainOperation.OpenDoors(DoorSide.LEFT)).snapshot().state)
            assertTrue(f.effects(0).isEmpty())
        }
    }

    @Test
    fun `formal doors capture installed directions and real reverse invokes only target world behavior`() {
        val script = Files.readString(Path.of("../models/s100/behavior.js"))
        val actions = listOf("door-left-open", "door-right-open", "door-left-close", "door-right-close",
            "front-light-head", "front-light-tail").map(::action)
        val source = definition(script, actions, actions.associate { it.name to emptySet<String>() } +
            ("front_end_light" to setOf("head", "tail", "off")))
        fixture(listOf(source, source)).use { a -> fixture(listOf(source, source)).use { b ->
            a.world.onOwnerForTest { (a.train.cars[1] as CoreCar).reverse() }
            assertSucceeded(a.execute(TrainOperation.OpenDoors(DoorSide.LEFT)))
            assertEquals(listOf("door-left-open"), a.animations(0))
            assertEquals(listOf("door-right-open"), a.animations(1))
            a.world.onOwnerForTest { a.train.reverse() }
            assertEquals(listOf("door-left-open", "front-light-tail"), a.animations(0))
            assertEquals(listOf("door-right-open", "front-light-head"), a.animations(1))
            assertTrue(b.effectSnapshots().all { it.isEmpty() })
            assertSucceeded(a.execute(TrainOperation.CloseDoors(DoorSide.LEFT)))
            assertEquals("door-right-close", a.animations(0).last())
            assertEquals("door-left-close", a.animations(1).last())
            a.gateway.close()
            val effects = a.effectSnapshots()
            a.world.onOwnerForTest { a.train.reverse() }
            assertEquals(effects, a.effectSnapshots())
        } }
    }

    @Test
    fun `accepted delayed effects retain exact cars and physical side through split and reversal`() {
        val actions = listOf("door-left-open", "door-right-open").map { name ->
            TrainActionConfig(name, animations = listOf(TrainActionAnimationConfig(name, at = 0.2)))
        }
        val script = """
            let openings = 0;
            function door(side, scene) {
                openings++;
                const physical = operatingEnd() === 'tail' ? (side === 'left' ? 'right' : 'left') : side;
                action('door-' + physical + '-open');
                return action(openings === 1 ? 'first' : 'later');
            }
        """.trimIndent()
        val source = definition(script, actions + listOf(action("first"), action("later")))
        fixture(listOf(source, source)).use { f ->
            val originalCars = f.train.cars.toList()
            val ticket = f.execute(TrainOperation.OpenDoors(DoorSide.LEFT))
            val split = f.world.onOwnerForTest { f.train.splitBefore(originalCars[1].id, "split") }
            f.world.onOwnerForTest { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            val separated = split.get(5, TimeUnit.SECONDS) as CoreTrain
            f.world.onOwnerForTest { separated.reverse() }
            assertSame(originalCars[1], separated.cars.single())
            f.advance(0)
            f.advance(201)
            assertSucceeded(ticket)
            assertEquals(listOf("first", "door-left-open"), f.animations(0))
            assertEquals(listOf("first", "door-left-open"), f.animations(1))
            val next = f.world.onOwnerForTest {
                (f.gateway.execute(request(separated.id, TrainOperation.OpenDoors(DoorSide.LEFT))) as TrainOperationExecutionResult.Accepted).ticket
            }
            f.advance(0)
            f.advance(201)
            assertSucceeded(next)
            assertEquals(listOf("first", "door-left-open", "later", "door-right-open"), f.animations(1))
            assertEquals(listOf("first", "door-left-open"), f.animations(0))
        }
    }

    @Test
    fun `guest load declaration and call errors derail only the current target train`() {
        val broken = listOf(
            "function door( {",
            "function __javaRailwaysListActions() { throw new Error('bad declarations'); }",
            "function door() { throw new Error('bad door'); }"
        )
        val healthy = definition("function door() { return action('open'); }", listOf(action("open")))
        broken.forEach { script ->
            fixture(listOf(definition(script, emptyList()))).use { failed ->
                fixture(listOf(healthy)).use { other ->
                    val operation = if (script.contains("bad declarations")) TrainOperation.ExecuteModelAction("open")
                        else TrainOperation.OpenDoors(DoorSide.LEFT)
                    assertEquals(TrainOperationTicketState.FAILED, failed.execute(operation).snapshot().state)
                    failed.world.onOwnerForTest { failed.world.physicsCoordinator.drainBetweenFramesOperations() }
                    assertTrue(failed.train.isDerailed, script)
                    assertTrue(failed.train.derailReason!!.contains(failed.train.cars.single().id))
                    assertEquals(PhysicsCoordinatorStatus.RUNNING, failed.world.physicsCoordinator.status)
                    assertFalse(other.train.isDerailed)
                    assertSucceeded(other.execute(TrainOperation.OpenDoors(DoorSide.LEFT)))
                    other.world.physicsCoordinator.start()
                    val frame = IsolatedSimulationThreadMarker.run {
                        other.world.physicsCoordinator.stepSimulation(50.0).await()
                    }
                    assertEquals(PhysicsCoordinatorStatus.RUNNING, other.world.physicsCoordinator.status)
                    assertEquals(1L, frame.epoch)
                }
            }
        }
    }

    @Test
    fun `guest failure after split stops the exact current consist and leaves original owner running`() {
        val source = definition("defineAction('fail', function() { throw new Error('split guest'); });", emptyList())
        fixture(listOf(source, source)).use { f ->
            val movedCar = f.train.cars[1]
            val pending = f.train.splitBefore(movedCar.id, "split-failure")
            f.world.onOwnerForTest { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            val separated = pending.get(5, TimeUnit.SECONDS) as CoreTrain
            val ticket = f.world.onOwnerForTest {
                (f.gateway.execute(request(separated.id, TrainOperation.ExecuteModelAction("fail")))
                    as TrainOperationExecutionResult.Accepted).ticket
            }
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            f.world.onOwnerForTest { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            assertSame(separated, movedCar.train)
            assertTrue(separated.isDerailed)
            assertTrue(separated.derailReason!!.contains(movedCar.id))
            assertFalse(f.train.isDerailed)
            assertEquals(PhysicsCoordinatorStatus.RUNNING, f.world.physicsCoordinator.status)
        }
    }

    @Test
    fun `behavior destruction returns one completed result and closed metadata cannot halt other targets`() {
        val delayed = TrainActionConfig("later", animations = listOf(TrainActionAnimationConfig("later", at = 1.0)))
        fixture(listOf(definition(null, listOf(delayed)))).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("later"))
            val car = f.extensions.single()
            val destroyed = car.destroy()
            assertSame(destroyed, car.destroy())
            destroyed.toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            assertThrows(IllegalStateException::class.java) { car.metadata(false) }
            assertEquals(PhysicsCoordinatorStatus.RUNNING, f.world.physicsCoordinator.status)
            f.advance(0)
            f.advance(2000)
            assertTrue(f.effects(0).isEmpty())
        }
    }

    @Test
    fun `production outlet executes effects and rejects invalid scenes without collecting history`() {
        val source = definition(null, listOf(
            TrainActionConfig("valid", animations = listOf(TrainActionAnimationConfig("door", "open")),
                sounds = listOf(TrainActionSoundConfig("rail:bell"))),
            TrainActionConfig("invalid", animations = listOf(TrainActionAnimationConfig("door", "missing")))
        ), mapOf("door" to setOf("open")))
        Fixture(listOf(source), observeEffects = false).use { f ->
            repeat(100) { assertSucceeded(f.execute(TrainOperation.ExecuteModelAction("valid"))) }
            assertEquals(TrainOperationTicketState.FAILED,
                f.execute(TrainOperation.ExecuteModelAction("invalid")).snapshot().state)
            assertTrue(f.effects(0).isEmpty())
        }
    }

    @Test
    fun `actual train retirement closes its exact car while gateway shutdown closes the survivor`() {
        val source = definition(null, listOf(TrainActionConfig("later",
            animations = listOf(TrainActionAnimationConfig("later", at = 1.0)))))
        fixture(listOf(source, source)).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("later"))
            val split = f.train.splitBefore(f.train.cars[1].id, "retired")
            f.world.onOwnerForTest { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            val retired = split.get(5, TimeUnit.SECONDS) as CoreTrain
            var retiredCompletions = 0
            var survivorCompletions = 0
            f.world.onOwnerForTest {
                f.extensions[1].action("later", false) { result ->
                    assertTrue(result.isFailure)
                    retiredCompletions++
                }
                f.extensions[0].action("later", false) { result ->
                    assertTrue(result.isFailure)
                    survivorCompletions++
                }
            }
            val destroyed = f.world.onOwnerForTest { retired.destroy() }
            destroyed.toCompletableFuture().get(5, TimeUnit.SECONDS)
            assertNull(f.world.getTrain(retired.id))
            assertEquals(1, retiredCompletions)
            assertEquals(0, survivorCompletions)
            assertThrows(IllegalStateException::class.java) { f.extensions[1].metadata(false) }
            assertTrue(f.extensions[0].metadata(false).any { it.name == "later" })
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            assertDoesNotThrow { f.gateway.close() }
            assertThrows(IllegalStateException::class.java) { f.extensions[0].metadata(false) }
            assertEquals(1, retiredCompletions)
            assertEquals(1, survivorCompletions)
        }
    }

    @Test
    @work.art1st.javarailways.core.testing.IsolateGlobalHalt
    fun `gateway does not retain a retired car whose completed destruction failed`() {
        val source = definition(null, listOf(TrainActionConfig("later",
            animations = listOf(TrainActionAnimationConfig("later", at = 1.0)))))
        fixture(listOf(source, source)).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("later"))
            val split = f.train.splitBefore(f.train.cars[1].id, "failed-retirement")
            f.world.onOwnerForTest { f.world.physicsCoordinator.drainBetweenFramesOperations() }
            val retired = split.get(5, TimeUnit.SECONDS) as CoreTrain
            var completions = 0
            val cleanupFailure = IllegalStateException("retired completion failed")
            f.world.onOwnerForTest {
                f.extensions[1].action("later", false) { result ->
                    assertTrue(result.isFailure)
                    completions++
                    throw cleanupFailure
                }
            }
            val destroyed = f.world.onOwnerForTest { retired.destroy() }
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                destroyed.toCompletableFuture().get(5, TimeUnit.SECONDS)
            }
            assertTrue(work.art1st.javarailways.core.physics.tick.RailwaySimulationHalt.isHalted)
            assertNull(f.world.getTrain(retired.id))
            assertEquals(1, completions)
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            // Re-closing a historical owner would rethrow its retained failed Destruction stage.
            assertDoesNotThrow { f.gateway.close() }
            assertThrows(IllegalStateException::class.java) { f.extensions[0].metadata(false) }
            assertEquals(1, completions)
        }
    }

    @Test
    fun `one current car cleanup failure cannot prevent remaining current cars from closing`() {
        val source = definition(null, listOf(TrainActionConfig("later",
            animations = listOf(TrainActionAnimationConfig("later", at = 1.0)))))
        fixture(listOf(source, source)).use { f ->
            val ticket = f.execute(TrainOperation.ExecuteModelAction("later"))
            val completions = IntArray(2)
            f.world.onOwnerForTest {
                f.extensions.forEachIndexed { index, extension ->
                    extension.action("later", false) { result ->
                        assertTrue(result.isFailure)
                        completions[index]++
                        if (index == 0) throw IllegalStateException("current completion failed")
                    }
                }
            }
            assertThrows(java.util.concurrent.CompletionException::class.java) { f.gateway.close() }
            assertEquals(listOf(1, 1), completions.toList())
            assertEquals(TrainOperationTicketState.FAILED, ticket.snapshot().state)
            f.extensions.forEach { extension ->
                assertThrows(IllegalStateException::class.java) { extension.metadata(false) }
            }
        }
    }

    private fun assertSucceeded(ticket: TrainOperationTicket) = assertEquals(TrainOperationTicketState.SUCCEEDED, ticket.snapshot().state)

    private fun fixture(definitions: List<CapturedCarBehavior>): Fixture = Fixture(definitions)

    private class Fixture(definitions: List<CapturedCarBehavior>, observeEffects: Boolean = true) : AutoCloseable {
        val world: CoreWorld
        val train: CoreTrain
        val extensions: List<SimulationCarBehaviorExtension>
        val gateway: SimulationTrainOperationGateway
        private val effectHistory = SimulationBehaviorEffectCollector()
        private val capturedCars: List<CoreCar>
        private var clock = System.currentTimeMillis()
        init {
            PluginRegistry.register(MARKER)
            world = createTestWorld(PhysicsCoordinator(1, automaticFallback = false), executionMode = WorldExecutionMode.SIMULATION)
            val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(300.0, segmentCount = 1))
            train = createTrainWithConfig("T1", rail, world, definitions.map { 10.0 })
            capturedCars = train.cars.map { it as CoreCar }
            extensions = definitions.mapIndexed { index, definition ->
                SimulationCarBehaviorExtension(definition, CarControlConfig(),
                    if (observeEffects) effectHistory::record else null).also { extension ->
                    extension.initialize(train.cars[index], emptyMap())
                    train.cars[index].registerExtensionData(MARKER, extension)
                }
            }
            gateway = SimulationTrainOperationGateway(world)
        }
        fun executeResult(operation: TrainOperation): TrainOperationExecutionResult = world.onOwnerForTest {
            gateway.execute(request(train.id, operation))
        }
        fun execute(operation: TrainOperation): TrainOperationTicket =
            (executeResult(operation) as TrainOperationExecutionResult.Accepted).ticket
        fun effects(index: Int): List<SimulationBehaviorEffect> = effectHistory.snapshot(capturedCars[index])
        fun effectSnapshots(): List<List<SimulationBehaviorEffect>> = capturedCars.map(effectHistory::snapshot)
        fun animations(index: Int): List<String> = effects(index).filterIsInstance<SimulationBehaviorEffect.AnimationStarted>().map { it.name }
        fun advance(millis: Long, commit: Boolean = true) = world.onOwnerForTest {
            clock += millis
            world.tickManager.preTick(clock)
            world.tickManager.postTick()
            if (commit) commit()
        }
        fun commit() = world.tickManager.drainPendingCommits().forEach { it.commit() }
        override fun close() {
            world.physicsCoordinator.close()
            (world.tickManager as AutoCloseable).close()
            gateway.close()
        }
    }

    companion object {
        private val MARKER = object : Plugin { override var identifier = -1 }
        private fun request(train: String, operation: TrainOperation) = TrainOperationRequest("r", "service", "call", train, 1L, operation)
        private fun action(name: String) = TrainActionConfig(name, animations = listOf(TrainActionAnimationConfig(name)))
        private fun definition(script: String?, actions: List<TrainActionConfig>, animations: Map<String, Set<String>> = actions.flatMap { it.animations }.associate { it.name to emptySet<String>() }) =
            CapturedCarBehavior(script?.let { TrainBehaviorScript("behavior.js", it) }, actions.associateBy { it.name }, animations)
    }
}
