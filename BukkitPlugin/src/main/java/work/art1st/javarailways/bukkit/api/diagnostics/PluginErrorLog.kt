package work.art1st.javarailways.bukkit.api.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/**
 * One independent plugin's error file. Existing JUL/console handling is left intact.
 *
 * Publishing only captures a record and queues it; stack formatting, rotation and disk access are serial
 * worker operations. Close after the plugin's producers stop, on a shutdown worker/global thread,
 * never on a physics or owning region callback: it drains accepted records for up to five seconds.
 * Only reported WARNING/SEVERE records are captured, not arbitrary caught exceptions or stderr.
 */
class PluginErrorLog(
    private val path: Path,
    private val pluginLogger: Logger,
    private val defaultModule: String,
    moduleLoggers: Map<Logger, String> = emptyMap(),
    private val maximumBytes: Long = 5L * 1024L * 1024L
) : AutoCloseable {
    private data class Entry(
        val time: Instant,
        val level: String,
        val module: String,
        val logger: String?,
        val source: String?,
        val message: String,
        val failure: Throwable?
    )

    private val lock = Any()
    private var closed = false
    @Volatile private var writerThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "JavaRailways-$defaultModule-error-log").apply {
            isDaemon = true
            writerThread = this
        }
    }
    private val sources = moduleLoggers + (pluginLogger to defaultModule)
    private val messageFormatter = SimpleFormatter()
    private val handler = object : Handler() {
        init { level = Level.WARNING }

        override fun publish(record: LogRecord) {
            // reportFailure already owns the file append on this same serial worker.
            if (!isLoggable(record) || Thread.currentThread() === writerThread) return
            // JUL infers the caller lazily. Capture it before leaving the publishing call stack.
            val source = record.sourceClassName
            val module = sources.entries.firstOrNull {
                record.loggerName == it.key.name || record.loggerName?.startsWith("${it.key.name}.") == true
            }?.value
                ?.takeUnless { record.loggerName == pluginLogger.name }
                ?: sourceModule(source) ?: defaultModule
            val entry = Entry(record.instant, record.level.name, module, record.loggerName, source,
                messageFormatter.formatMessage(record), record.thrown)
            synchronized(lock) {
                if (!closed) worker.execute { append(entry) }
            }
        }

        override fun flush() = Unit
        override fun close() = Unit // Only PluginErrorLog owns the executor and registrations.
    }

    init {
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        sources.keys.forEach { it.addHandler(handler) }
    }

    /** Nonblocking failure reporting for simulation callbacks; both console and file use our worker. */
    fun reportFailure(message: String, failure: Throwable) {
        val entry = Entry(Instant.now(), Level.SEVERE.name, defaultModule, pluginLogger.name, null, message, failure)
        synchronized(lock) {
            if (!closed) worker.execute {
                // Use the original plugin logger for its normal console prefix/parent handling.
                // The handler's writer-thread check prevents a second append, even before close.
                try {
                    pluginLogger.log(Level.SEVERE, message, failure)
                } finally {
                    append(entry)
                }
            }
        }
    }

    private fun sourceModule(source: String?): String? = when {
        source == null -> null
        source.startsWith("work.art1st.javarailways.core.") -> "Core"
        source.startsWith("work.art1st.javarailways.basic.") -> "Basic"
        source.startsWith("work.art1st.javarailways.simpleato.") -> "SimpleATO"
        source.startsWith("work.art1st.javarailways.simpleatobukkit.") -> "SimpleATOBukkit"
        source.startsWith("work.art1st.javarailways.simplesignals.") -> "SimpleSignals"
        source.startsWith("work.art1st.javarailways.bukkit.") -> "Bukkit"
        else -> null
    }

    private fun append(entry: Entry) {
        try {
            Files.createDirectories(path.parent)
            if (Files.exists(path) && Files.size(path) >= maximumBytes) {
                Files.move(path, path.resolveSibling("${path.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            val text = buildString {
                append('[').append(entry.time).append("] ").append(entry.level)
                append(" module=").append(entry.module).append(" logger=").append(entry.logger ?: "unknown")
                entry.source?.let { append(" source=").append(it) }
                append('\n').append(entry.message).append('\n')
                entry.failure?.let { append(it.stackTraceToString()) }
                append('\n')
            }
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (failure: Exception) {
            reportWriteFailure("Failed to write plugin error log $path", failure)
        }
    }

    private fun reportWriteFailure(message: String, failure: Throwable? = null) {
        // Bypass this logger's handler; otherwise a full/read-only disk creates recursive work.
        (pluginLogger.parent ?: Logger.getLogger("")).log(Level.SEVERE, message, failure)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sources.keys.forEach { it.removeHandler(handler) }
            worker.shutdown()
        }
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                val pending = worker.shutdownNow().size
                reportWriteFailure("Plugin error log shutdown timed out; $pending queued records were not written: $path")
            }
        } catch (failure: InterruptedException) {
            val pending = worker.shutdownNow().size
            Thread.currentThread().interrupt()
            reportWriteFailure("Plugin error log shutdown interrupted; $pending queued records were not written: $path", failure)
        }
    }
}
