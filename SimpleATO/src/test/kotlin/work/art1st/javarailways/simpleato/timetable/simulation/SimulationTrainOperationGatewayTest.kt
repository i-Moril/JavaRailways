package work.art1st.javarailways.simpleato.timetable.simulation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState

class SimulationTrainOperationGatewayTest {
    @Test
    fun `completes supported operations after deterministic logical ticks`() {
        val gateway = SimulationTrainOperationGateway { 2L }
        val result = gateway.execute(request(TrainOperation.ExecuteModelAction("power-off")))
        assertTrue(result is TrainOperationExecutionResult.Accepted)
        val ticket = (result as TrainOperationExecutionResult.Accepted).ticket

        gateway.tick(50.0)
        assertEquals(TrainOperationTicketState.PENDING, ticket.snapshot().state)
        gateway.tick(50.0)
        assertEquals(TrainOperationTicketState.SUCCEEDED, ticket.snapshot().state)
    }

    @Test
    fun `rejects operations without a simulation implementation`() {
        val gateway = SimulationTrainOperationGateway { null }
        val result = gateway.execute(request(TrainOperation.Announce("next-stop")))

        assertTrue(result is TrainOperationExecutionResult.Rejected)
        assertTrue((result as TrainOperationExecutionResult.Rejected).reason.contains("no simulation implementation"))
    }

    private fun request(operation: TrainOperation) = TrainOperationRequest(
        "request", "run", "call", "train", 0, operation
    )
}
