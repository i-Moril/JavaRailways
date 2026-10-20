package work.art1st.javarailways.bukkit.operation

import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicket
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketSnapshot
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import java.util.concurrent.atomic.AtomicReference

/** Cancellation and result publication share one terminal transition in both adapters. */
internal class ModelOperationTicket(override val request: TrainOperationRequest, namespace: String) : TrainOperationTicket {
    private val status = AtomicReference(TrainOperationTicketSnapshot("$namespace:${request.requestId}", request.requestId, TrainOperationTicketState.PENDING))
    fun isPending() = status.get().state == TrainOperationTicketState.PENDING
    fun complete() = transition(TrainOperationTicketState.SUCCEEDED)
    fun fail(message: String) = transition(TrainOperationTicketState.FAILED, message)
    override fun snapshot() = status.get()
    override fun cancel() = transition(TrainOperationTicketState.CANCELLED)
    private fun transition(state: TrainOperationTicketState, message: String? = null) {
        status.updateAndGet { current ->
            if (current.state == TrainOperationTicketState.PENDING) current.copy(state = state, message = message) else current
        }
    }
}
