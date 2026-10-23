package work.art1st.javarailways.simpleato.api.depot

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.persistence.SavedRollingStockV1

class CrossDepotStoreTest {
    private val a = DepotId("A")
    private val b = DepotId("B")
    private fun point(depot: DepotId) = StablingPointRef(depot, "one")
    private fun store() = DepotStore().also { store ->
        listOf(a, b).forEach { depot ->
            store.addStablingPoint(depot, depot.value, DepotStablingPoint(point(depot), "${depot.value}@one", 100.0))
        }
    }
    private fun stock(id: String = "EMU") = RollingStockRecord(id, a, "model/emu", setOf("metro"), 2, 40.0, 30.0, setOf("doors"))
    private fun take(store: DepotStore, depot: DepotId, assignment: String) =
        (store.allocate(DepotTrainSelector(depot), assignment, assignment) as DepotTrainAllocationResult.Allocated).allocation

    @Test fun `A to B storage and B acquisition retain home and exact assignment ownership`() {
        val store = store(); store.registerRollingStock(stock(), point(a))
        val first = take(store, a, "first")
        assertTrue(store.confirmInService(first)); assertTrue(store.releaseDeparture(first))
        val reservation = (store.reserveStorage("EMU", b, emptySet(), "first", "B") as DepotStorageReservationResult.Reserved).reservation
        assertFalse(store.cancelStorage(reservation.copy(trainId = "wrong")))
        assertTrue(store.completeStorage(reservation))
        assertEquals(a, store.rollingStock("EMU")!!.record.homeDepotId)
        assertEquals(point(b), store.rollingStock("EMU")!!.stablingPointRef)
        assertTrue(store.allocate(DepotTrainSelector(a), "old-home", "seed") is DepotTrainAllocationResult.NoMatchingTrain)
        val second = take(store, b, "second")
        assertEquals("EMU", second.trainId)
        assertTrue(store.allocate(DepotTrainSelector(b), "second", "seed") is DepotTrainAllocationResult.AssignmentConflict)
        assertTrue(store.cancelReservation(second))
        assertEquals("EMU", store.stablingPoint(point(b))!!.occupantTrainId)
    }

    @Test fun `rejected and cancelled foreign storage do not alter departure ownership`() {
        val store = store(); store.registerRollingStock(stock(), point(a))
        val allocation = take(store, a, "first")
        assertFalse(store.cancelReservation(allocation.copy(trainId = "wrong")))
        assertTrue(store.confirmInService(allocation))
        assertFalse(store.cancelReservation(allocation))
        assertTrue(store.releaseDeparture(allocation))
        assertTrue(store.reserveStorage("EMU", b, emptySet(), "wrong-assignment", "seed") is DepotStorageReservationResult.RollingStockUnavailable)
        val reservation = (store.reserveStorage("EMU", b, emptySet(), "first", "seed") as DepotStorageReservationResult.Reserved).reservation
        assertTrue(store.cancelStorage(reservation))
        assertEquals(null, store.stablingPoint(point(b))!!.reservedByAssignmentId)
        assertEquals(RollingStockState.IN_SERVICE, store.rollingStock("EMU")!!.state)
        assertEquals("first", store.rollingStock("EMU")!!.assignmentId)
    }

    @Test fun `foreign storage snapshot round trips and restores without home reassignment`() {
        val snapshot = RollingStockSnapshot(stock(), RollingStockState.AVAILABLE, point(b))
        val saved = SavedRollingStockV1.capture(snapshot)
        assertEquals("B", saved.stablingDepotId)
        val restored = Json.decodeFromString(SavedRollingStockV1.serializer(), Json.encodeToString(SavedRollingStockV1.serializer(), saved)).restore()
        assertEquals(snapshot, restored)
        val store = store(); store.restoreRollingStock(listOf(restored))
        assertEquals("EMU", take(store, b, "from-B").trainId)
    }

    @Test fun `existing current snapshot without physical depot field defaults to its home`() {
        val saved = Json.decodeFromString(SavedRollingStockV1.serializer(), """{
          "trainId":"EMU","homeDepotId":"A","modelPackageId":"model/emu","tags":[],
          "carCount":2,"lengthMeters":40.0,"maximumSpeedMetersPerSecond":30.0,"capabilities":[],
          "state":"AVAILABLE","stablingPointId":"one"
        }""")
        assertEquals(point(a), saved.restore().stablingPointRef)
    }

    @Test fun `reset follows physical B storage and ignores in service A home without a storage association`() {
        val store = store(); store.registerRollingStock(stock(), point(b))
        val aPlan = (store.planReset(setOf(a)) as DepotResetPlanningResult.Planned).plan
        assertEquals(emptyList<DepotResetAssignment>(), aPlan.assignments)
        val bPlan = (store.planReset(setOf(b)) as DepotResetPlanningResult.Planned).plan
        assertEquals(listOf("EMU"), bPlan.assignments.map { it.trainId })
        val allocation = take(store, b, "depart")
        assertTrue(store.confirmInService(allocation)); assertTrue(store.releaseDeparture(allocation))
        assertNotNull(store.validateResetPlan(bPlan))
        assertEquals(emptyList<DepotResetAssignment>(), (store.planReset() as DepotResetPlanningResult.Planned).plan.assignments)
    }

    @Test fun `reset of reserved B storage clears selected train exact leases at A too`() {
        val store = store(); store.registerRollingStock(stock(), point(a))
        val allocation = take(store, a, "first")
        assertTrue(store.confirmInService(allocation))
        store.reserveStorage("EMU", b, emptySet(), "first", "B") as DepotStorageReservationResult.Reserved
        val plan = (store.planReset(setOf(b)) as DepotResetPlanningResult.Planned).plan
        assertEquals(point(b), plan.assignments.single().stablingPointRef)
        assertTrue(store.applyReset(plan))
        assertEquals(null, store.stablingPoint(point(a))!!.occupantTrainId)
        assertEquals(null, store.stablingPoint(point(a))!!.reservedByAssignmentId)
        assertEquals("EMU", store.stablingPoint(point(b))!!.occupantTrainId)
        assertEquals(null, store.rollingStock("EMU")!!.assignmentId)
        assertEquals(a, store.rollingStock("EMU")!!.record.homeDepotId)
    }
}
