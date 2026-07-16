package work.art1st.mineRails.bukkit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.mineRails.basic.api.operation.TrainOperation
import work.art1st.mineRails.basic.api.operation.TrainOperationExecutionResult
import work.art1st.mineRails.basic.api.operation.TrainOperationRequest
import work.art1st.mineRails.basic.api.operation.TrainOperationTicketState
import work.art1st.mineRails.basic.api.station.DoorSide
import work.art1st.mineRails.bukkit.extension.train.TrainModelOperationResult
import work.art1st.mineRails.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.mineRails.core.physics.train.createStraightRailFromSegments
import work.art1st.mineRails.core.physics.train.createTestWorld
import work.art1st.mineRails.core.physics.train.createTrainWithConfig
import work.art1st.mineRails.core.util.ThreadGuard
import work.art1st.mineRails.core.util.ThreadGuardPlatformBridge

class BukkitTrainOperationGatewayTest {
    @BeforeEach
    fun installMainThreadBridge() {
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
    }

    @AfterEach
    fun clearMainThreadBridge() {
        ThreadGuard.clearPlatformBridge()
    }

    @Test
    fun `maps semantic door operation and confirms from model callback`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        var observedSide: DoorSide? = null
        var observedOpening: Boolean? = null
        var completion: (() -> Unit)? = null
        val gateway = BukkitTrainOperationGateway(
            world,
            BukkitDoorOperationExecutor { executedTrain, side, opening, onComplete ->
                assertEquals(train, executedTrain)
                observedSide = side
                observedOpening = opening
                completion = onComplete
                TrainModelOperationResult.Accepted(0.6)
            }
        )
        val request = request(train.id, TrainOperation.CloseDoors(DoorSide.BOTH))

        val result = gateway.execute(request) as TrainOperationExecutionResult.Accepted

        assertEquals(DoorSide.BOTH, observedSide)
        assertEquals(false, observedOpening)
        assertEquals(TrainOperationTicketState.PENDING, result.ticket.snapshot().state)
        completion!!.invoke()
        assertEquals(TrainOperationTicketState.SUCCEEDED, result.ticket.snapshot().state)
    }

    @Test
    fun `cancelled ticket ignores a later model callback`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        var completion: (() -> Unit)? = null
        val gateway = BukkitTrainOperationGateway(
            world,
            BukkitDoorOperationExecutor { _, _, _, onComplete ->
                completion = onComplete
                TrainModelOperationResult.Accepted(0.0)
            }
        )
        val accepted = gateway.execute(
            request(train.id, TrainOperation.OpenDoors(DoorSide.LEFT))
        ) as TrainOperationExecutionResult.Accepted

        accepted.ticket.cancel()
        completion!!.invoke()

        assertEquals(TrainOperationTicketState.CANCELLED, accepted.ticket.snapshot().state)
    }

    @Test
    fun `rejects unsupported semantic operation`() {
        val world = createTestWorld()
        val gateway = BukkitTrainOperationGateway(world) { _, _, _, _ ->
            TrainModelOperationResult.Accepted(0.0)
        }

        val result = gateway.execute(request("missing", TrainOperation.SetCabLights(true)))

        assertTrue(result is TrainOperationExecutionResult.Rejected)
    }

    private fun request(trainId: String, operation: TrainOperation) = TrainOperationRequest(
        requestId = "request-1",
        serviceRunId = "tt/service",
        stationCallId = "call-1",
        trainId = trainId,
        missionRevision = 1L,
        operation = operation
    )
}
