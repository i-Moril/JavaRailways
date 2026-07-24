package work.art1st.mineRails.bukkit.http

import work.art1st.mineRails.basic.persistence.SavedTimetableV1
import work.art1st.mineRails.basic.persistence.TimetableSnapshotCodec
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class TimetableScriptFailure(message: String) : IllegalArgumentException(message)
data class TimetableScriptDescriptor(val name: String, val sizeBytes: Long, val modifiedAtEpochMillis: Long)
/** Launches one memory-limited GraalJS worker process for every compilation. */
class TimetableScriptSandbox(private val plugin: BukkitPlugin) : AutoCloseable {
    private val dataDirectory = plugin.dataFolder.toPath().toAbsolutePath()
    private val scriptsDirectory = dataDirectory.resolve("timetables")
    private val runtimeDirectory = dataDirectory.resolve("timetable-js")
    private val workerJar = runtimeDirectory.resolve("MineRails-ScriptWorker.jar")
    private val permits = Semaphore(2)
    private val streamExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "MineRails-script-worker-io").apply { isDaemon = true }
    }
    private val extractionLock = Any()
    private val processes = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    @Volatile private var workerExtracted = false

    init {
        Files.createDirectories(scriptsDirectory)
        Files.createDirectories(runtimeDirectory)
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
        CompletableFuture.supplyAsync({ compileSource(readSource(scriptName), timetableId) }, streamExecutor)

    /** Compiles without touching world state; validation must be performed in simulation context. */
    fun compileSource(source: String, timetableId: String): SavedTimetableV1 {
        require(timetableId.isNotBlank()) { "timetable id must not be blank" }
        if (source.toByteArray(StandardCharsets.UTF_8).size > MAX_SOURCE_BYTES) {
            throw TimetableScriptFailure("script exceeds $MAX_SOURCE_BYTES bytes")
        }
        if (!permits.tryAcquire()) throw TimetableScriptFailure("script worker capacity is busy; retry later")
        try {
            extractWorker()
            val processBuilder = ProcessBuilder(
                javaExecutable(),
                "-Xms16m",
                "-Xmx64m",
                "-XX:MaxMetaspaceSize=128m",
                "-XX:+ExitOnOutOfMemoryError",
                "-jar",
                workerJar.toString(),
                timetableId
            ).directory(runtimeDirectory.toFile())
            // Do not leak Bukkit process secrets (including external bridge tokens) to the worker.
            processBuilder.environment().clear()
            val process = processBuilder.start()
            processes += process
            val stdout = readLimited(process.inputStream, MAX_OUTPUT_BYTES)
            val stderr = readLimited(process.errorStream, MAX_ERROR_BYTES)
            try {
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(source) }
                if (!process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                    throw TimetableScriptFailure("script exceeded ${TIMEOUT_MILLIS}ms execution timeout")
                }
                val out = stdout.get(1, TimeUnit.SECONDS)
                val err = stderr.get(1, TimeUnit.SECONDS)
                if (out.size > MAX_OUTPUT_BYTES) throw TimetableScriptFailure("worker output exceeds limit")
                if (process.exitValue() != 0) {
                    val message = err.toString(StandardCharsets.UTF_8).trim().take(2_000)
                    throw TimetableScriptFailure(message.ifBlank { "script worker exited with ${process.exitValue()}" })
                }
                return runCatching { TimetableSnapshotCodec.decode(out.toString(StandardCharsets.UTF_8)) }
                    .getOrElse { throw TimetableScriptFailure("worker returned invalid timetable JSON: ${it.message}") }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                processes -= process
            }
        } finally {
            permits.release()
        }
    }

    override fun close() {
        processes.forEach(Process::destroyForcibly)
        processes.clear()
        streamExecutor.shutdownNow()
    }

    private fun resolveScript(scriptName: String): Path {
        require(scriptName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.js"))) { "invalid script name" }
        val resolved = scriptsDirectory.resolve(scriptName).normalize()
        require(resolved.parent == scriptsDirectory &&
            Files.isRegularFile(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(resolved)
        ) { "script not found: $scriptName" }
        return resolved
    }

    private fun extractWorker() = synchronized(extractionLock) {
        if (workerExtracted && Files.isRegularFile(workerJar)) return@synchronized
        val temporary = workerJar.resolveSibling(".${workerJar.fileName}.tmp")
        plugin.getResource(WORKER_RESOURCE)?.use { input ->
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
        } ?: throw IllegalStateException("bundled script worker is missing: $WORKER_RESOURCE")
        try {
            Files.move(temporary, workerJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, workerJar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        workerExtracted = true
    }

    private fun readLimited(stream: java.io.InputStream, limit: Int): CompletableFuture<ByteArray> =
        CompletableFuture.supplyAsync({ stream.use { it.readNBytes(limit + 1) } }, streamExecutor)

    private fun javaExecutable(): String = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    ).toString()

    private companion object {
        const val WORKER_RESOURCE = "timetable-js/MineRails-ScriptWorker.jar"
        const val MAX_SOURCE_BYTES = 512 * 1024
        const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
        const val MAX_ERROR_BYTES = 64 * 1024
        const val TIMEOUT_MILLIS = 3_000L
    }
}
