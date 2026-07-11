package work.art1st.mineRails.bukkit.config.model

import work.art1st.mineRails.bukkit.config.RailModelConfig
import work.art1st.mineRails.bukkit.config.loadRailModelConfig
import java.io.File

/**
 * Loads a single self-contained package directory.
 *
 * A package is a directory under `trains/` or `rails/` containing
 * a `manifest.yml` at its root. TRAIN packages also have `cars/`
 * and `parts/` subdirectories. RAIL packages have a rail model config file.
 */
class PackageLoader(
    private val packageDir: File
) {
    /** Package name derived from the directory name. */
    val packageName: String = packageDir.name

    /**
     * Load a train package. Returns null only when manifest is missing.
     * Existing manifests and referenced config files must be complete and valid.
     */
    fun loadTrainPackage(): LoadedTrainPackage? {
        val manifest = loadManifestOrNull() ?: return null
        require(manifest.type == PackageType.TRAIN) {
            "Package ${packageDir.path} declares type '${manifest.type.name.lowercase()}' in manifest.yml but was loaded as a train package"
        }
        return buildTrainPackage(manifest)
    }

    /**
     * Load a rail package. Returns null only when manifest is missing.
     * Existing manifests and referenced config files must be complete and valid.
     */
    fun loadRailPackage(): LoadedRailPackage? {
        val manifest = loadManifestOrNull() ?: return null
        require(manifest.type == PackageType.RAIL) {
            "Package ${packageDir.path} declares type '${manifest.type.name.lowercase()}' in manifest.yml but was loaded as a rail package"
        }
        return buildRailPackage(manifest)
    }

    // -- Manifest ──────────────────────────────────────────────

    private fun loadManifestOrNull(): PackageManifest? {
        val manifestFile = File(packageDir, "manifest.yml")
        if (!manifestFile.exists() || !manifestFile.isFile) return null
        return loadManifest(manifestFile)
    }

    private fun loadManifest(file: File): PackageManifest {
        val map = loadYamlMap(file, "package manifest ${file.path}")
        val version = map.requireInt("config-version", "package manifest ${file.path}")
        val typeStr = map["type"] as? String
            ?: throw IllegalArgumentException("Package manifest ${file.path} is missing required field 'type'")
        val type = try {
            PackageType.valueOf(typeStr.uppercase())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Unsupported package type '$typeStr' in ${file.path}", e)
        }

        return PackageManifest(
            name = map.requireString("name", "package manifest ${file.path}"),
            type = type,
            version = version,
            displayName = map["display-name"] as? String,
            composition = map.optionalList("composition", "package manifest ${file.path}")?.mapIndexed { index, entry ->
                entry as? String
                    ?: throw IllegalArgumentException("package manifest ${file.path}.composition[$index] must be a string")
            },
            railConfigFile = map["rail-config"] as? String
        )
    }

    // -- TRAIN package ─────────────────────────────────────────

    private fun buildTrainPackage(manifest: PackageManifest): LoadedTrainPackage {
        val partsDir = File(packageDir, "parts")
        val carsDir = File(packageDir, "cars")

        // Load local parts
        val partRegistry = PartRegistry(partsDir)
        if (partsDir.exists()) {
            partRegistry.load()
        }

        // Load local cars
        val cars = mutableMapOf<String, CarDefinition>()
        if (carsDir.exists()) {
            loadCarsFromDir(carsDir, manifest.name, partRegistry, cars)
        }

        // Build train definition
        val composition = manifest.composition
            ?: throw IllegalArgumentException("Train package ${packageDir.path} must declare composition in manifest.yml")
        val train = TrainDefinition(
            key = manifest.name,
            name = manifest.displayName ?: manifest.name,
            composition = composition
        )
        val actions = loadActions(File(packageDir, "actions.yml"))
        val behavior = loadBehavior(File(packageDir, "behavior.js"))
        val control = loadControl(File(packageDir, "control.yml"))

        return LoadedTrainPackage(
            manifest = manifest,
            partRegistry = partRegistry,
            cars = cars,
            train = train,
            actions = actions,
            behavior = behavior,
            control = control
        )
    }

    private fun loadActions(file: File): Map<String, TrainActionConfig> {
        if (!file.exists() || !file.isFile) return emptyMap()
        val map = loadYamlMap(file, "train actions ${file.path}")
        return TrainActionConfig.parseActions(map)
    }

    private fun loadBehavior(file: File): TrainBehaviorScript? {
        if (!file.exists() || !file.isFile) return null
        return try {
            TrainBehaviorScript(
                fileName = file.name,
                source = file.readText()
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load train behavior ${file.path}", e)
        }
    }

    private fun loadControl(file: File): TrainControlConfig {
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("Train package ${packageDir.path} is missing required control.yml")
        }
        val map = loadYamlMap(file, "train control ${file.path}")
        return TrainControlConfig.parse(map)
    }

    private fun loadCarsFromDir(
        dir: File,
        namespace: String,
        partRegistry: PartRegistry,
        target: MutableMap<String, CarDefinition>
    ) {
        // Use CarLoader's parsing but collect into our map.
        // We create a temporary CarLoader that scans the dir,
        // but override its scanning to be non-recursive if needed.
        val tempLoader = CarLoader(dir, partRegistry)
        tempLoader.load()
        // CarLoader stores cars keyed by qualified name (<ns>/<simple>).
        // For packages, we want simple names (e.g. "head" not "s100/head").
        for ((qualifiedName, car) in tempLoader.getAllCars()) {
            val simpleName = qualifiedName.substringAfterLast('/')
            car.packageName = namespace
            target[simpleName] = car
        }
    }

    // -- RAIL package ──────────────────────────────────────────

    private fun buildRailPackage(manifest: PackageManifest): LoadedRailPackage {
        val configFileName = manifest.railConfigFile
            ?: throw IllegalArgumentException("Rail package ${packageDir.path} must declare rail-config in manifest.yml")
        val configFile = File(packageDir, configFileName)

        val railConfig = if (configFile.exists()) {
            try {
                configFile.inputStream().use { loadRailModelConfig(it) }
            } catch (e: Exception) {
                throw IllegalStateException("Failed to load rail config ${configFile.path}", e)
            }
        } else {
            throw IllegalArgumentException("Rail config file not found: ${configFile.path}")
        }

        return LoadedRailPackage(
            manifest = manifest,
            railConfig = railConfig
        )
    }
}
