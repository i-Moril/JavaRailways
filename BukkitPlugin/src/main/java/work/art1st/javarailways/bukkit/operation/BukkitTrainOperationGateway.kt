package work.art1st.javarailways.bukkit.operation

import org.bukkit.Location
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicket
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketSnapshot
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.basic.api.operation.DoorSide
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.extension.bukkitWorldData
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.BukkitThreadOwnership
import work.art1st.javarailways.core.util.ThreadGuard
import java.util.concurrent.atomic.AtomicReference

fun interface BukkitDoorOperationExecutor {
    fun execute(
        train: Train,
        side: DoorSide,
        opening: Boolean,
        onComplete: () -> Unit
    ): TrainModelOperationResult
}

fun interface BukkitModelActionExecutor {
    fun execute(
        train: Train,
        actionName: String,
        onComplete: () -> Unit
    ): TrainModelOperationResult
}

fun interface BukkitTrainOperationDispatcher {
    /** Submit without waiting for a Folia region thread; completion may run on that region later. */
    fun dispatch(
        train: Train,
        operation: () -> TrainModelOperationResult,
        onResult: (Result<TrainModelOperationResult>) -> Unit
    )
}

class RegionTrainOperationDispatcher(
    private val plugin: BukkitPlugin
) : BukkitTrainOperationDispatcher {
    override fun dispatch(
        train: Train,
        operation: () -> TrainModelOperationResult,
        onResult: (Result<TrainModelOperationResult>) -> Unit
    ) {
        val position = train.headLocation.position
        val location = Location(
            train.world.bukkitWorldData.bukkitWorld,
            position.x(),
            position.y(),
            position.z()
        )
        val execute = {
            onResult(runCatching {
                BukkitThreadGuardBridge.runInSimulationContext(operation)
            })
        }
        if (BukkitThreadOwnership.isOwnedByCurrentRegion(location)) {
            execute()
        } else {
            plugin.platformDispatcher.runAt(location, execute)
        }
    }
}

/** Simulation adapter from Basic semantic operations to region-owned train model actions. */
class BukkitTrainOperationGateway(
    private val world: World,
    private val operationDispatcher: BukkitTrainOperationDispatcher = BukkitTrainOperationDispatcher {
        _, action, onResult -> onResult(runCatching(action))
    },
    private val doorExecutor: BukkitDoorOperationExecutor = BukkitDoorOperationExecutor {
        train, side, opening, onComplete ->
        val renderer = train.rendererOrNull
            ?: return@BukkitDoorOperationExecutor TrainModelOperationResult.Rejected(
                "train renderer is not registered: ${train.id}"
            )
        renderer.executeDoorOperation(side, opening, onComplete)
    },
    private val modelActionExecutor: BukkitModelActionExecutor = BukkitModelActionExecutor {
        train, actionName, onComplete ->
        val renderer = train.rendererOrNull
            ?: return@BukkitModelActionExecutor TrainModelOperationResult.Rejected(
                "train renderer is not registered: ${train.id}"
            )
        renderer.executeModelAction(actionName, onComplete)
    }
) : TrainOperationGateway {
    constructor(world: World, doorExecutor: BukkitDoorOperationExecutor) : this(
        world = world,
        operationDispatcher = BukkitTrainOperationDispatcher {
            _, action, onResult -> onResult(runCatching(action))
        },
        doorExecutor = doorExecutor
    )

    override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult {
        ThreadGuard.ensureMainThread("BukkitTrainOperationGateway.execute")
        val train = world.getTrain(request.trainId)
            ?: return TrainOperationExecutionResult.Rejected("train not found: ${request.trainId}")
        val operation = request.operation
        if (operation !is TrainOperation.OpenDoors &&
            operation !is TrainOperation.CloseDoors &&
            operation !is TrainOperation.ExecuteModelAction
        ) {
            return TrainOperationExecutionResult.Rejected(
                "Bukkit adapter does not support ${operation::class.simpleName}"
            )
        }
        val ticket = BukkitOperationTicket(request)
        try {
            operationDispatcher.dispatch(
                train,
                operation = {
                    if (!ticket.isPending()) {
                        TrainModelOperationResult.Rejected("operation cancelled before region dispatch")
                    } else {
                        when (operation) {
                            is TrainOperation.OpenDoors -> doorExecutor.execute(
                                train,
                                operation.side,
                                true,
                                ticket::complete
                            )
                            is TrainOperation.CloseDoors -> doorExecutor.execute(
                                train,
                                operation.side,
                                false,
                                ticket::complete
                            )
                            is TrainOperation.ExecuteModelAction -> modelActionExecutor.execute(
                                train,
                                operation.actionName,
                                ticket::complete
                            )
                            else -> error("unsupported operation passed validation")
                        }
                    }
                },
                onResult = { result ->
                    result.fold(
                        onSuccess = { operationResult ->
                            if (operationResult is TrainModelOperationResult.Rejected) {
                                ticket.fail(operationResult.reason)
                            }
                        },
                        onFailure = { failure ->
                            ticket.fail(
                                "train operation failed: " +
                                    (failure.message ?: failure::class.java.simpleName)
                            )
                        }
                    )
                }
            )
        } catch (failure: Exception) {
            return TrainOperationExecutionResult.Rejected(
                "train operation dispatch failed: " +
                    (failure.message ?: failure::class.java.simpleName)
            )
        }
        return TrainOperationExecutionResult.Accepted(ticket)
    }

    private data class TicketStatus(
        val state: TrainOperationTicketState,
        val message: String? = null
    )

    private class BukkitOperationTicket(
        override val request: TrainOperationRequest
    ) : TrainOperationTicket {
        private val status = AtomicReference(TicketStatus(TrainOperationTicketState.PENDING))

        fun isPending(): Boolean = status.get().state == TrainOperationTicketState.PENDING

        fun complete() {
            transition(TicketStatus(TrainOperationTicketState.SUCCEEDED))
        }

        fun fail(message: String) {
            require(message.isNotBlank())
            transition(TicketStatus(TrainOperationTicketState.FAILED, message))
        }

        override fun snapshot(): TrainOperationTicketSnapshot {
            val current = status.get()
            return TrainOperationTicketSnapshot(
                ticketId = "bukkit:${request.requestId}",
                requestId = request.requestId,
                state = current.state,
                message = current.message
            )
        }

        override fun cancel() {
            transition(TicketStatus(TrainOperationTicketState.CANCELLED))
        }

        private fun transition(next: TicketStatus) {
            status.updateAndGet { current ->
                if (current.state == TrainOperationTicketState.PENDING) next else current
            }
        }
    }
}
