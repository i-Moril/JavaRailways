package work.art1st.mineRails.bukkit.persistence

import org.bukkit.World
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.core.persistence.WorldSnapshotCodec
import work.art1st.mineRails.core.physics.CoreWorld
import java.io.File

/**
 * 将 [CoreWorld] 快照保存到插件数据目录下，按 Bukkit 世界 [World.getUID] 区分文件。
 */
object WorldPersistence {
    private fun worldsDir(): File = File(BukkitPlugin.dataFolder, "worlds").also { it.mkdirs() }

    fun snapshotFile(world: World): File = File(worldsDir(), "${world.uid}.json")

    fun tryLoadSnapshot(world: World): CoreWorld.SavedWorldV1? {
        val f = snapshotFile(world)
        if (!f.exists() || !f.canRead()) return null
        return try {
            WorldSnapshotCodec.decodeFromString(f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun saveSnapshot(world: World, core: CoreWorld) {
        val f = snapshotFile(world)
        f.parentFile.mkdirs()
        f.writeText(WorldSnapshotCodec.encodeToString(core.save()))
    }
}
