package work.art1st.mineRails.bukkit.config.model

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
        if (!carsDir.exists() || !carsDir.isDirectory) return

        val errors = mutableListOf<String>()
        scanDirectory(carsDir, "", errors)
        if (errors.isNotEmpty()) {
            throw IllegalStateException(buildString {
                append("Failed to load car definitions under ${carsDir.path}:")
                errors.forEach { append("\n- ").append(it) }
            })
        }
    }

    private fun scanDirectory(dir: File, namespace: String, errors: MutableList<String>) {
        for (file in dir.listFiles() ?: emptyArray()) {
            when {
                file.isDirectory -> {
                    val childNs = if (namespace.isEmpty()) file.name else "$namespace/${file.name}"
                    scanDirectory(file, childNs, errors)
                }
                file.isFile && (file.extension == "yml" || file.extension == "yaml") -> {
                    try {
                        val car = loadCarFile(file, namespace)
                        cars[car.name] = car
                    } catch (e: Exception) {
                        errors += "${file.path}: ${e.message ?: e::class.simpleName.orEmpty()}"
                    }
                }
            }
        }
    }

    private fun loadCarFile(file: File, namespace: String): CarDefinition {
        val subject = "car definition ${file.path}"
        val map = loadYamlMap(file, subject)
        map.requireOnlyKeys(
            setOf(
                "config-version", "name", "length", "floor-height", "connector-length-a",
                "connector-length-b", "control", "body-parts", "bogies"
            ),
            subject
        )

        val version = map.requireInt("config-version", subject)
        require(version == PartRegistry.CONFIG_VERSION) { "Unsupported config-version: $version" }

        val simpleName = map.requireString("name", subject)
        require(simpleName.isNotBlank()) { "$subject.name must not be blank" }
        val qualifiedName = if (namespace.isEmpty()) simpleName else "$namespace/$simpleName"

        val length = map.requireDouble("length", "car definition ${file.path}")
        val floorHeight = map.requireDouble("floor-height", "car definition ${file.path}")
        val connectorLengthA = map.requireDouble("connector-length-a", "car definition ${file.path}")
        val connectorLengthB = map.requireDouble("connector-length-b", "car definition ${file.path}")
        val bodyParts = PartRegistry.parsePartList(map.requireList("body-parts", "car definition ${file.path}"), namespace)
        val bogies = parseBogies(map.requireList("bogies", "car definition ${file.path}"), namespace)
        val control = CarControlConfig.parse(map.requireMap("control", "car definition ${file.path}"))

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
            bogieBOffset = bogieBOffset,
            control = control
        )
    }

    private fun parseBogies(list: List<*>?, namespace: String): List<BogieDefinition> {
        if (list == null) return emptyList()
        return list.mapIndexed { index, entry ->
            val subject = "bogies[$index]"
            val entryMap = (entry as? Map<*, *>)?.toStringKeyMap(subject)
                ?: throw IllegalArgumentException("$subject must be a map")
            entryMap.requireOnlyKeys(setOf("offset", "parts", "wheel-parts", "wheel-diameter"), subject)
            BogieDefinition(
                offset = PartRegistry.parseVec3(entryMap.optionalMap("offset", "bogies[$index]")),
                parts = PartRegistry.parsePartList(entryMap.optionalList("parts", "bogies[$index]"), namespace),
                wheelParts = PartRegistry.parsePartList(entryMap.optionalList("wheel-parts", "bogies[$index]"), namespace),
                wheelDiameter = entryMap.optionalDoubleValue("wheel-diameter", subject) ?: 1.0
            )
        }
    }

    /** Get all loaded car names. */
    fun getAllNames(): Set<String> = cars.keys.toSet()

    /** Get all loaded car definitions. */
    fun getAllCars(): Map<String, CarDefinition> = cars.toMap()

    /** Get a car definition by fully qualified name. */
    operator fun get(name: String): CarDefinition? = cars[name]
}
