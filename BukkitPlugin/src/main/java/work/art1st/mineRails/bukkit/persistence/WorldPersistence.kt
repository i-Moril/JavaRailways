package work.art1st.mineRails.bukkit.persistence

import org.bukkit.World
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.persistence.WorldSnapshotCodec
import work.art1st.mineRails.core.physics.CoreWorld
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

/**
 * 将 [CoreWorld] 快照保存到插件数据目录下，按 Bukkit 世界 [World.getUID] 区分文件。
 */
object WorldPersistence {
    sealed interface SnapshotLoadResult {
        data object Missing : SnapshotLoadResult
        data class Loaded(val snapshot: CoreWorld.SavedWorldV1) : SnapshotLoadResult
        data class Corrupted(val file: File, val error: Throwable) : SnapshotLoadResult
    }

    private fun worldsDir(): File = File(BukkitPlugin.INSTANCE.dataFolder, "worlds").also { it.mkdirs() }

    fun snapshotFile(world: World): File = File(worldsDir(), "${world.uid}.json")

    fun loadSnapshot(world: World): SnapshotLoadResult {
        val f = snapshotFile(world)
        if (!f.exists()) return SnapshotLoadResult.Missing
        if (!f.canRead()) {
            return SnapshotLoadResult.Corrupted(f, IllegalStateException("Snapshot file is not readable"))
        }
        return try {
            SnapshotLoadResult.Loaded(WorldSnapshotCodec.decodeFromString(f.readText()))
        } catch (t: Throwable) {
            SnapshotLoadResult.Corrupted(f, t)
        }
    }

    fun saveSnapshot(world: World, core: CoreWorld) {
        val target = snapshotFile(world)
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(WorldSnapshotCodec.encodeToString(core.save()))
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
