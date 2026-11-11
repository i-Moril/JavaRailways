package work.art1st.javarailways.decorations.integration.simpledisplays

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import simpledisplays.api.DisplayTemplate
import simpledisplays.api.TextDraw
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CompiledTemplateCacheTest {
    private class Template(private val closeAction: () -> Unit = {}) : DisplayTemplate {
        val closes = AtomicInteger()
        override val width = 1.0
        override val height = 1.0
        override fun draw(values: Map<String, String>, elapsedTicks: Long): List<TextDraw> = error("Validation must never draw")
        override fun close() { closes.incrementAndGet(); closeAction() }
    }

    @Test fun `exact source reuse changed source LRU eviction and bounded ownership`() {
        val compiled = mutableMapOf<String, MutableList<Template>>()
        val cache = CompiledTemplateCache({ source -> Template().also { compiled.getOrPut(source) { mutableListOf() }.add(it) } }, { throw AssertionError(it) })
        cache.validate("source0"); cache.validate("source0")
        assertEquals(1, compiled.getValue("source0").size)
        (1..63).forEach { cache.validate("source$it") }
        cache.validate("source0") // Touch the first source: source1 becomes oldest unused.
        cache.validate("changed source")
        assertEquals(0, compiled.getValue("source0").single().closes.get())
        assertEquals(1, compiled.getValue("source1").single().closes.get())
        assertEquals(64, compiled.values.flatten().count { it.closes.get() == 0 })
        cache.validate("source1")
        assertEquals(2, compiled.getValue("source1").size)
        cache.close(); cache.close()
        assertTrue(compiled.values.flatten().all { it.closes.get() == 1 })
        assertThrows<IllegalStateException> { cache.validate("source0") }
    }

    @Test fun `failure is not cached and already validated sources retain ownership`() {
        val prefix = Template()
        val recovered = Template()
        var tries = 0
        val failure = IllegalArgumentException("broken source")
        val cache = CompiledTemplateCache({ source ->
            if (source == "prefix") prefix else {
                if (++tries == 1) throw failure
                recovered
            }
        }, { throw AssertionError(it) })
        cache.validate("prefix")
        assertSame(failure, assertThrows<IllegalArgumentException> { cache.validate("broken") })
        assertEquals(0, prefix.closes.get())
        cache.validate("broken"); cache.validate("broken")
        assertEquals(2, tries)
        cache.close()
        assertEquals(1, prefix.closes.get()); assertEquals(1, recovered.closes.get())
    }

    @Test fun `duplicate concurrent compilation closes loser and retains one compiled source`() {
        val entered = CountDownLatch(2)
        val proceed = CountDownLatch(1)
        val templates = java.util.concurrent.ConcurrentLinkedQueue<Template>()
        val cache = CompiledTemplateCache({
            entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS))
            Template().also(templates::add)
        }, { throw AssertionError(it) })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val jobs = (1..2).map { executor.submit { cache.validate("same") } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            proceed.countDown(); jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(2, templates.size)
            assertEquals(1, templates.count { it.closes.get() == 1 })
            cache.validate("same")
            assertEquals(2, templates.size)
            cache.close()
            assertTrue(templates.all { it.closes.get() == 1 })
        } finally { proceed.countDown(); executor.shutdownNow(); cache.close() }
    }

    @Test fun `close during compile rejects and closes the late candidate`() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val candidate = Template()
        val cache = CompiledTemplateCache({
            entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)); candidate
        }, { throw AssertionError(it) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val job = executor.submit { cache.validate("late") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cache.close() // Must not block waiting for an external compiler under the cache monitor.
            proceed.countDown()
            val failure = assertThrows<java.util.concurrent.ExecutionException> { job.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals(1, candidate.closes.get())
        } finally { proceed.countDown(); executor.shutdownNow(); cache.close() }
    }

    @Test fun `cleanup attempts every owned template and reports failures including eviction`() {
        val templates = mutableListOf<Template>()
        val failures = mutableListOf<Throwable>()
        val cache = CompiledTemplateCache({ Template { throw IllegalStateException("close failure") }.also(templates::add) }, failures::add)
        (0..64).forEach { cache.validate("source$it") }
        assertEquals(1, failures.size)
        assertEquals(1, templates.first().closes.get())
        cache.close()
        assertEquals(2, failures.size)
        assertEquals(63, failures.last().suppressed.size)
        assertTrue(templates.all { it.closes.get() == 1 })
    }

    @Test fun `compiler and close callbacks can reenter cache lifecycle without monitor deadlock`() {
        lateinit var cache: CompiledTemplateCache
        val executor = Executors.newSingleThreadExecutor()
        val template = Template {
            executor.submit { cache.close() }.get(5, TimeUnit.SECONDS)
        }
        cache = CompiledTemplateCache({
            executor.submit { cache.close() }.get(5, TimeUnit.SECONDS)
            template
        }, { throw AssertionError(it) })
        try {
            assertThrows<IllegalStateException> { cache.validate("source") }
            assertEquals(1, template.closes.get())
        } finally { executor.shutdownNow(); cache.close() }
    }
}
