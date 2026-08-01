package work.art1st.javarailways.bukkit.diagnostics

import work.art1st.javarailways.bukkit.BukkitPlugin
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/** Durable, low-volume diagnostics that must survive chat history and server-console rotation. */
object PluginDiagnosticLog {
    private const val MAX_BYTES = 5L * 1024L * 1024L
    private val lock = Any()

    fun timetable(event: String, details: String, failure: Throwable? = null): Path? =
        appendSafely("timetable.log", event, details, failure)

    fun command(event: String, details: String, failure: Throwable? = null): Path? =
        appendSafely("commands.log", event, details, failure)

    fun ato(event: String, details: String, failure: Throwable? = null): Path? =
        appendSafely("ato.log", event, details, failure)

    private fun appendSafely(
        fileName: String,
        event: String,
        details: String,
        failure: Throwable?
    ): Path? = runCatching { append(fileName, event, details, failure) }
        .onFailure {
            BukkitPlugin.INSTANCE.logger.warning(
                "Failed to write diagnostic log $fileName: ${it.message ?: it::class.simpleName}"
            )
        }
        .getOrNull()

    private fun append(
        fileName: String,
        event: String,
        details: String,
        failure: Throwable?
    ): Path = synchronized(lock) {
        val plugin = BukkitPlugin.INSTANCE
        val directory = plugin.dataFolder.toPath().resolve("logs")
        Files.createDirectories(directory)
        val path = directory.resolve(fileName)
        rotateIfNeeded(path)
        val record = buildString {
            append('[').append(Instant.now()).append("] ")
            append(event).append('\n')
            details.trimEnd().lines().forEach { append("  ").append(it).append('\n') }
            failure?.stackTraceToString()?.trimEnd()?.lines()?.forEach {
                append("  ").append(it).append('\n')
            }
        }
        Files.writeString(
            path,
            record,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
        path
    }

    private fun rotateIfNeeded(path: Path) {
        if (!Files.exists(path) || Files.size(path) < MAX_BYTES) return
        val previous = path.resolveSibling("${path.fileName}.1")
        Files.move(path, previous, StandardCopyOption.REPLACE_EXISTING)
    }
}
