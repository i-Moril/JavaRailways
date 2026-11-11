package work.art1st.javarailways.bukkit.persistence

import org.bukkit.World
import work.art1st.javarailways.bukkit.BukkitPlugin
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 将分离的世界快照保存到插件数据目录下，按 Bukkit 世界 [World.getUID] 区分文件。
 */
object WorldPersistence {
    sealed interface SplitSnapshotLoadResult {
        data object Missing : SplitSnapshotLoadResult
        data class Loaded(val snapshots: CapturedWorldSnapshots, val highestRevision: Long) : SplitSnapshotLoadResult
        data class Corrupted(val files: List<File>, val error: Throwable) : SplitSnapshotLoadResult
    }

    private fun worldsDir(): File = File(BukkitPlugin.INSTANCE.dataFolder, "worlds").also { it.mkdirs() }

    fun infrastructureFile(world: World): File = infrastructureFile(world.uid)
    fun infrastructureFiles(): List<File> = worldsDir().listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith("-infrastructure.json") }
        .sortedBy(File::getName)
    fun runtimeFile(world: World): File = runtimeFile(world.uid)
    fun infrastructureFile(worldId: UUID): File = File(worldsDir(), "$worldId-infrastructure.json")
    fun runtimeFile(worldId: UUID): File = File(worldsDir(), "$worldId-runtime.json")

    fun loadSplitSnapshots(world: World): SplitSnapshotLoadResult {
        return loadSplitSnapshots(infrastructureFile(world), runtimeFile(world), BukkitPlugin.INSTANCE.logger)
    }

    internal fun loadSplitSnapshots(
        infrastructure: File,
        runtime: File,
        logger: Logger
    ): SplitSnapshotLoadResult {
        if (!infrastructure.exists() && !runtime.exists()) return SplitSnapshotLoadResult.Missing
        if (!infrastructure.isFile || !runtime.isFile) {
            return SplitSnapshotLoadResult.Corrupted(
                listOf(infrastructure, runtime),
                IllegalStateException("both split snapshot files must exist")
            )
        }
        return try {
            val failures = mutableListOf<Pair<File, Exception>>()
            val infrastructureCandidates = decodeCandidates(
                listOf(infrastructure, backupFile(infrastructure)),
                SplitWorldSnapshotCodec::decodeInfrastructure,
                failures
            )
            val runtimeCandidates = decodeCandidates(
                listOf(runtime, backupFile(runtime)),
                SplitWorldSnapshotCodec::decodeRuntime,
                failures
            )
            val selected = infrastructureCandidates.flatMap { infra ->
                runtimeCandidates.mapNotNull { runtimeCandidate ->
                    if (infra.value.infrastructureRevision == runtimeCandidate.value.infrastructureRevision) {
                        SnapshotCandidatePair(
                            CapturedWorldSnapshots(infra.value, runtimeCandidate.value),
                            infra.file,
                            runtimeCandidate.file
                        )
                    } else null
                }
            }.maxByOrNull { it.snapshots.infrastructure.infrastructureRevision }

            val pair = selected ?: throw noMatchingRevisionFailure(
                infrastructureCandidates,
                runtimeCandidates,
                failures
            )
            if (pair.infrastructureFile != infrastructure || pair.runtimeFile != runtime) {
                logger.warning(
                    "Recovered world ${infrastructure.name} snapshots from backup files: " +
                        "infrastructure=${pair.infrastructureFile.name}, runtime=${pair.runtimeFile.name}"
                )
            }
            failures.forEach { (file, failure) ->
                logger.log(
                    Level.WARNING,
                    "Ignored invalid snapshot candidate ${file.path} while restoring world ${infrastructure.name}",
                    failure
                )
            }
            pair.snapshots.infrastructure.core()
            pair.snapshots.infrastructure.basic()
            pair.snapshots.infrastructure.simpleAto()
            pair.snapshots.runtime.core()
            pair.snapshots.runtime.basic()
            pair.snapshots.runtime.simpleAto()
            SplitSnapshotLoadResult.Loaded(
                pair.snapshots,
                maxOf(
                    infrastructureCandidates.maxOf { it.value.infrastructureRevision },
                    runtimeCandidates.maxOf { it.value.infrastructureRevision }
                )
            )
        } catch (failure: Exception) {
            SplitSnapshotLoadResult.Corrupted(listOf(infrastructure, runtime), failure)
        }
    }

    private data class DecodedCandidate<T>(val file: File, val value: T)

    private data class SnapshotCandidatePair(
        val snapshots: CapturedWorldSnapshots,
        val infrastructureFile: File,
        val runtimeFile: File
    )

    private fun <T> decodeCandidates(
        files: List<File>,
        decode: (String) -> T,
        failures: MutableList<Pair<File, Exception>>
    ): List<DecodedCandidate<T>> = files.filter(File::isFile).mapNotNull { file ->
        try {
            DecodedCandidate(file, decode(file.readText()))
        } catch (failure: Exception) {
            failures += file to failure
            null
        }
    }

    private fun noMatchingRevisionFailure(
        infrastructureCandidates: List<DecodedCandidate<InfrastructureFileV2>>,
        runtimeCandidates: List<DecodedCandidate<RuntimeFileV2>>,
        failures: List<Pair<File, Exception>>
    ): IllegalStateException {
        val infrastructureRevisions = infrastructureCandidates.map { it.value.infrastructureRevision }
        val runtimeRevisions = runtimeCandidates.map { it.value.infrastructureRevision }
        return IllegalStateException(
            "No matching infrastructure/runtime snapshot revision; " +
                "infrastructure revisions=$infrastructureRevisions, runtime revisions=$runtimeRevisions"
        ).also { aggregate ->
            failures.forEach { (file, failure) ->
                aggregate.addSuppressed(
                    IllegalStateException("Failed to decode snapshot candidate ${file.path}", failure)
                )
            }
        }
    }

    fun saveSplitSnapshots(world: World, capture: WorldSnapshotCapture) {
        saveSplitSnapshots(world.uid, capture)
    }

    fun saveSplitSnapshots(worldId: UUID, capture: WorldSnapshotCapture) {
        saveSplitSnapshots(worldId, capture.encode())
    }

    fun saveSplitSnapshots(worldId: UUID, snapshots: CapturedWorldSnapshots) {
        saveSplitSnapshots(infrastructureFile(worldId), runtimeFile(worldId), snapshots)
    }

    internal fun saveSplitSnapshots(infrastructure: File, runtime: File, snapshots: CapturedWorldSnapshots) {
        writeAtomic(infrastructure, SplitWorldSnapshotCodec.encode(snapshots.infrastructure))
        writeAtomic(runtime, SplitWorldSnapshotCodec.encode(snapshots.runtime))
    }

    private fun backupFile(target: File): File = File(target.parentFile, "${target.name}.bak")

    private fun writeAtomic(target: File, contents: String) {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(contents)
        if (target.isFile) {
            java.nio.file.Files.copy(
                target.toPath(),
                backupFile(target).toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        try {
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
