package work.art1st.mineRails.bukkit.persistence

import org.bukkit.World
import work.art1st.mineRails.bukkit.BukkitPlugin
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 将分离的世界快照保存到插件数据目录下，按 Bukkit 世界 [World.getUID] 区分文件。
 */
object WorldPersistence {
    sealed interface SplitSnapshotLoadResult {
        data object Missing : SplitSnapshotLoadResult
        data class Loaded(val snapshots: CapturedWorldSnapshots) : SplitSnapshotLoadResult
        data class Corrupted(val files: List<File>, val error: Throwable) : SplitSnapshotLoadResult
    }

    private fun worldsDir(): File = File(BukkitPlugin.INSTANCE.dataFolder, "worlds").also { it.mkdirs() }

    fun infrastructureFile(world: World): File = infrastructureFile(world.uid)
    fun runtimeFile(world: World): File = runtimeFile(world.uid)
    fun infrastructureFile(worldId: UUID): File = File(worldsDir(), "$worldId-infrastructure.json")
    fun runtimeFile(worldId: UUID): File = File(worldsDir(), "$worldId-runtime.json")

    fun loadSplitSnapshots(world: World): SplitSnapshotLoadResult {
        val infrastructure = infrastructureFile(world)
        val runtime = runtimeFile(world)
        if (!infrastructure.exists() && !runtime.exists()) return SplitSnapshotLoadResult.Missing
        if (!infrastructure.isFile || !runtime.isFile) {
            return SplitSnapshotLoadResult.Corrupted(
                listOf(infrastructure, runtime),
                IllegalStateException("both split snapshot files must exist")
            )
        }
        return try {
            val infrastructureCandidates = listOf(infrastructure, backupFile(infrastructure))
                .filter(File::isFile)
                .mapNotNull { file -> runCatching {
                    SplitWorldSnapshotCodec.decodeInfrastructure(file.readText())
                }.getOrNull() }
            val runtimeCandidates = listOf(runtime, backupFile(runtime))
                .filter(File::isFile)
                .mapNotNull { file -> runCatching {
                    SplitWorldSnapshotCodec.decodeRuntime(file.readText())
                }.getOrNull() }
            val pair = infrastructureCandidates.flatMap { infra ->
                runtimeCandidates.mapNotNull { run ->
                    if (infra.infrastructureRevision == run.infrastructureRevision) {
                        CapturedWorldSnapshots(infra, run)
                    } else null
                }
            }.maxByOrNull { it.infrastructure.infrastructureRevision }
                ?: error("no matching infrastructure/runtime snapshot revision")
            pair.infrastructure.core()
            pair.infrastructure.basic()
            pair.runtime.core()
            pair.runtime.basic()
            SplitSnapshotLoadResult.Loaded(pair)
        } catch (failure: Throwable) {
            SplitSnapshotLoadResult.Corrupted(listOf(infrastructure, runtime), failure)
        }
    }

    fun saveSplitSnapshots(world: World, capture: WorldSnapshotCapture) {
        saveSplitSnapshots(world.uid, capture)
    }

    fun saveSplitSnapshots(worldId: UUID, capture: WorldSnapshotCapture) {
        saveSplitSnapshots(worldId, capture.encode())
    }

    fun saveSplitSnapshots(worldId: UUID, snapshots: CapturedWorldSnapshots) {
        writeAtomic(infrastructureFile(worldId), SplitWorldSnapshotCodec.encode(snapshots.infrastructure))
        writeAtomic(runtimeFile(worldId), SplitWorldSnapshotCodec.encode(snapshots.runtime))
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
