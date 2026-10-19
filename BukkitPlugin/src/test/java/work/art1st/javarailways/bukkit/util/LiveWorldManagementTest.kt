package work.art1st.javarailways.bukkit.util

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.physics.WorldExecutionOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveWorldManagementTest {
    @Test
    fun `owner callback rejects management before waiting for management mutex`() {
        val owner = WorldExecutionOwner()
        val managerEntered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            owner.run {
                val management = executor.submit {
                    LiveWorldManagement.run {
                        managerEntered.countDown()
                        owner.run { true }
                    }
                }
                assertTrue(managerEntered.await(5, TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java) { LiveWorldManagement.run { true } }
                // Unrelated World work does not acquire the LIVE management mutex.
                WorldExecutionOwner().run { assertTrue(true) }
                assertTrue(!management.isDone)
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `management may nest under its own stable owner snapshot`() {
        val owner = WorldExecutionOwner()
        LiveWorldManagement.run {
            WorldExecutionOwner.runTogether(listOf(owner)) {
                LiveWorldManagement.run { owner.run { true } }
            }
        }
    }
}
