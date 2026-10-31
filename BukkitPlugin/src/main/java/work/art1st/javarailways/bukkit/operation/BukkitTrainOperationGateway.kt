package work.art1st.javarailways.bukkit.operation

import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.train.CarExtension
import work.art1st.javarailways.bukkit.extension.train.renderer
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.threading.ThreadGuard
import java.util.concurrent.CompletableFuture

/** Re-resolves the exact car's location on arrival; no simulation lock or live train traversal. */
class RegionTrainOperationDispatcher(private val plugin: BukkitPlugin) {
    internal fun <T> dispatch(car: CarExtension, operation: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        fun submit() {
            if (result.isDone) return
            try {
                val location = car.behaviorLocation()
                if (BukkitThreadOwnership.isOwnedByCurrentRegion(location)) result.complete(operation())
                else plugin.platformDispatcher.runAt(location) { submit() }
            } catch (failure: Exception) { result.completeExceptionally(failure) }
        }
        submit()
        return result
    }
}

/** Adapts a semantic operation to a fixed-car future, never merely queue acceptance. */
class BukkitTrainOperationGateway(
    private val world: World,
    private val executeOperation: (Train, TrainOperation, () -> Boolean) -> CompletableFuture<Unit> = { train, operation, pending ->
        when (operation) {
            is TrainOperation.OpenDoors -> train.renderer.executeDoorOperation(operation.side, true, pending)
            is TrainOperation.CloseDoors -> train.renderer.executeDoorOperation(operation.side, false, pending)
            is TrainOperation.ExecuteModelAction -> train.renderer.executeModelAction(operation.actionName, pending)
            else -> error("unsupported operation")
        }
    }
) : TrainOperationGateway {
    override fun estimateDoorCloseDurationTicks(trainId: String, side: work.art1st.javarailways.basic.api.operation.DoorSide): CompletableFuture<Long> {
        ThreadGuard.ensureMainThread("BukkitTrainOperationGateway.estimateDoorCloseDurationTicks")
        if (side == work.art1st.javarailways.basic.api.operation.DoorSide.NONE) return CompletableFuture.completedFuture(0L)
        val train = world.getTrain(trainId) ?: return CompletableFuture.failedFuture(IllegalArgumentException("train not found: $trainId"))
        return train.renderer.estimateDoorCloseDurationTicks(side)
    }

    override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult {
        ThreadGuard.ensureMainThread("BukkitTrainOperationGateway.execute")
        val operation = request.operation
        if (operation !is TrainOperation.OpenDoors && operation !is TrainOperation.CloseDoors && operation !is TrainOperation.ExecuteModelAction)
            return TrainOperationExecutionResult.Rejected("Bukkit adapter does not support ${operation::class.simpleName}")
        val train = world.getTrain(request.trainId) ?: return TrainOperationExecutionResult.Rejected("train not found: ${request.trainId}")
        val ticket = ModelOperationTicket(request, "bukkit")
        try {
            executeOperation(train, operation, ticket::isPending).whenComplete { _, failure ->
                if (failure == null) ticket.complete()
                else {
                    reportFailure(request, ticket, failure)
                    ticket.fail(failure.cause?.message ?: failure.message ?: failure::class.java.simpleName)
                }
            }
        } catch (failure: Exception) {
            reportFailure(request, ticket, failure)
            ticket.fail(failure.message ?: failure::class.java.simpleName)
        }
        return TrainOperationExecutionResult.Accepted(ticket)
    }

    private fun reportFailure(request: TrainOperationRequest, ticket: ModelOperationTicket, failure: Throwable) {
        if (!ticket.isPending() || failure is java.util.concurrent.CancellationException ||
            failure.cause is java.util.concurrent.CancellationException) return
        java.util.logging.Logger.getLogger(BukkitTrainOperationGateway::class.java.name).log(
            java.util.logging.Level.WARNING,
            "Train operation failed request=${request.requestId} train=${request.trainId} operation=${request.operation}", failure)
    }
}
