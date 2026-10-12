package work.art1st.javarailways.bukkit.config.model

import work.art1st.javarailways.bukkit.config.RailModelConfig
import work.art1st.javarailways.core.api.physics.World
import java.io.File

class ModelManager(
    private val dataFolder: File
) {
    val trainPackages: MutableMap<String, LoadedTrainPackage> = mutableMapOf()
    val railPackages: MutableMap<String, LoadedRailPackage> = mutableMapOf()
    var generation: Long = 0
        private set

    /** Load into isolated maps first, then publish both registries together. */
    @Synchronized
    fun loadAll(): ModelReloadResult {
        val candidateTrains = loadTrainPackages()
        val candidateRails = loadRailPackages()
        wireCrossPackageReferences(candidateTrains)
        trainPackages.clear()
        trainPackages.putAll(candidateTrains)
        railPackages.clear()
        railPackages.putAll(candidateRails)
        generation++
        return ModelReloadResult(candidateTrains.keys.sorted(), candidateRails.keys.sorted(), generation)
    }

    private fun loadTrainPackages(): MutableMap<String, LoadedTrainPackage> {
        val loaded = linkedMapOf<String, LoadedTrainPackage>()
        val trainsDir = File(dataFolder, "trains")
        if (!trainsDir.exists() || !trainsDir.isDirectory) return loaded

        for (dir in trainsDir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!dir.isDirectory) continue
            val manifestFile = File(dir, "manifest.yml")
            if (!manifestFile.exists()) continue

            val loader = PackageLoader(dir)
            val result = loader.loadTrainPackage() ?: continue
            require(loaded.putIfAbsent(result.manifest.name, result) == null) {
                "Duplicate train package name '${result.manifest.name}'"
            }
        }
        return loaded
    }

    private fun loadRailPackages(): MutableMap<String, LoadedRailPackage> {
        val loaded = linkedMapOf<String, LoadedRailPackage>()
        val railsDir = File(dataFolder, "rails")
        if (!railsDir.exists() || !railsDir.isDirectory) return loaded

        for (dir in railsDir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!dir.isDirectory) continue
            val manifestFile = File(dir, "manifest.yml")
            if (!manifestFile.exists()) continue

            val loader = PackageLoader(dir)
            val result = loader.loadRailPackage() ?: continue
            require(loaded.putIfAbsent(result.manifest.name, result) == null) {
                "Duplicate rail package name '${result.manifest.name}'"
            }
        }
        return loaded
    }

    private fun wireCrossPackageReferences(packages: Map<String, LoadedTrainPackage>) {
        val allRegistries: Map<String, PartRegistry> = packages.mapValues { it.value.partRegistry }

        for ((_, pkg) in packages) {
            pkg.partRegistry.fallbackRegistries = allRegistries - pkg.manifest.name
        }
    }

    fun getCar(name: String): CarDefinition? {
        // Qualified name: "<pkg>/<car>" → look up in specific package
        val slashIdx = name.indexOf('/')
        if (slashIdx > 0) {
            val pkgName = name.substring(0, slashIdx)
            val carName = name.substring(slashIdx + 1)
            trainPackages[pkgName]?.cars?.get(carName)?.let { return it }
        }
        // Bare name: search all packages
        for ((_, pkg) in trainPackages) {
            pkg.cars[name]?.let { return it }
        }
        return null
    }

    fun getTrain(name: String): TrainDefinition? = trainPackages[name]?.train

    fun getTrainAction(packageName: String?, actionName: String): TrainActionConfig? {
        if (packageName != null) {
            return trainPackages[packageName]?.actions?.get(actionName)
        }
        for ((_, pkg) in trainPackages) {
            pkg.actions[actionName]?.let { return it }
        }
        return null
    }

    fun getTrainActions(packageName: String?): Map<String, TrainActionConfig> {
        if (packageName != null) {
            return trainPackages[packageName]?.actions ?: emptyMap()
        }
        return trainPackages.values.flatMap { it.actions.entries }.associate { it.key to it.value }
    }

    fun getTrainBehavior(packageName: String?): TrainBehaviorScript? {
        if (packageName != null) {
            return trainPackages[packageName]?.behavior
        }
        return trainPackages.values.firstNotNullOfOrNull { it.behavior }
    }

    fun getTrainControl(packageName: String?): TrainControlConfig {
        val resolvedPackageName = packageName
            ?: throw IllegalArgumentException("Train control package name is required")
        return trainPackages[resolvedPackageName]?.control
            ?: throw IllegalArgumentException("Train package '$resolvedPackageName' has no loaded control profile")
    }

    fun getRailConfig(name: String): RailModelConfig? {
        return railPackages[name]?.railConfig
    }

    fun createTreeBuilder(world: World, packageName: String): ModelTreeBuilder {
        return ModelTreeBuilder(world, getPartResolver(packageName))
    }

    fun createTreeBuilder(world: World, partResolver: PartResolver): ModelTreeBuilder {
        return ModelTreeBuilder(world, partResolver)
    }

    fun getPartResolver(packageName: String): PartResolver =
        trainPackages[packageName]?.partRegistry
            ?: throw IllegalArgumentException("Unknown train package: $packageName")

    fun createTrainPackageSnapshot(packageName: String): SavedTrainPackageSnapshot? {
        val pkg = trainPackages[packageName] ?: return null
        return SavedTrainPackageSnapshot.build(
            packageName = packageName,
            actions = pkg.actions,
            behavior = pkg.behavior,
            control = pkg.control,
            carControls = pkg.cars.mapValues { it.value.control }
        )
    }

    fun createCarModelSnapshot(carModelName: String): SavedCarModelTree? {
        val car = getCar(carModelName) ?: return null
        val resolver = getPartResolver(requireNotNull(car.packageName) {
            "Car '$carModelName' has no package"
        })
        return SavedCarModelTree.build(car, resolver)
    }

    val hasPackages: Boolean get() = trainPackages.isNotEmpty() || railPackages.isNotEmpty()
    val hasRailPackages: Boolean get() = railPackages.isNotEmpty()
}

data class ModelReloadResult(
    val trainPackages: List<String>,
    val railPackages: List<String>,
    val generation: Long
)
