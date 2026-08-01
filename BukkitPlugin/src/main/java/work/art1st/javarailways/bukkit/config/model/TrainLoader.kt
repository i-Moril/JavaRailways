package work.art1st.javarailways.bukkit.config.model

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
        if (!trainsDir.exists() || !trainsDir.isDirectory) return

        val errors = mutableListOf<String>()
        for (file in trainsDir.listFiles() ?: emptyArray()) {
            if (file.isFile && (file.extension == "yml" || file.extension == "yaml")) {
                try {
                    val train = loadTrainFile(file)
                    trains[train.key] = train
                } catch (e: Exception) {
                    errors += "${file.path}: ${e.message ?: e::class.simpleName.orEmpty()}"
                }
            }
        }
        if (errors.isNotEmpty()) {
            throw IllegalStateException(buildString {
                append("Failed to load train definitions under ${trainsDir.path}:")
                errors.forEach { append("\n- ").append(it) }
            })
        }
    }

    private fun loadTrainFile(file: File): TrainDefinition {
        val map = loadYamlMap(file, "train definition ${file.path}")

        val version = (map["config-version"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing config-version")
        require(version == PartRegistry.CONFIG_VERSION) { "Unsupported config-version: $version" }

        // Key = filename without extension (e.g. "s100_4car")
        val key = file.nameWithoutExtension
        val displayName = map["name"] as? String ?: key

        val composition = map.optionalList("composition", "train definition ${file.path}")
            ?.mapIndexed { index, entry ->
                entry as? String
                    ?: throw IllegalArgumentException("train definition ${file.path}.composition[$index] must be a string")
            }
            ?: throw IllegalArgumentException("Missing required 'composition'")

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
