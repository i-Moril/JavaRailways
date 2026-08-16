package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot
import java.io.File

object TrainPackageSnapshotStore {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    private fun snapshotsDir(): File = File(BukkitPlugin.INSTANCE.dataFolder, "train-package-snapshots").also { it.mkdirs() }
    fun snapshotFile(hash: String): File = File(snapshotsDir(), "$hash.json")

    fun save(snapshot: SavedTrainPackageSnapshot): String {
        val file = snapshotFile(snapshot.hash)
        if (!file.exists()) file.writeText(json.encodeToString(snapshot))
        return snapshot.hash
    }

    fun load(hash: String): SavedTrainPackageSnapshot? {
        val file = snapshotFile(hash)
        if (!file.isFile || !file.canRead()) return null
        return try {
            json.decodeFromString<SavedTrainPackageSnapshot>(file.readText()).also {
                require(it.schemaVersion == SavedTrainPackageSnapshot.SCHEMA_VERSION) {
                    "Unsupported train package snapshot schema ${it.schemaVersion}"
                }
                require(it.hash == hash) { "Train package snapshot hash does not match file name" }
            }
        } catch (failure: Exception) {
            BukkitPlugin.INSTANCE.logger.warning("Failed to load train package snapshot $hash: ${failure.message}")
            null
        }
    }
}
