package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        val temporary = Files.createTempFile(file.parentFile.toPath(), snapshot.hash, ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(snapshot))
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
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
                require(it.hash == hash && SavedTrainPackageSnapshot.build(it.packageName, it.actions,
                    it.behavior, it.control, it.carControls, it.displays).hash == hash) {
                    "Train package snapshot hash does not match its contents"
                }
            }
        } catch (failure: Exception) {
            reportFailure("Failed to load train package snapshot $hash: ${failure.message}")
            null
        }
    }
}
