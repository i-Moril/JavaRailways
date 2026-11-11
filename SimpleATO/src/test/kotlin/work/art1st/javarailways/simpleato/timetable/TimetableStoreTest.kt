package work.art1st.javarailways.simpleato.timetable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TimetableStoreTest {
    @Test
    fun `replaceAll publishes one complete immutable definition set`() {
        val store = TimetableStore()
        store.add(Timetable("old"))

        store.replaceAll(listOf(Timetable("b"), Timetable("a")))

        assertEquals(listOf("a", "b"), store.all().map(Timetable::id))
        assertNull(store["old"])
    }

    @Test
    fun `replaceAll rejects duplicate ids without replacing current definitions`() {
        val store = TimetableStore()
        store.add(Timetable("old"))

        assertThrows(IllegalArgumentException::class.java) {
            store.replaceAll(listOf(Timetable("duplicate"), Timetable("duplicate")))
        }

        assertEquals(listOf("old"), store.all().map(Timetable::id))
    }
}
