package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.simpleato.persistence.SavedTimetableV1
import work.art1st.javarailways.simpleato.persistence.TimetableSnapshotCodec
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.scriptworker.ScriptExecution
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class TimetableScriptFailure(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
class TimetableScriptConflict(message: String) : IllegalStateException(message)
data class TimetableScriptDescriptor(val name: String, val sizeBytes: Long, val modifiedAtEpochMillis: Long)

/** Compiles and stores timetable JavaScript inside the plugin JVM. */
class TimetableScriptSandbox private constructor(
    private val scriptsDirectory: Path,
    private val executionTimeoutMillis: Long
) : AutoCloseable {
    constructor(plugin: BukkitPlugin) : this(plugin.dataFolder.toPath().toAbsolutePath().resolve("timetables"), 60_000)
    internal constructor(scriptsDirectory: Path, executionTimeoutMillis: Long = 60_000, testOnly: Unit = Unit) :
        this(scriptsDirectory.toAbsolutePath(), executionTimeoutMillis)

    private val storageLock = Any()
    private val lifecycleLock = Any()
    private var closed = false
    private val activeExecutions = mutableSetOf<ScriptExecution>()
    private val deadlineExecutor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "JavaRailways-timetable-js-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val permits = Semaphore(2)
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "JavaRailways-timetable-js").apply { isDaemon = true }
    }

    init {
        require(executionTimeoutMillis > 0) { "execution timeout must be positive" }
        Files.createDirectories(scriptsDirectory)
    }

    fun listScripts(): List<TimetableScriptDescriptor> = Files.list(scriptsDirectory).use { entries ->
        entries.filter {
            Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(it) && it.fileName.toString().endsWith(".js", ignoreCase = true)
        }
            .map { path ->
                TimetableScriptDescriptor(
                    path.fileName.toString(),
                    Files.size(path),
                    Files.getLastModifiedTime(path).toMillis()
                )
            }
            .sorted(compareBy(TimetableScriptDescriptor::name))
            .toList()
    }

    fun readSource(scriptName: String): String {
        val path = resolveScript(scriptName)
        val size = Files.size(path)
        if (size > MAX_SOURCE_BYTES) throw TimetableScriptFailure("script exceeds $MAX_SOURCE_BYTES bytes")
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    fun compileScriptAsync(scriptName: String, timetableId: String): CompletableFuture<SavedTimetableV1> =
        CompletableFuture.supplyAsync({ compileSource(readSource(scriptName), timetableId) }, executor)

    /** Returns true when an existing file was replaced. */
    fun saveSource(scriptName: String, source: String, overwrite: Boolean): Boolean = synchronized(storageLock) {
        val target = scriptPath(scriptName)
        val existed = Files.exists(target)
        if (existed && !overwrite) throw TimetableScriptConflict("script already exists: $scriptName")
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n') + "\n"
        val bytes = normalized.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_SOURCE_BYTES) throw TimetableScriptFailure("script exceeds $MAX_SOURCE_BYTES bytes")
        val temporary = scriptsDirectory.resolve(".$scriptName.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, bytes)
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        existed
    }

    fun renameSource(scriptName: String, targetName: String) = synchronized(storageLock) {
        val source = existingScriptPath(scriptName)
        val target = scriptPath(targetName)
        if (Files.exists(target)) throw TimetableScriptConflict("script already exists: $targetName")
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
        Unit
    }

    fun deleteSource(scriptName: String): Boolean = synchronized(storageLock) {
        Files.deleteIfExists(scriptPath(scriptName))
    }

    /** Compiles without touching world state; validation must be performed in simulation context. */
    fun compileSource(source: String, timetableId: String): SavedTimetableV1 {
        require(timetableId.isNotBlank()) { "timetable id must not be blank" }
        if (source.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES) {
            throw TimetableScriptFailure("script exceeds $MAX_SOURCE_BYTES bytes")
        }
        val execution = ScriptExecution()
        val deadline = synchronized(lifecycleLock) {
            if (closed) throw TimetableScriptFailure("script compiler is closed")
            if (!permits.tryAcquire()) throw TimetableScriptFailure("script compiler capacity is busy; retry later")
            activeExecutions.add(execution)
            deadlineExecutor.schedule(
                { execution.cancel("script execution timed out after $executionTimeoutMillis ms") },
                executionTimeoutMillis,
                TimeUnit.MILLISECONDS
            )
        }
        try {
            val output = try {
                execution.execute(source, timetableId)
            } catch (error: Throwable) {
                throw TimetableScriptFailure(error.message ?: "JavaScript compilation failed", error)
            }
            if (output.toByteArray(StandardCharsets.UTF_8).size > MAX_OUTPUT_BYTES) {
                throw TimetableScriptFailure("script output exceeds limit")
            }
            return runCatching { TimetableSnapshotCodec.decode(output) }
                .getOrElse { throw TimetableScriptFailure("compiler returned invalid timetable JSON: ${it.message}", it) }
        } finally {
            deadline.cancel(false)
            synchronized(lifecycleLock) { activeExecutions.remove(execution) }
            permits.release()
        }
    }

    override fun close() {
        val active = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeExecutions.toList()
        }
        var failure: Throwable? = null
        try {
            active.forEach { execution ->
                try {
                    execution.cancel("script compiler is closed")
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
        } finally {
            deadlineExecutor.shutdownNow()
            executor.shutdownNow()
        }
        failure?.let { throw it }
    }

    private fun resolveScript(scriptName: String): Path = existingScriptPath(scriptName)

    private fun scriptPath(scriptName: String): Path {
        require(scriptName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,123}\\.js"))) { "invalid script name" }
        val resolved = scriptsDirectory.resolve(scriptName).normalize()
        require(resolved.parent == scriptsDirectory) { "invalid script name" }
        return resolved
    }

    private fun existingScriptPath(scriptName: String): Path {
        val resolved = scriptPath(scriptName)
        require(Files.isRegularFile(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(resolved)) {
            "script not found: $scriptName"
        }
        return resolved
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 512 * 1024
        const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
    }
}
