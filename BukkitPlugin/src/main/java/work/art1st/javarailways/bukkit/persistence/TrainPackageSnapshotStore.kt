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

    fun snapshotFile(hash: String): File = snapshotFile(BukkitPlugin.INSTANCE.dataFolder, hash)

    internal fun snapshotFile(dataFolder: File, hash: String): File =
        File(File(dataFolder, "train-package-snapshots"), "$hash.json")

    fun save(snapshot: SavedTrainPackageSnapshot): String {
        val file = snapshotFile(snapshot.hash)
        file.parentFile.mkdirs()
        if (!file.exists()) file.writeText(json.encodeToString(snapshot))
        return snapshot.hash
    }

    fun load(hash: String): SavedTrainPackageSnapshot? = load(BukkitPlugin.INSTANCE.dataFolder, hash) {
        BukkitPlugin.INSTANCE.logger.warning(it)
    }

    internal fun load(dataFolder: File, hash: String, reportFailure: (String) -> Unit): SavedTrainPackageSnapshot? {
        val file = snapshotFile(dataFolder, hash)
        if (!file.isFile || !file.canRead()) return null
        return try {
            json.decodeFromString<SavedTrainPackageSnapshot>(file.readText()).also {
                require(it.schemaVersion == SavedTrainPackageSnapshot.SCHEMA_VERSION) {
                    "Unsupported train package snapshot schema ${it.schemaVersion}"
                }
                require(it.hash == hash) { "Train package snapshot hash does not match file name" }
            }
        } catch (failure: Exception) {
            reportFailure("Failed to load train package snapshot $hash: ${failure.message}")
            null
        }
    }
}
