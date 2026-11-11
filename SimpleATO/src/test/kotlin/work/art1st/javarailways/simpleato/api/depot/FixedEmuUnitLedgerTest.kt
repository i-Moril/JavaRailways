package work.art1st.javarailways.simpleato.api.depot

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.persistence.SavedFixedEmuUnit
import work.art1st.javarailways.simpleato.persistence.SavedFixedEmuUnitAssignment

class FixedEmuUnitLedgerTest {
    private val a = DepotId("A")
    private val b = DepotId("B")
    private val first = FixedEmuUnit("U1", a, listOf("a", "b"), "emu/control")
    private val second = FixedEmuUnit("U2", b, listOf("c", "d"), "emu/control")
    private val cars = listOf("a", "b", "c", "d")
    private fun empty() = DepotStore().also { store ->
        listOf(a, b).forEach { depot -> store.addStablingPoint(depot, depot.value, DepotStablingPoint(StablingPointRef(depot, "one"), "${depot.value}@one")) }
    }
    private fun registered() = empty().also { store ->
        store.registerFixedUnit(first, cars)
        store.registerFixedUnit(second, cars)
    }

    @Test fun `fixed unit identity and home survive reordered whole reversed units`() {
        val store = registered()
        assertEquals(listOf(first, second), store.fixedUnitsForFormation(cars))
        assertEquals(listOf(second, first), store.fixedUnitsForFormation(listOf("d", "c", "b", "a")))
        assertEquals(setOf(a, b), store.fixedUnitsForFormation(cars).map { it.homeDepotId }.toSet())
        assertEquals(first, SavedFixedEmuUnit.capture(first).restore())
        val encoded = Json.encodeToString(SavedFixedEmuUnit.serializer(), SavedFixedEmuUnit.capture(second))
        assertEquals(second, Json.decodeFromString(SavedFixedEmuUnit.serializer(), encoded).restore())
    }

    @Test fun `caller and Java list mutations cannot alter fixed unit membership`() {
        val input = mutableListOf("a", "b")
        val unit = FixedEmuUnit("immutable", a, input, "emu/control")
        val store = empty(); store.registerFixedUnit(unit, input)
        input[0] = "changed"
        assertEquals(listOf("a", "b"), store.allFixedUnits().single().carIds)
        assertThrows(UnsupportedOperationException::class.java) { (unit.carIds as MutableList<String>).clear() }
        assertEquals(listOf("a", "b"), store.allFixedUnits().single().carIds)
    }

    @Test fun `registration rejects overlapping or noncontiguous permanent car memberships before mutation`() {
        val store = registered(); val before = store.allFixedUnits()
        assertThrows(IllegalArgumentException::class.java) { store.registerFixedUnit(FixedEmuUnit("overlap", a, listOf("b", "c"), "emu/control"), cars) }
        assertThrows(IllegalArgumentException::class.java) { store.registerFixedUnit(FixedEmuUnit("gap", a, listOf("x", "z"), "emu/control"), listOf("x", "y", "z")) }
        assertEquals(before, store.allFixedUnits())
        listOf(listOf("a"), listOf("a", "c", "b", "d"), listOf("a", "b", "unknown"), listOf("a", "a")).forEach { incomplete ->
            assertThrows(IllegalArgumentException::class.java) { store.fixedUnitsForFormation(incomplete) }
        }
    }

    @Test fun `declared incompatible control families cannot claim a formation`() {
        val store = empty(); store.registerFixedUnit(first, cars)
        store.registerFixedUnit(FixedEmuUnit(second.id, second.homeDepotId, second.carIds, "different/control"), cars)
        assertThrows(IllegalArgumentException::class.java) { store.claimFixedFormation("T", cars, "duty") }
        assertEquals(emptyList<FixedEmuUnitAssignment>(), store.allFixedUnitAssignments())
    }

    @Test fun `all unit assignment claims succeed or fail together and reject duplicate asset use`() {
        val store = registered()
        val secondClaim = store.claimFixedFormation("T2", second.carIds, "duty2")
        assertThrows(IllegalArgumentException::class.java) { store.claimFixedFormation("T1", cars, "duty1") }
        assertEquals(secondClaim, store.allFixedUnitAssignments())
        assertThrows(IllegalArgumentException::class.java) { store.claimFixedFormation("T1", first.carIds, "duty2") }
        assertFalse(store.releaseFixedFormation("wrong", "duty2"))
        assertEquals(secondClaim, store.allFixedUnitAssignments())
        assertTrue(store.releaseFixedFormation("T2", "duty2"))
        val claims = store.claimFixedFormation("T", cars, "duty")
        assertEquals(claims, store.claimFixedFormation("T", cars, "duty"))
        assertThrows(IllegalArgumentException::class.java) { store.claimFixedFormation("T", first.carIds, "duty") }
        assertEquals(claims, store.allFixedUnitAssignments())
    }

    @Test fun `separation only accepts boundaries between complete fixed units`() {
        val store = registered()
        assertEquals(listOf(first) to listOf(second), store.validateFixedUnitSplit(cars, "c"))
        listOf("a", "b", "d", "unknown").forEach { split ->
            assertThrows(IllegalArgumentException::class.java) { store.validateFixedUnitSplit(cars, split) }
        }
        assertEquals(emptyList<FixedEmuUnitAssignment>(), store.allFixedUnitAssignments())
    }

    @Test fun `restore preserves whole formation claims and rejects missing or partially assigned Core membership`() {
        val claims = listOf(FixedEmuUnitAssignment("U1", "T", "duty"), FixedEmuUnitAssignment("U2", "T", "duty"))
        val store = empty(); store.restoreFixedUnits(listOf(first, second), claims, mapOf("T" to cars))
        assertEquals(claims, store.allFixedUnitAssignments())
        val saved = SavedFixedEmuUnitAssignment.capture(claims.first())
        assertEquals(claims.first(), Json.decodeFromString(SavedFixedEmuUnitAssignment.serializer(), Json.encodeToString(SavedFixedEmuUnitAssignment.serializer(), saved)).restore())
        val invalid = empty()
        assertThrows(IllegalArgumentException::class.java) { invalid.restoreFixedUnits(listOf(first, second), claims.take(1), mapOf("T" to cars)) }
        assertThrows(IllegalArgumentException::class.java) { invalid.restoreFixedUnits(listOf(first, second), claims, mapOf("T" to first.carIds)) }
        assertEquals(emptyList<FixedEmuUnit>(), invalid.allFixedUnits())
        assertEquals(emptyList<FixedEmuUnitAssignment>(), invalid.allFixedUnitAssignments())
    }

    @Test fun `real depot reservation and storage own registered unit claims atomically`() {
        val store = registered()
        val record = RollingStockRecord("T", a, "model/emu", emptySet(), 4, 80.0, 30.0, emptySet())
        val atA = StablingPointRef(a, "one")
        store.registerRollingStock(record, atA)
        assertThrows(IllegalArgumentException::class.java) { store.reservePhysicallySelected("T", atA, DepotTrainSelector(a), "duty", listOf("a")) }
        assertEquals(RollingStockState.AVAILABLE, store.rollingStock("T")!!.state)
        assertEquals(emptyList<FixedEmuUnitAssignment>(), store.allFixedUnitAssignments())
        val allocation = checkNotNull(store.reservePhysicallySelected("T", atA, DepotTrainSelector(a), "duty", cars))
        assertEquals(2, store.allFixedUnitAssignments().size)
        assertTrue(store.confirmInService(allocation)); assertTrue(store.releaseDeparture(allocation))
        assertEquals(2, store.allFixedUnitAssignments().size)
        val storage = (store.reserveStorage("T", b, emptySet(), "duty", "seed") as DepotStorageReservationResult.Reserved).reservation
        assertTrue(store.completeStorage(storage))
        assertEquals(emptyList<FixedEmuUnitAssignment>(), store.allFixedUnitAssignments())
        val next = checkNotNull(store.reservePhysicallySelected("T", storage.stablingPointRef, DepotTrainSelector(b), "next", cars))
        assertEquals(2, store.allFixedUnitAssignments().size)
        assertTrue(store.cancelReservation(next))
        assertEquals(emptyList<FixedEmuUnitAssignment>(), store.allFixedUnitAssignments())
    }

    @Test fun `registered metadata and unit claims cannot be omitted on recovery or reused before reset`() {
        val stock = RollingStockSnapshot(RollingStockRecord("T", a, "model/emu", emptySet(), 4, 80.0, 30.0, emptySet()), RollingStockState.RESERVED, StablingPointRef(a, "one"), "duty")
        val claims = listOf(FixedEmuUnitAssignment("U1", "T", "duty"), FixedEmuUnitAssignment("U2", "T", "duty"))
        val store = empty()
        assertThrows(IllegalArgumentException::class.java) { store.restoreOperationalLedger(listOf(stock), listOf(first, second), emptyList(), mapOf("T" to cars)) }
        assertThrows(IllegalArgumentException::class.java) { store.restoreOperationalLedger(emptyList(), listOf(first, second), claims, mapOf("T" to cars)) }
        assertEquals(emptyList<RollingStockSnapshot>(), store.allRollingStock())
        assertEquals(emptyList<FixedEmuUnit>(), store.allFixedUnits())
        store.restoreOperationalLedger(listOf(stock), listOf(first, second), claims, mapOf("T" to cars))
        assertThrows(IllegalArgumentException::class.java) { store.reservePhysicallySelected("T", StablingPointRef(a, "one"), DepotTrainSelector(a), "duty", cars) }
        assertEquals(claims, store.allFixedUnitAssignments())
        val reset = (store.planReset(setOf(a)) as DepotResetPlanningResult.Planned).plan
        assertTrue(store.applyReset(reset))
        assertEquals(emptyList<FixedEmuUnitAssignment>(), store.allFixedUnitAssignments())
    }

    @Test fun `operational restore validates rolling stock and units before either ledger is published`() {
        val store = empty()
        val stock = RollingStockSnapshot(RollingStockRecord("T", a, "model/emu", emptySet(), 4, 80.0, 30.0, emptySet()), RollingStockState.IN_SERVICE, null, "other-duty")
        val claims = listOf(FixedEmuUnitAssignment("U1", "T", "duty"), FixedEmuUnitAssignment("U2", "T", "duty"))
        assertThrows(IllegalArgumentException::class.java) { store.restoreOperationalLedger(listOf(stock), listOf(first, second), claims, mapOf("T" to cars)) }
        assertEquals(emptyList<FixedEmuUnit>(), store.allFixedUnits())
        assertEquals(emptyList<RollingStockSnapshot>(), store.allRollingStock())
        store.restoreOperationalLedger(listOf(stock.copy(assignmentId = "duty")), listOf(first, second), claims, mapOf("T" to cars))
        assertEquals(claims, store.allFixedUnitAssignments())
        assertEquals("duty", store.rollingStock("T")!!.assignmentId)
    }
}
