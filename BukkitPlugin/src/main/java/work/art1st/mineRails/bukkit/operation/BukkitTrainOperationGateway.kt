package work.art1st.mineRails.bukkit.operation

import work.art1st.mineRails.basic.api.operation.TrainOperation
import work.art1st.mineRails.basic.api.operation.TrainOperationExecutionResult
import work.art1st.mineRails.basic.api.operation.TrainOperationGateway
import work.art1st.mineRails.basic.api.operation.TrainOperationRequest
import work.art1st.mineRails.basic.api.operation.TrainOperationTicket
import work.art1st.mineRails.basic.api.operation.TrainOperationTicketSnapshot
import work.art1st.mineRails.basic.api.operation.TrainOperationTicketState
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.bukkit.extension.train.TrainModelOperationResult
import work.art1st.mineRails.bukkit.extension.train.rendererOrNull
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.train.Train
import work.art1st.mineRails.core.util.ThreadGuard

fun interface BukkitDoorOperationExecutor {
    fun execute(
        train: Train,
        side: DoorSide,
        opening: Boolean,
        onComplete: () -> Unit
    ): TrainModelOperationResult
}

/** Main-thread adapter from Basic semantic operations to train behavior.js/model actions. */
class BukkitTrainOperationGateway(
    private val world: World,
    private val doorExecutor: BukkitDoorOperationExecutor = BukkitDoorOperationExecutor {
        train, side, opening, onComplete ->
        val renderer = train.rendererOrNull
            ?: return@BukkitDoorOperationExecutor TrainModelOperationResult.Rejected(
                "train renderer is not registered: ${train.id}"
            )
        renderer.executeDoorOperation(side, opening, onComplete)
    }
) : TrainOperationGateway {
    override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult {
        ThreadGuard.ensureMainThread("BukkitTrainOperationGateway.execute")
        val train = world.getTrain(request.trainId)
            ?: return TrainOperationExecutionResult.Rejected("train not found: ${request.trainId}")
        val operation = request.operation
        if (operation !is TrainOperation.OpenDoors && operation !is TrainOperation.CloseDoors) {
            return TrainOperationExecutionResult.Rejected(
                "Bukkit adapter does not support ${operation::class.simpleName}"
            )
        }
        val ticket = BukkitOperationTicket(request)
        val side = when (operation) {
            is TrainOperation.OpenDoors -> operation.side
            is TrainOperation.CloseDoors -> operation.side
        }
        val result = try {
            doorExecutor.execute(
                train,
                side,
                operation is TrainOperation.OpenDoors,
                ticket::complete
            )
        } catch (failure: Exception) {
            return TrainOperationExecutionResult.Rejected(
                "door operation failed: ${failure.message ?: failure::class.java.simpleName}"
            )
        }
        return when (result) {
            is TrainModelOperationResult.Accepted ->
                TrainOperationExecutionResult.Accepted(ticket)
            is TrainModelOperationResult.Rejected ->
                TrainOperationExecutionResult.Rejected(result.reason)
        }
    }

    private class BukkitOperationTicket(
        override val request: TrainOperationRequest
    ) : TrainOperationTicket {
        private var state = TrainOperationTicketState.PENDING

        fun complete() {
            if (state == TrainOperationTicketState.PENDING) {
                state = TrainOperationTicketState.SUCCEEDED
            }
        }

        override fun snapshot(): TrainOperationTicketSnapshot = TrainOperationTicketSnapshot(
            ticketId = "bukkit:${request.requestId}",
            requestId = request.requestId,
            state = state
        )

        override fun cancel() {
            if (state == TrainOperationTicketState.PENDING) {
                state = TrainOperationTicketState.CANCELLED
            }
        }
    }
}
