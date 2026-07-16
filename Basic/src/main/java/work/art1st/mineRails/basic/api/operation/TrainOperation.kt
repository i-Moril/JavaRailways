package work.art1st.mineRails.basic.api.operation

import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.core.api.util.annotation.MainThread

/** A platform-independent operation requested by the main-thread timetable coordinator. */
sealed interface TrainOperation {
    data class OpenDoors(val side: DoorSide) : TrainOperation {
        init {
            require(side != DoorSide.NONE) { "OpenDoors requires a physical door side" }
        }
    }

    data class CloseDoors(val side: DoorSide) : TrainOperation {
        init {
            require(side != DoorSide.NONE) { "CloseDoors requires a physical door side" }
        }
    }

    data class SetCabLights(val enabled: Boolean) : TrainOperation

    data class Announce(val messageId: String) : TrainOperation {
        init {
            require(messageId.isNotBlank() && messageId == messageId.trim())
        }
    }
}

/** Immutable identity and ownership context for one semantic train operation. */
data class TrainOperationRequest(
    val requestId: String,
    val serviceRunId: String,
    val stationCallId: String,
    val trainId: String,
    val missionRevision: Long,
    val operation: TrainOperation
) {
    init {
        requireStableText("requestId", requestId)
        requireStableText("serviceRunId", serviceRunId)
        requireStableText("stationCallId", stationCallId)
        requireStableText("trainId", trainId)
        require(missionRevision >= 0L)
    }
}

enum class TrainOperationTicketState {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

/** Immutable status read by the Dispatcher on the platform main thread. */
data class TrainOperationTicketSnapshot(
    val ticketId: String,
    val requestId: String,
    val state: TrainOperationTicketState,
    val message: String? = null
) {
    init {
        requireStableText("ticketId", ticketId)
        requireStableText("requestId", requestId)
        require(message == null || message.isNotBlank())
        require(state == TrainOperationTicketState.FAILED || message == null) {
            "only failed operation tickets may carry a failure message"
        }
    }
}

/**
 * Main-thread handle for an operation accepted by a platform adapter.
 *
 * A successful ticket confirms completion as defined by that adapter, not merely queue insertion.
 * Cancellation is idempotent and must prevent a later completion callback from reporting success.
 */
interface TrainOperationTicket {
    val request: TrainOperationRequest

    @MainThread
    fun snapshot(): TrainOperationTicketSnapshot

    @MainThread
    fun cancel()
}

sealed interface TrainOperationExecutionResult {
    data class Accepted(val ticket: TrainOperationTicket) : TrainOperationExecutionResult
    data class Rejected(val reason: String) : TrainOperationExecutionResult {
        init {
            require(reason.isNotBlank())
        }
    }
}

/** Platform boundary. Implementations and tickets are invoked only on the platform main thread. */
fun interface TrainOperationGateway {
    @MainThread
    fun execute(request: TrainOperationRequest): TrainOperationExecutionResult
}

/** Default used outside a platform adapter. Doorless station calls never invoke it. */
object RejectingTrainOperationGateway : TrainOperationGateway {
    override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult =
        TrainOperationExecutionResult.Rejected("train operation gateway is not configured")
}

private fun requireStableText(label: String, value: String) {
    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
        "$label must be stable non-blank text"
    }
}
