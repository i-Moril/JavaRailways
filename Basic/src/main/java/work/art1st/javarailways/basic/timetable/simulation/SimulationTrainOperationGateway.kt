package work.art1st.javarailways.basic.timetable.simulation

import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationGateway
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicket
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketSnapshot
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.core.api.util.tick.TickingObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Resolves the duration of a model operation in logical ticks; null means no simulation implementation. */
fun interface SimulationTrainOperationDurationResolver {
    fun durationTicks(request: TrainOperationRequest): Long?
}

/** Tick-driven operation gateway for an isolated world; it performs no platform or renderer effects. */
class SimulationTrainOperationGateway(
    private val durationResolver: SimulationTrainOperationDurationResolver
) : TrainOperationGateway, TickingObject {
    private val sequence = AtomicLong()
    private val logicalTick = AtomicLong()
    private val tickets = ConcurrentHashMap<String, Ticket>()

    override fun execute(request: TrainOperationRequest): TrainOperationExecutionResult {
        val duration = durationResolver.durationTicks(request)
            ?: return TrainOperationExecutionResult.Rejected(
                "no simulation implementation for ${request.operation.description()}"
            )
        if (duration < 0L) {
            return TrainOperationExecutionResult.Rejected("simulation operation duration must not be negative")
        }
        val ticket = Ticket(
            request,
            "simulation-operation-${sequence.incrementAndGet()}",
            Math.addExact(logicalTick.get(), duration)
        )
        tickets[ticket.ticketId] = ticket
        return TrainOperationExecutionResult.Accepted(ticket)
    }

    override fun tick(deltaT: Double) {
        val now = logicalTick.incrementAndGet()
        tickets.values.forEach { ticket ->
            if (ticket.completeAtTick <= now) ticket.complete()
            if (!ticket.isPending()) tickets.remove(ticket.ticketId, ticket)
        }
    }

    private class Ticket(
        override val request: TrainOperationRequest,
        val ticketId: String,
        val completeAtTick: Long
    ) : TrainOperationTicket {
        private val pending = AtomicBoolean(true)
        private val cancelled = AtomicBoolean(false)

        fun complete() { pending.compareAndSet(true, false) }
        fun isPending(): Boolean = pending.get()

        override fun snapshot(): TrainOperationTicketSnapshot = TrainOperationTicketSnapshot(
            ticketId,
            request.requestId,
            when {
                cancelled.get() -> TrainOperationTicketState.CANCELLED
                pending.get() -> TrainOperationTicketState.PENDING
                else -> TrainOperationTicketState.SUCCEEDED
            }
        )

        override fun cancel() {
            if (pending.compareAndSet(true, false)) cancelled.set(true)
        }
    }
}

private fun TrainOperation.description(): String = when (this) {
    is TrainOperation.OpenDoors -> "open doors ${side.name.lowercase()}"
    is TrainOperation.CloseDoors -> "close doors ${side.name.lowercase()}"
    is TrainOperation.SetCabLights -> "set cab lights"
    is TrainOperation.ExecuteModelAction -> "model action $actionName"
    is TrainOperation.Announce -> "announcement $messageId"
}
