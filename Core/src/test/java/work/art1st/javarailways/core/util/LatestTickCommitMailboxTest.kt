package work.art1st.javarailways.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.util.tick.Synchronizable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestTickCommitMailboxTest {
    @Test
    fun `concurrent offers coalesce by object identity`() {
        val mailbox = LatestTickCommitMailbox(16)
        val request = NoOpSynchronizable()
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(1_000) { executor.submit { mailbox.offer(request) } }
        } finally {
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }

        assertEquals(1, mailbox.metrics().size)
        assertEquals(999L, mailbox.metrics().coalescedRequests)
        assertEquals(listOf(request), mailbox.drain())
    }

    @Test
    fun `capacity drops a new request without evicting pending work`() {
        val mailbox = LatestTickCommitMailbox(2)
        val first = NoOpSynchronizable()
        val second = NoOpSynchronizable()
        val third = NoOpSynchronizable()

        mailbox.offer(first)
        mailbox.offer(second)
        mailbox.offer(third)

        assertEquals(setOf(first, second), mailbox.drain().toSet())
        assertEquals(1L, mailbox.metrics().droppedCapacityRequests)
    }

    private class NoOpSynchronizable : Synchronizable {
        override fun commit() = Unit
    }
}
