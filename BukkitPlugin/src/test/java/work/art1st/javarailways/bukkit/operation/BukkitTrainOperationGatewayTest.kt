package work.art1st.javarailways.bukkit.operation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.*
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.*
import java.util.concurrent.CompletableFuture

class BukkitTrainOperationGatewayTest {
    @Test
    fun `ticket waits for model completion and cancellation is terminal`() = withTrain { world, train ->
        val completion = CompletableFuture<Unit>()
        var observed: TrainOperation? = null
        var pending: (() -> Boolean)? = null
        val gateway = BukkitTrainOperationGateway(world) { target, operation, isPending ->
            assertSame(train, target)
            observed = operation
            pending = isPending
            completion
        }
        val operation = TrainOperation.CloseDoors(DoorSide.BOTH)
        val result = gateway.execute(request(train.id, operation)) as TrainOperationExecutionResult.Accepted
        assertEquals(operation, observed)
        assertEquals(TrainOperationTicketState.PENDING, result.ticket.snapshot().state)
        assertTrue(pending!!())
        result.ticket.cancel()
        assertFalse(pending())
        completion.complete(Unit)
        assertEquals(TrainOperationTicketState.CANCELLED, result.ticket.snapshot().state)
    }

    @Test
    fun `model future failure cannot be reported as success`() = withTrain { world, train ->
        val completion = CompletableFuture<Unit>()
        val gateway = BukkitTrainOperationGateway(world) { _, _, _ -> completion }
        val result = gateway.execute(request(train.id, TrainOperation.ExecuteModelAction("bell"))) as TrainOperationExecutionResult.Accepted
        completion.completeExceptionally(IllegalStateException("car destroyed"))
        assertEquals(TrainOperationTicketState.FAILED, result.ticket.snapshot().state)
        assertEquals("car destroyed", result.ticket.snapshot().message)
        completion.complete(Unit)
        assertEquals(TrainOperationTicketState.FAILED, result.ticket.snapshot().state)
    }

    @Test
    fun `synchronous completion succeeds but dispatch exception fails`() = withTrain { world, train ->
        val success = BukkitTrainOperationGateway(world) { _, _, _ -> CompletableFuture.completedFuture(Unit) }
        val request = request(train.id, TrainOperation.OpenDoors(DoorSide.LEFT))
        assertEquals(TrainOperationTicketState.SUCCEEDED, (success.execute(request) as TrainOperationExecutionResult.Accepted).ticket.snapshot().state)
        val failed = BukkitTrainOperationGateway(world) { _, _, _ -> error("dispatch failed") }
        assertEquals(TrainOperationTicketState.FAILED, (failed.execute(request) as TrainOperationExecutionResult.Accepted).ticket.snapshot().state)
        assertTrue(success.execute(request(train.id, TrainOperation.SetCabLights(true))) is TrainOperationExecutionResult.Rejected)
    }

    private fun withTrain(test: (work.art1st.javarailways.core.physics.CoreWorld, CoreTrain) -> Unit) {
        ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge { override fun isMainThread() = true }).use {
            val world = createTestWorld()
            (world.tickManager as AutoCloseable).use {
                AutoCloseable { world.releaseRuntimeBinding() }.use {
                    world.physicsCoordinator.use {
                        val rail = createStraightRailFromSegments(world, PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1))
                        test(world, createTrainWithConfig("T1", rail, world, listOf(10.0)))
                    }
                }
            }
        }
    }

    private fun request(id: String, operation: TrainOperation) = TrainOperationRequest("r", "service", "call", id, 1L, operation)
}
