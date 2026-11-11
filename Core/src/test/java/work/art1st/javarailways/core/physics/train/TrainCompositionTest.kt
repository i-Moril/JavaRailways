package work.art1st.javarailways.core.physics.train

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.physics.train.TrainComposition
import work.art1st.javarailways.core.api.physics.train.TrainUnitComposition

class TrainCompositionTest {
    @Test fun `unit partition is detached and resolves whole reversed units in physical order`() {
        val canonical = mutableListOf("a", "b")
        val first = TrainUnitComposition("first", canonical)
        val second = TrainUnitComposition("second", listOf("c", "d"))
        val inputs = mutableListOf(first, second)
        val actual = listOf("d", "c", "a", "b")
        val composition = TrainComposition.create(inputs, actual)
        canonical.clear()
        inputs.clear()
        assertEquals(listOf(second, first), composition.units)
        assertEquals(listOf("a", "b"), first.carIds)
        assertTrue(second.isReversedIn(actual))
        assertFalse(first.isReversedIn(actual))
        assertEquals(listOf(first), composition.unitsFor(listOf("a", "b")))
        assertTrue(composition.unitsFor(listOf("a")).isEmpty())
        assertThrows(UnsupportedOperationException::class.java) { (composition.units as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (first.carIds as MutableList<*>).clear() }
    }

    @Test fun `split keeps whole units and gives the canonical suffix fragment the new train identity`() {
        val composition = TrainComposition.create(listOf(TrainUnitComposition("front", listOf("a", "b")),
            TrainUnitComposition("rear", listOf("c", "d"))), listOf("a", "b", "c", "d"))
        val boundary = composition.splitAt(listOf("a", "b", "c", "d"), 2, "separated")
        assertEquals(listOf("front"), boundary.retained.units.map { it.unitId })
        assertEquals(listOf("rear"), boundary.separated.units.map { it.unitId })
        val internal = composition.splitAt(listOf("a", "b", "c", "d"), 1, "separated")
        assertEquals(listOf("a"), internal.retained.units.single().carIds)
        assertEquals(listOf("separated", "rear"), internal.separated.units.map { it.unitId })
        assertEquals(listOf("b"), internal.separated.units.first().carIds)
        assertEquals(2, composition.units.size)
        assertThrows(IllegalArgumentException::class.java) {
            composition.splitAt(listOf("a", "b", "c", "d"), 1, "rear")
        }
    }

    @Test fun `internal split of a reversed unit preserves both canonical fragment directions`() {
        val source = TrainComposition.single(listOf("a", "b", "c", "d"), "original")
        val split = source.splitAt(listOf("d", "c", "b", "a"), 2, "new-train")
        assertEquals("original", split.retained.units.single().unitId)
        assertEquals(listOf("c", "d"), split.retained.units.single().carIds)
        assertEquals("new-train", split.separated.units.single().unitId)
        assertEquals(listOf("a", "b"), split.separated.units.single().carIds)
        assertTrue(split.retained.units.single().isReversedIn(listOf("d", "c")))
        assertTrue(split.separated.units.single().isReversedIn(listOf("b", "a")))
    }

    @Test fun `invalid membership duplicates partial units and interleaving are rejected before installation`() {
        val first = TrainUnitComposition("first", listOf("a", "b"))
        val second = TrainUnitComposition("second", listOf("c", "d"))
        for (actual in listOf(listOf("a", "c", "b", "d"), listOf("a", "b", "c"), listOf("a", "b", "c", "c"))) {
            assertThrows(IllegalArgumentException::class.java) { TrainComposition.create(listOf(first, second), actual) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrainComposition.create(listOf(first, TrainUnitComposition("first", listOf("c", "d"))), listOf("a", "b", "c", "d"))
        }
        assertEquals(listOf("a", "b"), first.carIds)
    }
}
