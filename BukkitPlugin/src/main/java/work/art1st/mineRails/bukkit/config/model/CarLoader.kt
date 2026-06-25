package work.art1st.mineRails.bukkit.config.model

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Loads car definitions from the cars/ directory tree.
 *
 * Each car YAML references parts from the PartRegistry for
 * body-parts and bogie parts/wheel-parts.
 */
class CarLoader(
    private val carsDir: File,
    private val partRegistry: PartRegistry
) {
    private val cars: MutableMap<String, CarDefinition> = mutableMapOf()

    fun load() {
        cars.clear()
        if (carsDir.exists() && carsDir.isDirectory) {
            scanDirectory(carsDir, "")
        }
    }

    private fun scanDirectory(dir: File, namespace: String) {
        for (file in dir.listFiles() ?: emptyArray()) {
            when {
                file.isDirectory -> {
                    val childNs = if (namespace.isEmpty()) file.name else "$namespace/${file.name}"
                    scanDirectory(file, childNs)
                }
                file.isFile && (file.extension == "yml" || file.extension == "yaml") -> {
                    try {
                        val car = loadCarFile(file, namespace)
                        cars[car.name] = car
                    } catch (e: Exception) {
                        System.err.println("[MineRails] Failed to load car file ${file.path}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun loadCarFile(file: File, namespace: String): CarDefinition {
        val yaml = Yaml()
        val map = yaml.load<Map<String, Any>>(file.inputStream())

        val version = (map["config-version"] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing config-version")
        require(version == PartRegistry.CONFIG_VERSION) { "Unsupported config-version: $version" }

        val simpleName = map["name"] as? String
            ?: throw IllegalArgumentException("Missing 'name'")
        val qualifiedName = if (namespace.isEmpty()) simpleName else "$namespace/$simpleName"

        val bodyParts = PartRegistry.parsePartList(map["body-parts"] as? List<*>)
        val bogies = parseBogies(map["bogies"] as? List<*>)
        val seats = parseSeats(map["seats"] as? Map<String, Any>)

        return CarDefinition(
            name = qualifiedName,
            bodyParts = bodyParts,
            bogies = bogies,
            seats = seats
        )
    }

    private fun parseSeats(map: Map<String, Any>?): SeatConfig? {
        if (map == null) return null
        val interactionMap = map["interaction"] as? Map<String, Any>
        return SeatConfig(
            heightOffset = (map["height-offset"] as? Number)?.toDouble() ?: 0.8,
            baseOffsetRatio = (map["base-offset-ratio"] as? Number)?.toDouble() ?: -0.25,
            spacing = (map["spacing"] as? Number)?.toDouble() ?: 0.6,
            interaction = SeatInteractionConfig(
                width = (interactionMap?.get("width") as? Number)?.toDouble() ?: 0.5,
                height = (interactionMap?.get("height") as? Number)?.toDouble() ?: 1.0
            )
        )
    }

    private fun parseBogies(list: List<*>?): List<BogieDefinition> {
        if (list == null) return emptyList()
        return list.mapNotNull { entry ->
            val entryMap = entry as? Map<String, Any> ?: return@mapNotNull null
            BogieDefinition(
                offset = PartRegistry.parseVec3(entryMap["offset"] as? Map<String, Any>),
                parts = PartRegistry.parsePartList(entryMap["parts"] as? List<*>),
                wheelParts = PartRegistry.parsePartList(entryMap["wheel-parts"] as? List<*>),
                wheelDiameter = (entryMap["wheel-diameter"] as? Number)?.toDouble() ?: 1.0
            )
        }
    }

    /** Get all loaded car names. */
    fun getAllNames(): Set<String> = cars.keys.toSet()

    /** Get a car definition by fully qualified name. */
    operator fun get(name: String): CarDefinition? = cars[name]
}
