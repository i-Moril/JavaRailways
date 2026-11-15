package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.scriptworker.executeScript
import java.nio.file.Path
import java.time.Duration

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
    fun `closed compiler rejects subsequent compilation`() {
        val sandbox = TimetableScriptSandbox(directory)
        sandbox.close()
        assertThrows(TimetableScriptFailure::class.java) {
            sandbox.compileSource(VALID_SCRIPT, "closed")
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
