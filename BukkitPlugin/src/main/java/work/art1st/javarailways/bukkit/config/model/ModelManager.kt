package work.art1st.javarailways.bukkit.config.model

import work.art1st.javarailways.bukkit.config.RailModelConfig
import work.art1st.javarailways.core.api.physics.World
import java.io.File

class ModelManager(
    private val dataFolder: File,
    private val extensionService: work.art1st.javarailways.bukkit.api.internal.DefaultPartExtensionService? = null
) {
    val trainPackages: MutableMap<String, LoadedTrainPackage> = mutableMapOf()
    val railPackages: MutableMap<String, LoadedRailPackage> = mutableMapOf()
    @Volatile
    var generation: Long = 0
        private set

    private val resourceVersions = mutableMapOf<String, TrainResourceVersion>()
    private val versionOwners = mutableMapOf<String, LoadedTrainPackage>()
    private var trainSources: Map<String, String> = emptyMap()
    var lastReloadFailures: Map<String, Throwable> = emptyMap()
        private set

    init {
        extensionService?.bindPackages { synchronized(this) { trainPackages.values.toList() } }
    }

    /** Strict startup entry point. Reload callers may publish independent valid train types. */
    fun loadAll(): ModelReloadResult {
        val prepared = prepareReload()
        prepared.failures.values.firstOrNull()?.let { throw it }
        return publishReload(prepared)
    }

    /** No published registry is mutated while files and complete model snapshots are prepared. */
    internal fun prepareReload(): PreparedModelReload {
        val extensionGeneration = extensionService?.generation() ?: 0L
        val baseline = synchronized(this) { Triple(generation, trainPackages.toMap(), trainSources) }
        val failures = linkedMapOf<String, Throwable>()
        val sources = linkedMapOf<String, String>()
        val candidates = linkedMapOf<String, LoadedTrainPackage>()
        val trainsDir = File(dataFolder, "trains")
        for (dir in trainsDir.listFiles()?.sortedBy { it.name }.orEmpty()) {
            if (!dir.isDirectory || !File(dir, "manifest.yml").exists()) continue
            var packageName = baseline.third[dir.absolutePath] ?: dir.name
            try {
                val loaded = requireNotNull(PackageLoader(dir).loadTrainPackage()) { "Missing train manifest: $dir" }
                packageName = loaded.manifest.name
                sources[dir.absolutePath] = packageName
                require(packageName !in candidates && packageName !in failures) { "Duplicate train package name '$packageName'" }
                candidates[packageName] = loaded
            } catch (failure: Exception) {
                candidates.remove(packageName)
                failures[packageName] = failure
            }
        }
        baseline.second.keys.filter { it !in candidates && it !in failures }.forEach { name ->
            failures[name] = IllegalArgumentException("Train package '$name' is no longer available")
        }
        // Extension validation needs only local/inline definitions and detached captured resources.
        // Remove rejected providers' candidates before resolving any composition's source cars.
        for ((name, pkg) in candidates) {
            try { extensionService?.validatePackage(pkg) } catch (failure: Exception) { failures[name] = failure }
        }
        failures.keys.forEach(candidates::remove)
        // Only fresh registries are wired. Rejected source packages resolve to their retained baseline.
        val available = baseline.second + candidates
        wireCrossPackageReferences(candidates, available)
        val versions = linkedMapOf<String, TrainResourceVersion>()
        for ((name, pkg) in candidates) {
            try {
                versions[name] = buildResourceVersion(pkg, available)
            } catch (failure: Exception) {
                failures[name] = failure
            }
        }
        failures.keys.forEach { candidates.remove(it); versions.remove(it) }
        return PreparedModelReload(baseline.first, candidates.toMap(), loadRailPackages(), versions.toMap(),
            failures.toMap(), sources.toMap(), extensionGeneration)
    }

    /** Publish only prepared successful types; every failed/rejected type retains its old catalog. */
    @Synchronized
    internal fun publishReload(prepared: PreparedModelReload, rejected: Map<String, Throwable> = emptyMap()): ModelReloadResult {
        check(prepared.extensionGeneration == (extensionService?.generation() ?: 0L)) { "Part extension providers changed while reload was being prepared" }
        check(prepared.baseGeneration == generation) { "Model resources changed while reload was being prepared" }
        val failures = prepared.failures + rejected
        val accepted = prepared.trainPackages.filterKeys { it !in failures }
        accepted.forEach { (name, pkg) ->
            trainPackages[name] = pkg
            resourceVersions[name] = prepared.versions.getValue(name)
            versionOwners[name] = pkg
        }
        trainSources = trainSources + prepared.trainSources.filterValues { it in accepted }
        railPackages.clear()
        railPackages.putAll(prepared.railPackages)
        generation++
        lastReloadFailures = failures.toMap()
        return ModelReloadResult(trainPackages.keys.sorted(), railPackages.keys.sorted(), generation, lastReloadFailures)
    }

    @Synchronized
    fun resourceVersion(packageName: String): TrainResourceVersion {
        val pkg = trainPackages[packageName] ?: throw IllegalArgumentException("Unknown train package: $packageName")
        if (versionOwners[packageName] === pkg) return resourceVersions.getValue(packageName)
        return buildResourceVersion(pkg, trainPackages).also {
            resourceVersions[packageName] = it
            versionOwners[packageName] = pkg
        }
    }

    private fun buildResourceVersion(pkg: LoadedTrainPackage, available: Map<String, LoadedTrainPackage>): TrainResourceVersion {
        val models = linkedMapOf<String, SavedCarModelTree>()
        for (car in pkg.cars.values) {
            models[car.resourceName] = SavedCarModelTree.build(car, pkg.partRegistry)
        }
        val composition = pkg.train.composition.map { name ->
            val car = getCar(available, name) ?: throw IllegalArgumentException("Train '${pkg.manifest.name}' references missing car '$name'")
            val owner = available[car.packageName] ?: throw IllegalArgumentException("Car '$name' has no loaded package")
            val qualifiedName = car.resourceName
            models.getOrPut(qualifiedName) { SavedCarModelTree.build(car, owner.partRegistry) }
            qualifiedName
        }
        val snapshot = SavedTrainPackageSnapshot.build(pkg.manifest.name, pkg.actions, pkg.behavior,
            pkg.control, models.mapValues { it.value.car.control }, pkg.extensions)
        val sourceSnapshots = models.keys.associateWith { name ->
            val owner = requireNotNull(available[getCar(available, name)?.packageName]) {
                "Car '$name' has no source package"
            }
            SavedTrainPackageSnapshot.build(owner.manifest.name, owner.actions, owner.behavior,
                owner.control, owner.cars.mapKeys { "${owner.manifest.name}/${it.key}" }.mapValues { it.value.control }, owner.extensions)
        }
        return TrainResourceVersion(snapshot, java.util.Collections.unmodifiableMap(models),
            java.util.Collections.unmodifiableMap(sourceSnapshots), java.util.List.copyOf(composition))
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

    private fun wireCrossPackageReferences(fresh: Map<String, LoadedTrainPackage>, available: Map<String, LoadedTrainPackage>) {
        val allRegistries = available.mapValues { it.value.partRegistry }
        for ((name, pkg) in fresh) {
            pkg.partRegistry.fallbackRegistries = allRegistries - name
        }
    }

    fun getCar(name: String): CarDefinition? = getCar(trainPackages, name)

    private fun getCar(packages: Map<String, LoadedTrainPackage>, name: String): CarDefinition? {
        val slashIdx = name.indexOf('/')
        if (slashIdx > 0) {
            val pkgName = name.substring(0, slashIdx)
            val carName = name.substring(slashIdx + 1)
            packages[pkgName]?.cars?.get(carName)?.let { return it }
        }
        return packages.values.firstNotNullOfOrNull { it.cars[name] }
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
        if (packageName !in trainPackages) return null
        return resourceVersion(packageName).packageSnapshot
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
    val generation: Long,
    val failures: Map<String, Throwable> = emptyMap()
)
