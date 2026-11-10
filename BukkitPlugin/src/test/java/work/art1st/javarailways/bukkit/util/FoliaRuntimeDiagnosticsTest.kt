package work.art1st.javarailways.bukkit.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class FoliaRuntimeDiagnosticsTest {
    class Scheduler(private val count: Int) {
        fun getTotalThreadCount() = count
    }

    @Test
    fun `reads scheduler actual count and does not mistake unavailable for one`() {
        assertEquals(1, FoliaRuntimeDiagnostics.readSchedulerWorkerCount(Scheduler(1)))
        assertEquals(8, FoliaRuntimeDiagnostics.readSchedulerWorkerCount(Scheduler(8)))
        assertNull(FoliaRuntimeDiagnostics.readSchedulerWorkerCount(Scheduler(0)))
        assertNull(FoliaRuntimeDiagnostics.readSchedulerWorkerCount(Any()))
    }

    @Test
    fun `warns for one worker and distinguishes diagnostic failure`() {
        val messages = mutableListOf<String>()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) { messages.add(record.message) }
                override fun flush() {}
                override fun close() {}
            })
        }
        FoliaRuntimeDiagnostics.warnIfSingleWorker(logger) { 1 }
        assertTrue(messages.single().contains("only one tick worker"))
        messages.clear()
        FoliaRuntimeDiagnostics.warnIfSingleWorker(logger) { 4 }
        assertTrue(messages.isEmpty())
        FoliaRuntimeDiagnostics.warnIfSingleWorker(logger) { throw NoSuchMethodException() }
        assertTrue(messages.single().contains("Could not determine"))
    }
}
