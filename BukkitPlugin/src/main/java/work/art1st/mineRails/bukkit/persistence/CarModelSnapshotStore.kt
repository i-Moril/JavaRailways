package work.art1st.mineRails.bukkit.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.model.SavedCarModelTree
import java.io.File

object CarModelSnapshotStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun snapshotsDir(): File = File(BukkitPlugin.INSTANCE.dataFolder, "model-snapshots").also { it.mkdirs() }

    fun snapshotFile(hash: String): File = File(snapshotsDir(), "$hash.json")

    fun save(snapshot: SavedCarModelTree): String {
        val file = snapshotFile(snapshot.hash)
        if (!file.exists()) {
            file.writeText(json.encodeToString(snapshot))
        }
        return snapshot.hash
    }

    fun load(hash: String): SavedCarModelTree? {
        val file = snapshotFile(hash)
        if (!file.exists() || !file.canRead()) return null
        return try {
            json.decodeFromString<SavedCarModelTree>(file.readText())
        } catch (e: Exception) {
            BukkitPlugin.INSTANCE.logger.warning("Failed to load car model snapshot $hash: ${e.message}")
            null
        }
    }
}
