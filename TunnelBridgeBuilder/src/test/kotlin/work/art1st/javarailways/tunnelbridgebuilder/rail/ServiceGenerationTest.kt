package work.art1st.javarailways.tunnelbridgebuilder.rail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ServiceGenerationTest {
    @Test
    fun `late plan apply and undo cannot enter a recovered generation`() {
        val generations = ServiceGeneration<String>()
        val old = generations.bind("first") { true }
        generations.invalidate()
        val recovered = generations.bind("second") { true }

        assertFalse(old.isCurrent())
        assertThrows(IllegalStateException::class.java) { old.use { error("retired callback ran") } }
        assertEquals("second", recovered.use { it })
    }

    @Test
    fun `disabled provider rejects work even before unregister event`() {
        var enabled = true
        val lease = ServiceGeneration<String>().bind("service") { enabled }
        enabled = false
        assertFalse(lease.isCurrent())
        assertThrows(IllegalStateException::class.java) { lease.use { error("disabled provider used") } }
    }

    @Test
    fun `invalidation finishes current group and rejects remaining mutations`() {
        val generations = ServiceGeneration<String>()
        val lease = generations.bind("service") { true }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val mutation = pool.submit<String> {
                lease.use {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    it
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val invalidation = pool.submit {
                invalidationStarted.countDown()
                generations.invalidate()
            }
            check(invalidationStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("service", mutation.get(5, TimeUnit.SECONDS))
            invalidation.get(5, TimeUnit.SECONDS)
            assertThrows(IllegalStateException::class.java) { lease.use { error("late mutation ran") } }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
