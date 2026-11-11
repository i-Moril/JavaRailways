package work.art1st.javarailways.bukkit.http

import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.bukkit.config.model.CarControlConfig
import work.art1st.javarailways.bukkit.config.model.TrainControlConfig
import work.art1st.javarailways.bukkit.extension.train.carRendererOrNull
import work.art1st.javarailways.bukkit.extension.train.rendererOrNull
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.physics.train.CoreCar
import work.art1st.javarailways.bukkit.behavior.CapturedCarBehavior
import work.art1st.javarailways.bukkit.extension.train.captureCarBehavior

enum class SimulationProfileType { WORLD_TRAIN, TRAIN_TEMPLATE }

internal data class SimulationCarBlueprint(
    val length: Double,
    val floorHeight: Double,
    val connectorLengthAtA: Double,
    val connectorLengthAtB: Double,
    val bogieAOffset: Double,
    val bogieBOffset: Double,
    val control: CarControlConfig,
    val behavior: CapturedCarBehavior,
    val installedReversed: Boolean = false
) {
    init {
        require(length.isFinite() && length > 0.0)
        require(floorHeight.isFinite())
        require(connectorLengthAtA.isFinite() && connectorLengthAtA >= 0.0)
        require(connectorLengthAtB.isFinite() && connectorLengthAtB >= 0.0)
        require(bogieAOffset.isFinite() && bogieBOffset.isFinite())
    }
}

internal data class SimulationControlProfile(
    val type: SimulationProfileType,
    val id: String,
    val trainControl: TrainControlConfig,
    val carControls: List<CarControlConfig>,
    val cars: List<SimulationCarBlueprint> = emptyList()
) {
    init {
        require(id.isNotBlank())
        require(carControls.isNotEmpty())
        require(cars.isEmpty() || cars.size == carControls.size)
    }
}

data class SimulationProfileDescriptor(
    val type: SimulationProfileType,
    val id: String,
    val displayName: String,
    val carCount: Int
)

/** Resolves exact loaded control configs without exposing mutable trains to the async simulator. */
internal class SimulationControlProfileCatalog(private val plugin: BukkitPlugin) {
    fun descriptors(world: World): List<SimulationProfileDescriptor> = buildList {
        world.allTrains.sortedBy { it.id }.forEach { train ->
            runCatching { resolveWorldTrain(world, train.id) }.getOrNull()?.let { profile ->
                add(SimulationProfileDescriptor(
                    SimulationProfileType.WORLD_TRAIN,
                    train.id,
                    train.name,
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
        val renderer = train.rendererOrNull
            ?: throw IllegalArgumentException("train $id is not associated with a train template")
        val carControls = train.cars.mapIndexed { index, car ->
            val carRenderer = car.carRendererOrNull
            val modelName = carRenderer?.carModelName
                ?: throw IllegalArgumentException("train $id car #${index + 1} has no model control profile")
            carRenderer.resolveCarControl()
                ?: throw IllegalArgumentException("car model has no control profile: $modelName")
        }
        val cars = train.cars.mapIndexed { index, car ->
            val core = car as? CoreCar
                ?: throw IllegalArgumentException("train $id car #${index + 1} is not a CoreCar")
            SimulationCarBlueprint(
                core.length,
                core.floorHeight,
                core.connectorLengthAtA,
                core.connectorLengthAtB,
                core.bogieAOffset,
                core.bogieBOffset,
                carControls[index],
                requireNotNull(car.carRendererOrNull).captureSimulationBehavior(),
                core.isReversed xor train.isReversed
            )
        }
        return SimulationControlProfile(
            SimulationProfileType.WORLD_TRAIN,
            id,
            renderer.resolveControl(),
            carControls,
            cars
        )
    }

    private fun resolveTemplate(id: String): SimulationControlProfile {
        val version = plugin.modelManager.resourceVersion(id)
        return profileFromTemplate(id, version)
    }
}

/** Uses the exact per-car source package, including mixed-package template compositions. */
internal fun profileFromTemplate(
    id: String,
    version: work.art1st.javarailways.bukkit.config.model.TrainResourceVersion
): SimulationControlProfile {
    val cars = version.composition.map { modelName ->
        val model = version.models.getValue(modelName)
        val car = model.car
        val source = version.carPackages.getValue(modelName)
        val control = source.carControls.getValue(modelName)
        SimulationCarBlueprint(
            car.length, car.floorHeight, car.connectorLengthA, car.connectorLengthB,
            car.bogieAOffset ?: car.length * 0.35, car.bogieBOffset ?: -car.length * 0.35,
            control, captureCarBehavior(source, model)
        )
    }
    return SimulationControlProfile(
        SimulationProfileType.TRAIN_TEMPLATE, id, version.packageSnapshot.control,
        cars.map { it.control }, cars
    )
}
