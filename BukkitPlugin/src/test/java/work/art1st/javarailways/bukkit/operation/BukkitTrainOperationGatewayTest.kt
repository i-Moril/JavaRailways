package work.art1st.javarailways.bukkit.operation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import work.art1st.javarailways.basic.api.operation.TrainOperation
import work.art1st.javarailways.basic.api.operation.TrainOperationExecutionResult
import work.art1st.javarailways.basic.api.operation.TrainOperationRequest
import work.art1st.javarailways.basic.api.operation.TrainOperationTicketState
import work.art1st.javarailways.basic.api.station.DoorSide
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult
import work.art1st.javarailways.core.physics.rail.curve.PrimeCurveGenerator
import work.art1st.javarailways.core.physics.train.createStraightRailFromSegments
import work.art1st.javarailways.core.physics.train.createTestWorld
import work.art1st.javarailways.core.physics.train.createTrainWithConfig
import work.art1st.javarailways.core.util.ThreadGuard
import work.art1st.javarailways.core.util.ThreadGuardPlatformBridge

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
    fun `returns pending ticket without waiting for region dispatch`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        var scheduled: (() -> Unit)? = null
        var executorCalled = false
        val gateway = BukkitTrainOperationGateway(
            world = world,
            operationDispatcher = BukkitTrainOperationDispatcher { _, operation, onResult ->
                scheduled = { onResult(runCatching(operation)) }
            },
            doorExecutor = BukkitDoorOperationExecutor { _, _, _, _ ->
                executorCalled = true
                TrainModelOperationResult.Accepted(0.0)
            }
        )

        val accepted = gateway.execute(
            request(train.id, TrainOperation.OpenDoors(DoorSide.LEFT))
        ) as TrainOperationExecutionResult.Accepted

        assertEquals(TrainOperationTicketState.PENDING, accepted.ticket.snapshot().state)
        assertEquals(false, executorCalled)
        scheduled!!.invoke()
        assertEquals(true, executorCalled)
        assertEquals(TrainOperationTicketState.PENDING, accepted.ticket.snapshot().state)
    }

    @Test
    fun `asynchronous region rejection fails ticket`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        val gateway = BukkitTrainOperationGateway(
            world = world,
            operationDispatcher = BukkitTrainOperationDispatcher { _, _, onResult ->
                onResult(Result.success(TrainModelOperationResult.Rejected("model rejected")))
            }
        )

        val accepted = gateway.execute(
            request(train.id, TrainOperation.OpenDoors(DoorSide.LEFT))
        ) as TrainOperationExecutionResult.Accepted
        val snapshot = accepted.ticket.snapshot()

        assertEquals(TrainOperationTicketState.FAILED, snapshot.state)
        assertEquals("model rejected", snapshot.message)
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
    fun `maps model action operation and confirms from package callback`() {
        val world = createTestWorld()
        val rail = createStraightRailFromSegments(
            world,
            PrimeCurveGenerator.buildStraight(100.0, segmentCount = 1)
        )
        val train = createTrainWithConfig("T1", rail, world, listOf(10.0))
        var observedAction: String? = null
        var completion: (() -> Unit)? = null
        val gateway = BukkitTrainOperationGateway(
            world = world,
            modelActionExecutor = BukkitModelActionExecutor { executedTrain, actionName, onComplete ->
                assertEquals(train, executedTrain)
                observedAction = actionName
                completion = onComplete
                TrainModelOperationResult.Accepted(0.4)
            }
        )

        val result = gateway.execute(
            request(train.id, TrainOperation.ExecuteModelAction("power-off"))
        ) as TrainOperationExecutionResult.Accepted

        assertEquals("power-off", observedAction)
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
