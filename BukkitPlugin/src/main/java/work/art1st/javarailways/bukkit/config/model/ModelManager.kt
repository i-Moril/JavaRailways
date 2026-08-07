package work.art1st.javarailways.bukkit.config.model

import work.art1st.javarailways.bukkit.config.RailModelConfig
import work.art1st.javarailways.core.api.physics.World
import java.io.File

class ModelManager(
    private val dataFolder: File
) {
    val trainPackages: MutableMap<String, LoadedTrainPackage> = mutableMapOf()
    val railPackages: MutableMap<String, LoadedRailPackage> = mutableMapOf()

    fun loadAll() {
        trainPackages.clear()
        railPackages.clear()

        loadTrainPackages()
        loadRailPackages()

        wireCrossPackageReferences()
    }

    private fun loadTrainPackages() {
        val trainsDir = File(dataFolder, "trains")
        if (!trainsDir.exists() || !trainsDir.isDirectory) return

        for (dir in trainsDir.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory) continue
            val manifestFile = File(dir, "manifest.yml")
            if (!manifestFile.exists()) continue

            val loader = PackageLoader(dir)
            val result = loader.loadTrainPackage() ?: continue
            trainPackages[result.manifest.name] = result
        }
    }

    private fun loadRailPackages() {
        val railsDir = File(dataFolder, "rails")
        if (!railsDir.exists() || !railsDir.isDirectory) return

        for (dir in railsDir.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory) continue
            val manifestFile = File(dir, "manifest.yml")
            if (!manifestFile.exists()) continue

            val loader = PackageLoader(dir)
            val result = loader.loadRailPackage() ?: continue
            railPackages[result.manifest.name] = result
        }
    }

    private fun wireCrossPackageReferences() {
        val allRegistries: Map<String, PartRegistry> = trainPackages.mapValues { it.value.partRegistry }

        for ((_, pkg) in trainPackages) {
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
            trainPackages[packageName]?.actions?.get(actionName)?.let { return it }
        }
        for ((_, pkg) in trainPackages) {
            pkg.actions[actionName]?.let { return it }
        }
        return null
    }

    fun getTrainActions(packageName: String?): Map<String, TrainActionConfig> {
        if (packageName != null) {
            trainPackages[packageName]?.actions?.let { return it }
        }
        return trainPackages.values.flatMap { it.actions.entries }.associate { it.key to it.value }
    }

    fun getTrainBehavior(packageName: String?): TrainBehaviorScript? {
        if (packageName != null) {
            trainPackages[packageName]?.behavior?.let { return it }
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
