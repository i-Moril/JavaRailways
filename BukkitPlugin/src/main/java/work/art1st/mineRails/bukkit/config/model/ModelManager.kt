package work.art1st.mineRails.bukkit.config.model

import work.art1st.mineRails.bukkit.config.RailModelConfig
import work.art1st.mineRails.core.api.physics.World
import java.io.File

class ModelManager(
    private val dataFolder: File
) {
    val partRegistry: PartRegistry
    val carLoader: CarLoader
    val trainLoader: TrainLoader

    val trainPackages: MutableMap<String, LoadedTrainPackage> = mutableMapOf()
    val railPackages: MutableMap<String, LoadedRailPackage> = mutableMapOf()

    init {
        val partsDir = File(dataFolder, "parts")
        val carsDir = File(dataFolder, "cars")
        val trainsDir = File(dataFolder, "trains")

        partRegistry = PartRegistry(partsDir)
        carLoader = CarLoader(carsDir, partRegistry)
        trainLoader = TrainLoader(trainsDir, carLoader)
    }

    fun loadAll() {
        trainPackages.clear()
        railPackages.clear()

        loadTrainPackages()
        loadRailPackages()

        wireCrossPackageReferences()

        partRegistry.load()
        carLoader.load()
        trainLoader.load()
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
        // Fallback to legacy
        return carLoader[name]
    }

    fun getTrain(name: String): TrainDefinition? {
        trainPackages[name]?.train?.let { return it }
        return trainLoader[name]
    }

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

    fun createTreeBuilder(world: World, packageName: String? = null): ModelTreeBuilder {
        return ModelTreeBuilder(world, getPartResolver(packageName))
    }

    fun createTreeBuilder(world: World, partResolver: PartResolver): ModelTreeBuilder {
        return ModelTreeBuilder(world, partResolver)
    }

    fun getPartResolver(packageName: String?): PartResolver {
        return if (packageName != null) {
            trainPackages[packageName]?.partRegistry ?: partRegistry
        } else {
            partRegistry
        }
    }

    fun createCarModelSnapshot(carModelName: String): SavedCarModelTree? {
        val car = getCar(carModelName) ?: return null
        val resolver = getPartResolver(car.packageName)
        return SavedCarModelTree.build(car, resolver)
    }

    val hasPackages: Boolean get() = trainPackages.isNotEmpty() || railPackages.isNotEmpty()
    val hasRailPackages: Boolean get() = railPackages.isNotEmpty()
}
