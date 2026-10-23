package work.art1st.javarailways.bukkit.api.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import work.art1st.javarailways.diagnostics.PluginErrorLog

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class PluginErrorLogTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `captures module errors and original causes while queued reporting drains console and file exactly once`() {
        val logger = logger()
        val core = logger()
        val console = CopyOnWriteArrayList<LogRecord>()
        val workerEntered = CountDownLatch(1)
        val continueWorker = CountDownLatch(1)
        val callbackThread = CopyOnWriteArrayList<Thread>()
        logger.addHandler(handler { record ->
            console += record
            if (record.message == "rule failed") {
                callbackThread += Thread.currentThread()
                workerEntered.countDown()
                check(continueWorker.await(5, TimeUnit.SECONDS))
            }
        })
        val failure = IllegalStateException("original", IllegalArgumentException("cause"))
        failure.addSuppressed(IllegalStateException("suppressed"))
        val path = directory.resolve("logs/errors.log")
        val log = PluginErrorLog(path, logger, "Bukkit", mapOf(core to "Core"))
        try {
            log.reportFailure("rule failed", failure)
            assertTrue(workerEntered.await(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(path), "the caller does not create directories or append before the worker")
            assertNotEquals(Thread.currentThread(), callbackThread.single())
            core.log(LogRecord(Level.SEVERE, "event failed").apply {
                sourceClassName = "work.art1st.javarailways.core.api.event.processor.TypedRailEventBus"
                thrown = failure
            })
            logger.info("ordinary message")
            logger.warning("platform warning")
        } finally {
            continueWorker.countDown()
            log.close()
        }
        val text = Files.readString(path)
        assertEquals(1, Regex("rule failed").findAll(text).count())
        assertEquals(1, console.count { it.message == "rule failed" })
        assertSame(failure, console.single { it.message == "rule failed" }.thrown)
        assertTrue(text.contains("module=Core"))
        assertTrue(text.contains("module=Bukkit"))
        assertTrue(text.contains("Caused by: java.lang.IllegalArgumentException: cause"))
        assertTrue(text.contains("Suppressed: java.lang.IllegalStateException: suppressed"))
        assertFalse(text.contains("ordinary message"))
        val before = Files.readString(path)
        log.close()
        logger.severe("after close")
        log.reportFailure("after close callback", failure)
        assertEquals(before, Files.readString(path))
        assertEquals(1, logger.handlers.size, "close detaches only its own handler")
    }

    @Test
    fun `queue saturation stays nonblocking and reports exact loss when worker drains on close`() {
        val logger = logger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val console = CopyOnWriteArrayList<LogRecord>()
        logger.addHandler(handler { record ->
            console += record
            if (record.message == "blocked worker") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        val path = directory.resolve("logs/errors.log")
        val log = PluginErrorLog(path, logger, "Bukkit", maximumBytes = 64L * 1024L * 1024L)
        try {
            log.reportFailure("blocked worker", IllegalStateException("original"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // One running task, exactly 1024 queued tasks, then observable overflow.
            repeat(1034) { index -> log.reportFailure("queued-$index", IllegalStateException("failure")) }
            assertFalse(Files.exists(path))
            assertEquals(1, console.size, "producer never falls back to synchronous console work")
        } finally {
            release.countDown()
            log.close()
        }
        val text = Files.readString(path)
        assertEquals(1024, Regex("queued-\\d+").findAll(text).count())
        assertTrue(text.contains("queue overflow: 10 records were not retained"))
        assertEquals(1, console.count { it.message.contains("queue overflow: 10 records") })
    }

    @Test
    fun `rotates only one previous file and appends existing data on reopen`() {
        val logger = logger()
        val path = directory.resolve("logs/errors.log")
        PluginErrorLog(path, logger, "SimpleSignals", maximumBytes = 1).use {
            logger.warning("first warning")
            logger.severe("second failure")
        }
        assertTrue(Files.readString(path).contains("second failure"))
        assertTrue(Files.readString(path.resolveSibling("errors.log.1")).contains("first warning"))
        PluginErrorLog(path, logger, "SimpleSignals", maximumBytes = 1).use {
            logger.warning("third warning")
        }
        assertTrue(Files.readString(path).contains("third warning"))
        val previous = Files.readString(path.resolveSibling("errors.log.1"))
        assertTrue(previous.contains("second failure"))
        assertFalse(previous.contains("first warning"))
    }

    @Test
    fun `file failures reach console once without recursive logging or changing caller outcome`() {
        val parent = logger()
        val logger = logger().apply { this.parent = parent; useParentHandlers = true }
        val console = CopyOnWriteArrayList<LogRecord>()
        parent.addHandler(handler { console += it })
        val notDirectory = directory.resolve("not-directory")
        Files.writeString(notDirectory, "keep")
        PluginErrorLog(notDirectory.resolve("errors.log"), logger, "SimpleSignals").use {
            logger.warning("original warning")
        }
        assertEquals("keep", Files.readString(notDirectory))
        assertEquals(1, console.count { it.message == "original warning" })
        val fileFailure = console.single { it.message.startsWith("Failed to write plugin error log") }
        assertNotNull(fileFailure.thrown)
        assertTrue(logger.handlers.isEmpty())
    }

    private fun logger(): Logger = Logger.getLogger("test.error-log.${UUID.randomUUID()}").apply {
        useParentHandlers = false
        level = Level.ALL
    }

    private fun handler(consume: (LogRecord) -> Unit): Handler = object : Handler() {
        override fun publish(record: LogRecord) = consume(record)
        override fun flush() = Unit
        override fun close() = Unit
    }
}
