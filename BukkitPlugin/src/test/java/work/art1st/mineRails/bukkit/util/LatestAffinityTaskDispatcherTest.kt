package work.art1st.mineRails.bukkit.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LatestAffinityTaskDispatcherTest {
    @Test
    fun `keeps one in flight task and the latest pending value per key`() {
        val scheduled = mutableListOf<ScheduledValue>()
        val dispatcher = LatestAffinityTaskDispatcher<String, Int> { value, complete ->
            scheduled += ScheduledValue(value, complete)
        }

        dispatcher.offer("car", 1)
        dispatcher.offer("car", 2)
        dispatcher.offer("car", 3)

        assertEquals(listOf(1), scheduled.map { it.value })
        assertEquals(1L, dispatcher.metrics().coalescedValues)

        scheduled[0].complete()
        assertEquals(listOf(1, 3), scheduled.map { it.value })
        scheduled[1].complete()

        assertEquals(0, dispatcher.metrics().activeKeys)
        assertEquals(2L, dispatcher.metrics().scheduledTasks)
    }

    @Test
    fun `dispatches different affinity keys independently`() {
        val scheduled = mutableListOf<Pair<Int, () -> Unit>>()
        val dispatcher = LatestAffinityTaskDispatcher<String, Int> { value, complete ->
            scheduled += value to complete
        }

        dispatcher.offer("first", 1)
        dispatcher.offer("second", 2)

        assertEquals(listOf(1, 2), scheduled.map { it.first })
        assertEquals(2, dispatcher.metrics().activeKeys)
    }

    @Test
    fun `releases key when scheduler rejects a task`() {
        val dispatcher = LatestAffinityTaskDispatcher<String, Int> { _, _ ->
            error("rejected")
        }

        assertThrows(IllegalStateException::class.java) { dispatcher.offer("car", 1) }
        assertEquals(0, dispatcher.metrics().activeKeys)
    }

    private data class ScheduledValue(val value: Int, val complete: () -> Unit)
}
