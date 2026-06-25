package work.art1st.mineRails.bukkit.config.model

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Loads train definitions from the trains/ directory.
 *
 * Each train YAML lists the cars that make up the train in order.
 */
class TrainLoader(
    private val trainsDir: File,
    private val carLoader: CarLoader
) {
    private val trains: MutableMap<String, TrainDefinition> = mutableMapOf()

    fun load() {
        trains.clear()
        if (trainsDir.exists() && trainsDir.isDirectory) {
            for (file in trainsDir.listFiles() ?: emptyArray()) {
                if (file.isFile && (file.extension == "yml" || file.extension == "yaml")) {
                    try {
                        val train = loadTrainFile(file)
                        trains[train.key] = train
                    } catch (e: Exception) {
                        System.err.println("[MineRails] Failed to load train file ${file.path}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun loadTrainFile(file: File): TrainDefinition {
        val yaml = Yaml()
        val map = yaml.load<Map<String, Any>>(file.inputStream())

        val version = (map["config-version"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing config-version")
        require(version == PartRegistry.CONFIG_VERSION) { "Unsupported config-version: $version" }

        // Key = filename without extension (e.g. "s100_4car")
        val key = file.nameWithoutExtension
        val displayName = map["name"] as? String ?: key

        val composition = (map["composition"] as? List<*>)?.mapNotNull { it as? String }
            ?: throw IllegalArgumentException("Missing or invalid 'composition'")

        // Validate that all referenced cars exist
        for (carName in composition) {
            require(carLoader[carName] != null) {
                "Train '$key' references unknown car '$carName'"
            }
        }

        return TrainDefinition(
            name = displayName,
            key = key,
            composition = composition
        )
    }

    /** Get all loaded train names. */
    fun getAllNames(): Set<String> = trains.keys.toSet()

    /** Get a train definition by name. */
    operator fun get(name: String): TrainDefinition? = trains[name]
}
