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
            val result = loader.load()
            if (result is LoadedTrainPackage) {
                trainPackages[result.manifest.name] = result
            }
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
            val result = loader.load()
            if (result is LoadedRailPackage) {
                railPackages[result.manifest.name] = result
            }
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

    fun getRailConfig(name: String): RailModelConfig? {
        return railPackages[name]?.railConfig
    }

    fun createTreeBuilder(world: World, packageName: String? = null): ModelTreeBuilder {
        val registry = if (packageName != null) {
            trainPackages[packageName]?.partRegistry ?: partRegistry
        } else {
            partRegistry
        }
        return ModelTreeBuilder(world, registry)
    }

    val hasPackages: Boolean get() = trainPackages.isNotEmpty() || railPackages.isNotEmpty()
    val hasRailPackages: Boolean get() = railPackages.isNotEmpty()
}
