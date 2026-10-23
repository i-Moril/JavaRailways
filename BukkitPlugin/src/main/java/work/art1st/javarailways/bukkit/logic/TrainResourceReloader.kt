package work.art1st.javarailways.bukkit.logic

import work.art1st.javarailways.basic.basicWorldData
import work.art1st.javarailways.basic.control.RealisticManualController
import work.art1st.javarailways.basic.safety.TrainSpeedProfile
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.TrainModelUpdatePolicy
import work.art1st.javarailways.bukkit.config.model.*
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.bukkit.persistence.CarModelSnapshotStore
import work.art1st.javarailways.bukkit.persistence.TrainPackageSnapshotStore
import work.art1st.javarailways.bukkit.util.BukkitThreadGuardBridge
import work.art1st.javarailways.bukkit.util.LiveWorldManagement
import work.art1st.javarailways.core.api.control.PreparedControllerStateUpdate
import work.art1st.javarailways.core.api.control.TrainController
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.core.physics.train.CoreTrain
import work.art1st.javarailways.simpleato.api.ato.AbstractATO
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Prepare files off-thread, then publish each type with all its instances between physics frames. */
internal class TrainResourceReloader(private val plugin: BukkitPlugin) {
    fun reload(): CompletableFuture<ModelReloadResult> {
        val completion = CompletableFuture<ModelReloadResult>()
        plugin.platformDispatcher.runAsync {
            try {
                val loaded = plugin.modelManager.prepareReload()
                val writeFailures = linkedMapOf<String, Throwable>()
                loaded.versions.forEach { (type, version) ->
                    try {
                        TrainPackageSnapshotStore.save(version.packageSnapshot)
                        version.carPackages.values.distinctBy { it.hash }.forEach(TrainPackageSnapshotStore::save)
                        version.models.values.forEach(CarModelSnapshotStore::save)
                    } catch (failure: Exception) {
                        writeFailures[type] = failure
                    }
                }
                val prepared = loaded.copy(
                    trainPackages = loaded.trainPackages - writeFailures.keys,
                    versions = loaded.versions - writeFailures.keys,
                    failures = loaded.failures + writeFailures)

                plugin.platformDispatcher.runGlobal {
                    try {
                        val result = LiveWorldManagement.run {
                            val worlds = plugin.worldManager.railWorlds.entries.sortedBy { it.key.uid.toString() }
                                .mapNotNull { (bukkit, world) -> (world as? CoreWorld)?.let { bukkit.uid to it } }
                            BukkitThreadGuardBridge.runWithWorlds(worlds.map { it.second }) {
                                fun publish(index: Int): ModelReloadResult {
                                    if (index == worlds.size) return publishPrepared(prepared, worlds)
                                    return worlds[index].second.physicsCoordinator.processBetweenFrames { publish(index + 1) }
                                }
                                publish(0)
                            }
                        }
                        completion.complete(result)
                    } catch (failure: Exception) {
                        completion.completeExceptionally(failure)
                    }
                }
            } catch (failure: Exception) {
                completion.completeExceptionally(failure)
            }
        }
        return completion
    }

    private fun publishPrepared(prepared: PreparedModelReload, worlds: List<Pair<java.util.UUID, CoreWorld>>): ModelReloadResult {
        if (plugin.javaRailwaysConfig.trainModelUpdatePolicy == TrainModelUpdatePolicy.SNAPSHOT) {
            val result = plugin.modelManager.publishReload(prepared)
            // Revalidate only quarantined instances against their own pinned configuration.
            worlds.forEach { (_, world) ->
                plugin.worldManager.refreshTrainSpeedProfiles(world, onlyHeld = true)
            }
            reportReloadFailures(result)
            return result
        }
        val trains = worlds.flatMap { it.second.allTrains }
        val rejected = linkedMapOf<String, Throwable>()
        var updates: Map<CoreTrain, PreparedTrainConfiguration>
        // A linked consist can depend on several types. Reject all participating candidate types
        // if its controller cannot accept their joint configuration, and prepare the survivors again.
        do {
            val before = rejected.size
            val pending = linkedMapOf<CoreTrain, PreparedTrainConfiguration>()
            trains.forEach { train ->
                val types = resourceTypes(train)
                val changing = types.intersect(prepared.versions.keys) - rejected.keys
                if (changing.isEmpty()) return@forEach
                var rejectedCarType = false
                try {
                    fun version(type: String): TrainResourceVersion =
                        if (type in rejected) plugin.modelManager.resourceVersion(type)
                        else prepared.versions[type] ?: plugin.modelManager.resourceVersion(type)
                    val renderer = requireNotNull(train.rendererOrNull) { "Train '${train.id}' has no resource binding" }
                    val root = requireNotNull(renderer.modelPackageName()) { "Train '${train.id}' has no creation template" }
                    val control = version(root).packageSnapshot.control
                    val cars = train.cars.map { car ->
                        val binding = requireNotNull(car.carRendererOrNull) { "Car '${car.id}' has no resource binding" }
                        val type = requireNotNull(binding.resourceType) { "Car '${car.id}' has no creation template" }
                        val name = requireNotNull(binding.carModelName) { "Car '${car.id}' has no model" }
                        try {
                            val candidate = version(type)
                            val model = requireNotNull(candidate.models[name]) { "Template '$type' no longer contains '$name'" }
                            requireCompatibleGeometry(car, model.car)
                            requireNotNull(candidate.packageSnapshot.carControls[name]) { "Missing control profile for '$name'" }
                        } catch (failure: Exception) {
                            if (type in changing) {
                                rejected[type] = failure
                                rejectedCarType = true
                            }
                            throw failure
                        }
                    }
                    pending[train] = prepareTrainConfiguration(train, control, cars)
                } catch (failure: Exception) {
                    if (!rejectedCarType) changing.forEach { rejected[it] = failure }
                }
            }
            updates = pending
        } while (rejected.size != before)
        val result = plugin.modelManager.publishReload(prepared, rejected)
        updates.forEach { (train, update) ->
            update.install(train)
            val renderer = requireNotNull(train.rendererOrNull)
            val type = requireNotNull(renderer.modelPackageName())
            renderer.bindCreatedResources(type, plugin.modelManager.resourceVersion(type))
            train.cars.forEach { car ->
                val binding = requireNotNull(car.carRendererOrNull)
                val source = requireNotNull(binding.resourceType)
                binding.bindCreatedResources(source, requireNotNull(binding.carModelName), plugin.modelManager.resourceVersion(source))
            }
        }
        worlds.forEach { (_, world) -> plugin.worldManager.refreshTrainSpeedProfiles(world, onlyHeld = true) }
        reportReloadFailures(result)
        return result
    }

    private fun resourceTypes(train: CoreTrain): Set<String> = buildSet {
        train.rendererOrNull?.modelPackageName()?.let(::add)
        train.cars.mapNotNullTo(this) { it.carRendererOrNull?.resourceType }
    }

    private fun reportReloadFailures(result: ModelReloadResult) {
        // Rejected candidates retain the active resources. Current configuration/model faults
        // are reported by their revalidation owners, not by this failed reload attempt.
        result.failures.forEach { (type, failure) ->
            plugin.logger.log(Level.SEVERE, "Train resource reload failed for '$type'; retaining its previous version", failure)
        }
    }
}

internal data class PreparedTrainConfiguration(
    val controller: TrainController?,
    val updates: List<PreparedControllerStateUpdate>,
    val speedProfile: TrainSpeedProfile
) {
    fun install(train: CoreTrain) {
        controller?.let { train.applyControllerConfiguration(it, updates) }
        train.world.basicWorldData.trainSpeedProfiles.put(train.id, speedProfile)
    }
}

internal fun prepareTrainConfiguration(
    train: CoreTrain, control: TrainControlConfig, carControls: List<CarControlConfig>
): PreparedTrainConfiguration {
    require(carControls.size == train.cars.size) { "Train '${train.id}' has incomplete car configuration" }
    val controller = train.controller
    val updates = when (controller) {
        null -> emptyList()
        is RealisticManualController -> controller.prepareConfigurationChange(control, carControls)
        is AbstractATO -> controller.prepareConfigurationChange(control, carControls)
        else -> error("Controller '${controller.javaClass.name}' cannot replace its train configuration")
    }
    return PreparedTrainConfiguration(controller, updates, TrainSpeedProfile(control.curveSpeedLimitOffset))
}

internal fun requireCompatibleGeometry(car: CoreCar, definition: CarDefinition) {
    require(car.length == definition.length && car.floorHeight == definition.floorHeight &&
        car.connectorLengthAtA == definition.connectorLengthA && car.connectorLengthAtB == definition.connectorLengthB &&
        car.bogieAOffset == (definition.bogieAOffset ?: definition.length * 0.3) &&
        car.bogieBOffset == (definition.bogieBOffset ?: -definition.length * 0.3)) {
        "Car '${car.id}' geometry changed; recreate the train before using this resource version"
    }
}
