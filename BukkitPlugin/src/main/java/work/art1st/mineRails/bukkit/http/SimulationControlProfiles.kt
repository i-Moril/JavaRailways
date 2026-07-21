package work.art1st.mineRails.bukkit.http

import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.config.model.CarControlConfig
import work.art1st.mineRails.bukkit.config.model.TrainControlConfig
import work.art1st.mineRails.bukkit.extension.train.carRendererOrNull
import work.art1st.mineRails.core.api.physics.World

enum class SimulationProfileType { WORLD_TRAIN, TRAIN_TEMPLATE }

data class SimulationControlProfile(
    val type: SimulationProfileType,
    val id: String,
    val trainControl: TrainControlConfig,
    val carControls: List<CarControlConfig>
) {
    init {
        require(id.isNotBlank())
        require(carControls.isNotEmpty())
    }
}

data class SimulationProfileDescriptor(
    val type: SimulationProfileType,
    val id: String,
    val displayName: String,
    val carCount: Int
)

/** Resolves exact loaded control configs without exposing mutable trains to the async simulator. */
class SimulationControlProfileCatalog(private val plugin: BukkitPlugin) {
    fun descriptors(world: World): List<SimulationProfileDescriptor> = buildList {
        world.allTrains.sortedBy { it.id }.forEach { train ->
            runCatching { resolveWorldTrain(world, train.id) }.getOrNull()?.let { profile ->
                add(SimulationProfileDescriptor(
                    SimulationProfileType.WORLD_TRAIN,
                    train.id,
                    "${train.id} (${profile.carControls.size} cars)",
                    profile.carControls.size
                ))
            }
        }
        plugin.modelManager.trainPackages.toSortedMap().forEach { (packageId, loaded) ->
            val controls = loaded.train.composition.mapNotNull { plugin.modelManager.getCar(it)?.control }
            if (controls.size == loaded.train.composition.size && controls.isNotEmpty()) {
                add(SimulationProfileDescriptor(
                    SimulationProfileType.TRAIN_TEMPLATE,
                    packageId,
                    loaded.manifest.displayName ?: packageId,
                    controls.size
                ))
            }
        }
    }

    fun resolve(world: World, type: SimulationProfileType, id: String): SimulationControlProfile = when (type) {
        SimulationProfileType.WORLD_TRAIN -> resolveWorldTrain(world, id)
        SimulationProfileType.TRAIN_TEMPLATE -> resolveTemplate(id)
    }

    private fun resolveWorldTrain(world: World, id: String): SimulationControlProfile {
        val train = world.allTrains.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("world train not found: $id")
        val carModelNames = train.cars.mapIndexed { index, car ->
            car.carRendererOrNull?.carModelName
                ?: throw IllegalArgumentException("train $id car #${index + 1} has no model control profile")
        }
        val packageName = carModelNames.firstNotNullOfOrNull { modelName ->
            plugin.modelManager.getCar(modelName)?.packageName
        } ?: throw IllegalArgumentException("train $id is not associated with a train template")
        val carControls = carModelNames.map { modelName ->
            plugin.modelManager.getCar(modelName)?.control
                ?: throw IllegalArgumentException("car model has no control profile: $modelName")
        }
        return SimulationControlProfile(
            SimulationProfileType.WORLD_TRAIN,
            id,
            plugin.modelManager.getTrainControl(packageName),
            carControls
        )
    }

    private fun resolveTemplate(id: String): SimulationControlProfile {
        val loaded = plugin.modelManager.trainPackages[id]
            ?: throw IllegalArgumentException("train template not found: $id")
        val controls = loaded.train.composition.map { modelName ->
            plugin.modelManager.getCar(modelName)?.control
                ?: throw IllegalArgumentException("template $id car has no control profile: $modelName")
        }
        return SimulationControlProfile(
            SimulationProfileType.TRAIN_TEMPLATE,
            id,
            loaded.control,
            controls
        )
    }
}
