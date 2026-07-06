package work.art1st.mineRails.bukkit.config.model

import org.yaml.snakeyaml.Yaml
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
     * Load the package. Returns null if manifest is missing or invalid.
     */
    fun load(): Any? {
        val manifestFile = File(packageDir, "manifest.yml")
        if (!manifestFile.exists()) return null

        val manifest = loadManifest(manifestFile) ?: return null

        return when (manifest.type) {
            PackageType.TRAIN -> loadTrainPackage(manifest)
            PackageType.RAIL -> loadRailPackage(manifest)
        }
    }

    // -- Manifest ──────────────────────────────────────────────

    private fun loadManifest(file: File): PackageManifest? {
        return try {
            val yaml = Yaml()
            val map = yaml.load<Map<String, Any>>(file.inputStream())

            val version = (map["config-version"] as? Number)?.toInt() ?: 1
            val typeStr = map["type"] as? String
                ?: return null
            val type = try {
                PackageType.valueOf(typeStr.uppercase())
            } catch (e: IllegalArgumentException) {
                return null
            }

            PackageManifest(
                name = map["name"] as? String ?: packageName,
                type = type,
                version = version,
                displayName = map["display-name"] as? String,
                composition = (map["composition"] as? List<*>)?.mapNotNull { it as? String },
                railConfigFile = map["rail-config"] as? String
            )
        } catch (e: Exception) {
            System.err.println("[MineRails] Failed to load package manifest ${file.path}: ${e.message}")
            null
        }
    }

    // -- TRAIN package ─────────────────────────────────────────

    private fun loadTrainPackage(manifest: PackageManifest): LoadedTrainPackage? {
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
        val composition = manifest.composition ?: cars.keys.toList()
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
        return try {
            val yaml = Yaml()
            val map = yaml.load<Map<String, Any>>(file.inputStream())
            TrainActionConfig.parseActions(map)
        } catch (e: Exception) {
            System.err.println("[MineRails] Failed to load train actions ${file.path}: ${e.message}")
            emptyMap()
        }
    }

    private fun loadBehavior(file: File): TrainBehaviorScript? {
        if (!file.exists() || !file.isFile) return null
        return try {
            TrainBehaviorScript(
                fileName = file.name,
                source = file.readText()
            )
        } catch (e: Exception) {
            System.err.println("[MineRails] Failed to load train behavior ${file.path}: ${e.message}")
            null
        }
    }

    private fun loadControl(file: File): TrainControlConfig {
        if (!file.exists() || !file.isFile) return TrainControlConfig()
        return try {
            val yaml = Yaml()
            val map = yaml.load<Map<String, Any>>(file.inputStream())
            TrainControlConfig.parse(map)
        } catch (e: Exception) {
            System.err.println("[MineRails] Failed to load train control ${file.path}: ${e.message}")
            TrainControlConfig()
        }
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

    private fun loadRailPackage(manifest: PackageManifest): LoadedRailPackage? {
        val configFileName = manifest.railConfigFile ?: "rail_model.yml"
        val configFile = File(packageDir, configFileName)

        val railConfig = if (configFile.exists()) {
            try {
                configFile.inputStream().use { loadRailModelConfig(it) }
            } catch (e: Exception) {
                System.err.println("[MineRails] Failed to load rail config ${configFile.path}: ${e.message}")
                return null
            }
        } else {
            System.err.println("[MineRails] Rail config file not found: ${configFile.path}")
            return null
        }

        return LoadedRailPackage(
            manifest = manifest,
            railConfig = railConfig
        )
    }
}
