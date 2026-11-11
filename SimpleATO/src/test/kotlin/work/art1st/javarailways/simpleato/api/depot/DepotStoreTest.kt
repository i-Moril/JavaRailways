package work.art1st.javarailways.simpleato.api.depot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DepotStoreTest {
    @Test
    fun `candidate filtering uses declared facts and actual car count without an inventory registration`() {
        val selector = DepotTrainSelector(DepotId("north"), setOf("metro"), minimumCars = 4,
            maximumCars = 8, minimumSpeedMetersPerSecond = 15.0, requiredCapabilities = setOf("doors"))
        val facts = TrainCandidateFacts("test/s100", setOf("metro", "local"), 20.0, setOf("doors"))
        assertTrue(selector.matches(facts, 6))
        assertFalse(selector.matches(facts, 3))
        assertFalse(selector.matches(facts, 9))
        assertFalse(selector.matches(TrainCandidateFacts("test/s100", setOf("metro"), null, setOf("doors")), 6))
        assertFalse(selector.matches(TrainCandidateFacts("test/s100", setOf("metro"), 14.0, setOf("doors")), 6))
        assertFalse(selector.matches(TrainCandidateFacts("test/s100", setOf("express"), 20.0, setOf("doors")), 6))
        assertFalse(selector.matches(TrainCandidateFacts("test/s100", setOf("metro"), 20.0, emptySet()), 6))
        assertFalse(selector.matches(null, 6))
        assertTrue(DepotTrainSelector(DepotId("north")).matches(null, 1))
    }

    @Test
    fun `wrong task ownership cannot cancel an actual unit claim`() {
        val units = listOf(FixedEmuUnit("unit", listOf("a", "b")))
        val store = DepotStore { units }
        val claimed = store.claimFixedFormation("train", listOf("a", "b"), "weekday/service")
        assertFalse(store.releaseFixedFormation("wrong-train", "weekday/service"))
        assertFalse(store.releaseFixedFormation("train", "wrong-service"))
        assertEquals(claimed, store.allFixedUnitAssignments())
        assertThrows(IllegalArgumentException::class.java) {
            store.claimFixedFormation("other-train", listOf("a", "b"), "other-service")
        }
        assertEquals(claimed, store.allFixedUnitAssignments())
        assertTrue(store.releaseFixedFormation("train", "weekday/service"))
        assertTrue(store.allFixedUnitAssignments().isEmpty())
    }

    @Test
    fun `invalid restored task claims leave the entire ledger unchanged`() {
        val units = listOf(FixedEmuUnit("first", listOf("a")), FixedEmuUnit("second", listOf("b")))
        val store = DepotStore { units }
        assertThrows(IllegalArgumentException::class.java) {
            store.restoreFixedUnitAssignments(listOf(
                FixedEmuUnitAssignment("first", "train-a", "service"),
                FixedEmuUnitAssignment("second", "train-a", "service")),
                mapOf("train-a" to listOf("a"), "train-b" to listOf("b")))
        }
        assertTrue(store.allFixedUnitAssignments().isEmpty())
        assertEquals(units, store.allFixedUnits())
    }
}
