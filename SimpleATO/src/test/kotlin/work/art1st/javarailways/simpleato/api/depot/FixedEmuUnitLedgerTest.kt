package work.art1st.javarailways.simpleato.api.depot

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.simpleato.persistence.SavedFixedEmuUnitAssignment

class FixedEmuUnitLedgerTest {
    private val a = DepotId("A")
    private val b = DepotId("B")
    private val first = FixedEmuUnit("U1", listOf("a", "b"), "emu/control")
    private val second = FixedEmuUnit("U2", listOf("c", "d"), "emu/control")
    private val cars = listOf("a", "b", "c", "d")
    private fun empty(units: List<FixedEmuUnit> = listOf(first, second)) = DepotStore { units }.also { store ->
        listOf(a, b).forEach { depot -> store.addStablingPoint(depot, depot.value, DepotStablingPoint(StablingPointRef(depot, "one"), "${depot.value}@one")) }
    }
    private fun registered() = empty()

    @Test fun `Core supplied unit identity survives reordered whole reversed units without depot membership`() {
        val store = DepotStore { listOf(first, second) }
        assertEquals(listOf(first, second), store.fixedUnitsForFormation(cars))
        assertEquals(listOf(second, first), store.fixedUnitsForFormation(listOf("d", "c", "b", "a")))
        assertEquals(listOf("U1", "U2"), store.allFixedUnits().map { it.id })
    }

    @Test fun `caller and Java list mutations cannot alter fixed unit membership`() {
        val input = mutableListOf("a", "b")
        val unit = FixedEmuUnit("immutable", input, "emu/control")
        val store = empty(listOf(unit))
        input[0] = "changed"
        assertEquals(listOf("a", "b"), store.allFixedUnits().single().carIds)
        assertThrows(UnsupportedOperationException::class.java) { (unit.carIds as MutableList<String>).clear() }
        assertEquals(listOf("a", "b"), store.allFixedUnits().single().carIds)
    }

    @Test fun `Core projection rejects overlapping or incomplete membership without editable registration`() {
        val store = registered()
        val before = store.allFixedUnits()
        val overlap = DepotStore { listOf(first, FixedEmuUnit("overlap", listOf("b", "c"))) }
        assertThrows(IllegalArgumentException::class.java) { overlap.allFixedUnits() }
        assertEquals(before, store.allFixedUnits())
        listOf(listOf("a"), listOf("a", "c", "b", "d"), listOf("a", "b", "unknown"), listOf("a", "a")).forEach { incomplete ->
            assertThrows(IllegalArgumentException::class.java) { store.fixedUnitsForFormation(incomplete) }
        }
    }

    @Test fun `unknown control families allow ordinary claims but do not prove coupling compatibility`() {
        val store = empty(listOf(FixedEmuUnit(first.id, first.carIds), FixedEmuUnit(second.id, second.carIds)))
        assertEquals(2, store.claimFixedFormation("T", cars, "duty").size)
        assertThrows(IllegalArgumentException::class.java) { store.validateFixedUnitCoupling(cars) }
        assertTrue(store.releaseFixedFormation("T", "duty"))
        store.registerUnitControlCompatibility(first.id, "emu")
        store.registerUnitControlCompatibility(second.id, "different")
        assertThrows(IllegalArgumentException::class.java) { store.validateFixedUnitCoupling(cars) }
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
        val store = empty(); store.restoreFixedUnitAssignments(claims, mapOf("T" to cars))
        assertEquals(claims, store.allFixedUnitAssignments())
        val saved = SavedFixedEmuUnitAssignment.capture(claims.first())
        assertEquals(claims.first(), Json.decodeFromString(SavedFixedEmuUnitAssignment.serializer(), Json.encodeToString(SavedFixedEmuUnitAssignment.serializer(), saved)).restore())
        val invalid = empty()
        assertThrows(IllegalArgumentException::class.java) { invalid.restoreFixedUnitAssignments(claims.take(1), mapOf("T" to cars)) }
        assertThrows(IllegalArgumentException::class.java) { invalid.restoreFixedUnitAssignments(claims, mapOf("T" to first.carIds)) }
        assertEquals(listOf(first, second), invalid.allFixedUnits())
        assertEquals(emptyList<FixedEmuUnitAssignment>(), invalid.allFixedUnitAssignments())
    }





    @Test fun `ordinary unit claims round trip without any depot or control declaration`() {
        val unit = FixedEmuUnit("ordinary", listOf("ordinary-car"))
        val source = DepotStore { listOf(unit) }
        val claims = source.claimFixedFormation("ordinary-train", unit.carIds, "ordinary-duty")
        val restored = DepotStore { listOf(unit) }
        restored.restoreOperationalLedger(claims, mapOf("ordinary-train" to unit.carIds))
        assertEquals(claims, restored.allFixedUnitAssignments())
        assertEquals(listOf(unit), restored.allFixedUnits())
        assertThrows(IllegalArgumentException::class.java) { restored.validateFixedUnitCoupling(unit.carIds) }
    }


    @Test fun `operational restore validates every claim before publishing any ownership`() {
        val store = empty()
        val claims = listOf(FixedEmuUnitAssignment("U1", "T", "duty"), FixedEmuUnitAssignment("U2", "T", "other"))
        assertThrows(IllegalArgumentException::class.java) { store.restoreOperationalLedger(claims, mapOf("T" to cars)) }
        assertTrue(store.allFixedUnitAssignments().isEmpty())
        assertEquals(listOf(first, second), store.allFixedUnits())
    }
}
