package work.art1st.javarailways.bukkit.operation

import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.bukkit.behavior.CarOperationBatch
import work.art1st.javarailways.bukkit.behavior.behaviorRelativeTail
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult
import work.art1st.javarailways.core.api.event.TrainSwitchDirectionEvent
import work.art1st.javarailways.core.api.event.processor.EventListener
import work.art1st.javarailways.core.api.event.processor.HandlesEvent
import work.art1st.javarailways.core.api.event.processor.MainRailEventBus
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.threading.ThreadGuard
import work.art1st.javarailways.core.physics.CoreWorld
import java.util.concurrent.CompletableFuture

/** World-owned semantic adapter. Executes the same fixed-car batch and actual per-car JS as LIVE. */
internal class SimulationTrainOperationGateway(private val world: World) : TrainOperationGateway, AutoCloseable {
    private var closed = false
    private val operations = linkedMapOf<CompletableFuture<Unit>, ModelOperationTicket>()
    private val registration = MainRailEventBus.register(object : EventListener {
        @HandlesEvent
        fun onReverse(event: TrainSwitchDirectionEvent) {
            if (event.world !== world || closed) return
            val result = CompletableFuture<Unit>()
            broadcast(event.train, result) { car, tail, done -> car.call("onReverse", listOf(event.train.id), tail, done) }
            result.whenComplete { _, failure ->
                if (failure != null) LOGGER.log(java.util.logging.Level.WARNING,
                    "[simulation ${(world as CoreWorld).runtimeId} train ${event.train.id}] onReverse failed", failure)
            }
        }
    })

    override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult {
        ThreadGuard.ensureMainThread("SimulationTrainOperationGateway.execute")
        if (closed) return TrainOperationExecutionResult.Rejected("simulation behavior owner is closed")
        val operation = request.operation
        if (operation !is TrainOperation.OpenDoors && operation !is TrainOperation.CloseDoors && operation !is TrainOperation.ExecuteModelAction)
            return TrainOperationExecutionResult.Rejected("Bukkit adapter does not support ${operation::class.simpleName}")
        val train = world.getTrain(request.trainId) ?: return TrainOperationExecutionResult.Rejected("train not found: ${request.trainId}")
        val ticket = ModelOperationTicket(request, "simulation:${(world as CoreWorld).runtimeId}")
        val result = CompletableFuture<Unit>()
        operations[result] = ticket
        result.whenComplete { _, failure ->
            operations.remove(result)
            if (failure == null) ticket.complete()
            else {
                if (!closed && ticket.isPending() && failure !is java.util.concurrent.CancellationException &&
                    failure.cause !is java.util.concurrent.CancellationException) {
                    LOGGER.log(java.util.logging.Level.WARNING,
                        "Simulation operation failed simulation=${world.runtimeId} request=${request.requestId} train=${request.trainId}", failure)
                }
                ticket.fail(failure.cause?.message ?: failure.message ?: failure::class.java.simpleName)
            }
        }
        try {
            broadcast(train, result, ticket::isPending, (operation as? TrainOperation.ExecuteModelAction)?.actionName) { car, tail, done ->
                when (operation) {
                    is TrainOperation.OpenDoors -> car.door(operation.side, true, tail, done)
                    is TrainOperation.CloseDoors -> car.door(operation.side, false, tail, done)
                    is TrainOperation.ExecuteModelAction -> car.action(operation.actionName, tail, done)
                    else -> error("unsupported operation")
                }
            }
        } catch (failure: Exception) { result.completeExceptionally(failure) }
        return TrainOperationExecutionResult.Accepted(ticket)
    }

    private fun broadcast(
        train: Train, result: CompletableFuture<Unit>, isPending: () -> Boolean = { true }, customName: String? = null,
        invoke: (SimulationCarBehaviorExtension, Boolean, (Result<Unit>) -> Unit) -> TrainModelOperationResult
    ) {
        // Same fallback as TrainControlManager without a manual session: reversed trains operate TAIL.
        val captured = train.cars.map { car ->
            val extension = requireNotNull(car.simulationBehaviorOrNull) {
                "simulation train ${train.id} car ${car.id} has no captured behavior"
            }
            extension to train.behaviorRelativeTail(car, train.isReversed)
        }
        val targets = captured.map { (car, tail) ->
            CarOperationBatch.Target(
                metadata = { CompletableFuture.completedFuture(car.metadata(tail)) },
                execute = { done -> CompletableFuture.completedFuture(invoke(car, tail, done)) }
            )
        }
        CarOperationBatch.execute(targets, result, isPending, customName)
    }

    /** Called after the target's physics and generic workers have truly terminated. */
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (value: Throwable) {
                if (failure == null) failure = value else if (failure !== value) failure!!.addSuppressed(value)
            }
        }
        attempt { registration.close() }
        operations.keys.toList().forEach { it.completeExceptionally(IllegalStateException("simulation behavior owner closed")) }
        // Core destroys extensions before retiring a train; transferred cars remain in their new train.
        val currentCars = world.allTrains.flatMap { it.cars }.mapNotNull { it.simulationBehaviorOrNull }
        currentCars.forEach { attempt(it::close) }
        failure?.let { throw it }
    }

    companion object {
        private val LOGGER = java.util.logging.Logger.getLogger(SimulationTrainOperationGateway::class.java.name)
    }
}
