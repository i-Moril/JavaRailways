package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.scriptworker.executeScript
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class TimetableScriptExecutionTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `top level and main loops terminate and compiler capacity recovers`() {
        TimetableScriptSandbox(directory, executionTimeoutMillis = 1_000).use { sandbox ->
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                for (source in listOf("while (true) {}", "function main(timetable) { while (true) {} }")) {
                    val failure = assertThrows(TimetableScriptFailure::class.java) {
                        sandbox.compileSource(source, "loop")
                    }
                    assertTrue(failure.message!!.contains("timed out"), failure.message)
                }
                assertEquals("valid", sandbox.compileSource(VALID_SCRIPT, "valid").id)
            }
        }
    }

    @Test
    fun `printed output is bounded even if guest catches write errors`() {
        TimetableScriptSandbox(directory).use { sandbox ->
            val failure = assertThrows(TimetableScriptFailure::class.java) {
                sandbox.compileSource("""
                    function main(timetable) {
                        const chunk = 'x'.repeat(65536);
                        for (let i = 0; i < 20; i++) { try { print(chunk); } catch (e) {} }
                        timetable.setPeriodDays(1);
                    }
                """.trimIndent(), "verbose")
            }
            assertTrue(failure.message!!.contains("printed output exceeds limit"), failure.message)
            assertEquals("valid", sandbox.compileSource(VALID_SCRIPT, "valid").id)
        }
    }

    @Test
    fun `close terminates active command jobs and rejects subsequent compilation`() {
        val sandbox = TimetableScriptSandbox(directory)
        sandbox.saveSource("loop.js", "function main(timetable) { while (true) {} }", false)
        val jobs = List(2) { sandbox.compileScriptAsync("loop.js", "loop-$it") }
        try {
            // Wait until both command jobs have acquired the two compiler slots.
            assertTimeoutPreemptively(Duration.ofSeconds(10)) {
                while (true) {
                    val probe = runCatching { sandbox.compileSource(VALID_SCRIPT, "probe") }.exceptionOrNull()
                    if (probe?.message?.contains("capacity is busy") == true) break
                    Thread.yield()
                }
            }
            sandbox.close()
            for (job in jobs) {
                val failure = assertThrows(ExecutionException::class.java) { job.get(5, TimeUnit.SECONDS) }
                assertTrue(failure.cause!!.message!!.contains("compiler is closed"), failure.cause!!.message)
            }
            assertThrows(TimetableScriptFailure::class.java) { sandbox.compileSource(VALID_SCRIPT, "closed") }
        } finally {
            sandbox.close()
        }
    }

    companion object {
        private const val VALID_SCRIPT = "function main(timetable) { timetable.setPeriodDays(1); }"

        @JvmStatic
        @BeforeAll
        fun warmGuestEngine() {
            // Initial engine loading is outside the deliberately short test deadline.
            executeScript(VALID_SCRIPT, "warmup")
        }
    }
}
