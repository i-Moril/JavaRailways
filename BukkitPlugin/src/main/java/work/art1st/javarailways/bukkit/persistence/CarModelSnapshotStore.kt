package work.art1st.javarailways.bukkit.persistence

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.SavedCarModelTree
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object CarModelSnapshotStore {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    private fun snapshotsDir(): File = File(BukkitPlugin.INSTANCE.dataFolder, "model-snapshots").also { it.mkdirs() }

    fun snapshotFile(hash: String): File = File(snapshotsDir(), "$hash.json")

    fun save(snapshot: SavedCarModelTree): String {
        val file = snapshotFile(snapshot.hash)
        val temporary = Files.createTempFile(file.parentFile.toPath(), snapshot.hash, ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(snapshot))
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return snapshot.hash
    }

    fun load(hash: String): SavedCarModelTree? {
        val file = snapshotFile(hash)
        if (!file.exists() || !file.canRead()) return null
        return try {
            json.decodeFromString<SavedCarModelTree>(file.readText()).also {
                require(it.hash == hash && SavedCarModelTree.computeHash(it.car, it.parts) == hash) {
                    "Car model snapshot hash does not match its contents"
                }
            }
        } catch (e: Exception) {
            BukkitPlugin.INSTANCE.logger.warning("Failed to load car model snapshot $hash: ${e.message}")
            null
        }
    }
}
