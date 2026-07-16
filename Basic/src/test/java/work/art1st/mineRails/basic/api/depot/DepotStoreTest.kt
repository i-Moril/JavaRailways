package work.art1st.mineRails.basic.api.depot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DepotStoreTest {
    @Test
    fun `allocation is atomic filtered and deterministic`() {
        val first = storeWithRollingStock()
        val second = storeWithRollingStock()
        val selector = DepotTrainSelector(
            depotId = DepotId("north"),
            requiredTags = setOf("metro"),
            minimumCars = 4,
            maximumCars = 8,
            minimumSpeedMetersPerSecond = 15.0,
            requiredCapabilities = setOf("doors")
        )

        val a = first.allocate(selector, "tt:0:S1", "tt|0|S1") as
            DepotTrainAllocationResult.Allocated
        val b = second.allocate(selector, "tt:0:S1", "tt|0|S1") as
            DepotTrainAllocationResult.Allocated

        assertEquals(a.allocation.trainId, b.allocation.trainId)
        assertTrue(first.confirmInService(a.allocation))
        assertTrue(first.releaseDeparture(a.allocation))
        assertEquals(
            RollingStockState.IN_SERVICE,
            first.rollingStock(a.allocation.trainId)?.state
        )
        assertTrue(
            first.allocate(selector, "tt:0:S1", "other") is
                DepotTrainAllocationResult.AssignmentConflict
        )
    }

    @Test
    fun `no compatible available train reports a strict miss`() {
        val store = storeWithRollingStock()
        val selector = DepotTrainSelector(
            DepotId("north"),
            requiredTags = setOf("express")
        )

        val result = store.allocate(selector, "tt:0:express", "seed")

        assertTrue(result is DepotTrainAllocationResult.NoMatchingTrain)
        assertEquals(2, (result as DepotTrainAllocationResult.NoMatchingTrain).availableTrainCount)
    }

    @Test
    fun `storage reservation atomically restores occupancy and availability`() {
        val store = storeWithRollingStock()
        val selector = DepotTrainSelector(DepotId("north"), requiredTags = setOf("metro"))
        val allocated = store.allocate(selector, "tt:0:S1", "take-seed") as
            DepotTrainAllocationResult.Allocated
        assertTrue(store.confirmInService(allocated.allocation))
        assertTrue(store.releaseDeparture(allocated.allocation))

        val storage = store.reserveStorage(
            trainId = allocated.allocation.trainId,
            depotId = DepotId("north"),
            requestedPointIds = setOf("one", "two"),
            assignmentId = allocated.allocation.assignmentId,
            stableSeed = "store-seed"
        ) as DepotStorageReservationResult.Reserved

        assertTrue(store.completeStorage(storage.reservation))
        val rolling = store.rollingStock(allocated.allocation.trainId)!!
        val point = store.stablingPoint(storage.reservation.stablingPointRef)!!
        assertEquals(RollingStockState.AVAILABLE, rolling.state)
        assertEquals(null, rolling.assignmentId)
        assertEquals(allocated.allocation.trainId, point.occupantTrainId)
        assertEquals(null, point.reservedByAssignmentId)
    }

    @Test
    fun `storage selection fails without partial reservation`() {
        val store = storeWithRollingStock()
        val selector = DepotTrainSelector(DepotId("north"), requiredTags = setOf("metro"))
        val allocated = store.allocate(selector, "tt:0:S1", "take-seed") as
            DepotTrainAllocationResult.Allocated
        assertTrue(store.confirmInService(allocated.allocation))
        assertTrue(store.releaseDeparture(allocated.allocation))

        val result = store.reserveStorage(
            trainId = allocated.allocation.trainId,
            depotId = DepotId("north"),
            requestedPointIds = setOf("missing"),
            assignmentId = allocated.allocation.assignmentId,
            stableSeed = "store-seed"
        )

        assertTrue(result is DepotStorageReservationResult.NoCompatiblePoint)
        assertTrue(store.stablingPoints(DepotId("north")).all {
            it.reservedByAssignmentId == null
        })
        assertEquals(RollingStockState.IN_SERVICE, store.rollingStock(allocated.allocation.trainId)?.state)
    }

    @Test
    fun `reset planning finds a full deterministic matching and commits atomically`() {
        val store = DepotStore()
        val depot = DepotId("north")
        store.addStablingPoint(
            depot,
            "North",
            DepotStablingPoint(
                StablingPointRef(depot, "one"),
                "north-one",
                100.0,
                setOf("express")
            )
        )
        store.addStablingPoint(
            depot,
            "North",
            DepotStablingPoint(
                StablingPointRef(depot, "two"),
                "north-two",
                100.0,
                emptySet()
            )
        )
        store.registerRollingStock(
            RollingStockRecord(
                "A-flexible",
                depot,
                "test/flexible",
                setOf("express", "metro"),
                4,
                60.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depot, "one")
        )
        store.registerRollingStock(
            RollingStockRecord(
                "B-express",
                depot,
                "test/express",
                setOf("express"),
                4,
                60.0,
                20.0,
                emptySet()
            ),
            StablingPointRef(depot, "two")
        )

        val plan = (store.planReset(setOf(depot)) as DepotResetPlanningResult.Planned).plan

        assertEquals(
            StablingPointRef(depot, "two"),
            plan.assignments.single { it.trainId == "A-flexible" }.stablingPointRef
        )
        assertEquals(
            StablingPointRef(depot, "one"),
            plan.assignments.single { it.trainId == "B-express" }.stablingPointRef
        )
        assertTrue(store.applyReset(plan))
        assertEquals("B-express", store.stablingPoint(StablingPointRef(depot, "one"))?.occupantTrainId)
        assertEquals("A-flexible", store.stablingPoint(StablingPointRef(depot, "two"))?.occupantTrainId)
    }

    @Test
    fun `failed reset preflight leaves every runtime untouched`() {
        val store = storeWithRollingStock()
        val depot = DepotId("north")
        val beforeStock = store.allRollingStock()
        val beforePoints = store.stablingPoints(depot)

        val result = store.planReset(setOf(DepotId("missing")))

        assertTrue(result is DepotResetPlanningResult.Failure)
        assertEquals(beforeStock, store.allRollingStock())
        assertEquals(beforePoints, store.stablingPoints(depot))
    }

    @Test
    fun `reservation cancellation restores the exact train and point`() {
        val store = storeWithRollingStock()
        val selector = DepotTrainSelector(DepotId("north"), requiredTags = setOf("metro"))
        val allocated = store.allocate(selector, "tt:0:S1", "seed") as
            DepotTrainAllocationResult.Allocated

        assertTrue(store.cancelReservation(allocated.allocation))

        val rolling = store.rollingStock(allocated.allocation.trainId)!!
        val point = store.stablingPoint(allocated.allocation.stablingPointRef)!!
        assertEquals(RollingStockState.AVAILABLE, rolling.state)
        assertEquals(allocated.allocation.trainId, point.occupantTrainId)
        assertEquals(null, point.reservedByAssignmentId)
    }

    private fun storeWithRollingStock(): DepotStore = DepotStore().apply {
        val depot = DepotId("north")
        addStablingPoint(
            depot,
            "North",
            DepotStablingPoint(StablingPointRef(depot, "one"), "north-one", 120.0)
        )
        addStablingPoint(
            depot,
            "North",
            DepotStablingPoint(StablingPointRef(depot, "two"), "north-two", 120.0)
        )
        registerRollingStock(
            RollingStockRecord(
                trainId = "T1",
                homeDepotId = depot,
                modelPackageId = "test/s100",
                tags = setOf("metro", "local"),
                carCount = 6,
                lengthMeters = 80.0,
                maximumSpeedMetersPerSecond = 20.0,
                capabilities = setOf("doors")
            ),
            StablingPointRef(depot, "one")
        )
        registerRollingStock(
            RollingStockRecord(
                trainId = "T2",
                homeDepotId = depot,
                modelPackageId = "test/s100",
                tags = setOf("metro"),
                carCount = 4,
                lengthMeters = 60.0,
                maximumSpeedMetersPerSecond = 18.0,
                capabilities = setOf("doors")
            ),
            StablingPointRef(depot, "two")
        )
    }
}
