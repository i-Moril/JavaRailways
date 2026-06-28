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

        val length = (map["length"] as? Number)?.toDouble() ?: 2.0
        val floorHeight = (map["floor-height"] as? Number)?.toDouble() ?: 0.5
        val connectorLengthA = (map["connector-length-a"] as? Number)?.toDouble() ?: 0.3
        val connectorLengthB = (map["connector-length-b"] as? Number)?.toDouble() ?: 0.3
        val bodyParts = PartRegistry.parsePartList(map["body-parts"] as? List<*>, namespace)
        val bogies = parseBogies(map["bogies"] as? List<*>, namespace)

        // Extract physics bogie offsets from YAML bogie definitions (z component)
        val bogieAOffset = bogies.getOrNull(0)?.offset?.z
        val bogieBOffset = bogies.getOrNull(1)?.offset?.z

        return CarDefinition(
            name = qualifiedName,
            length = length,
            floorHeight = floorHeight,
            bodyParts = bodyParts,
            bogies = bogies,
            connectorLengthA = connectorLengthA,
            connectorLengthB = connectorLengthB,
            bogieAOffset = bogieAOffset,
            bogieBOffset = bogieBOffset
        )
    }

    private fun parseBogies(list: List<*>?, namespace: String): List<BogieDefinition> {
        if (list == null) return emptyList()
        return list.mapNotNull { entry ->
            val entryMap = entry as? Map<String, Any> ?: return@mapNotNull null
            BogieDefinition(
                offset = PartRegistry.parseVec3(entryMap["offset"] as? Map<String, Any>),
                parts = PartRegistry.parsePartList(entryMap["parts"] as? List<*>, namespace),
                wheelParts = PartRegistry.parsePartList(entryMap["wheel-parts"] as? List<*>, namespace),
                wheelDiameter = (entryMap["wheel-diameter"] as? Number)?.toDouble() ?: 1.0
            )
        }
    }

    /** Get all loaded car names. */
    fun getAllNames(): Set<String> = cars.keys.toSet()

    /** Get a car definition by fully qualified name. */
    operator fun get(name: String): CarDefinition? = cars[name]
}
