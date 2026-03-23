package work.art1st.mineRails.core.physics.train

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import work.art1st.mineRails.core.api.physics.exception.CannotCreateTrainException
import work.art1st.mineRails.core.api.util.persistence.Loads
import work.art1st.mineRails.core.api.util.plugin.SerializableExtensionData
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.physics.rail.RailLocationWithFacing
import kotlin.collections.map

typealias SavedTrain = SavedTrainV1
typealias TrainConfig = TrainConfigV1
typealias SavedCar = SavedCarV1
typealias CarConfig = CarConfigV1
typealias SavedBogie = SavedBogieV1
typealias BogieConfig = BogieConfigV1

data class TrainBuildDependency(
    val trainId: String,
    val world: CoreWorld,
    val trainFactory: TrainFactory,
    val headLocation: RailLocationWithFacing
)

data class CarBuildDependency(
    val trainDependency: TrainBuildDependency,
    val carId: String
)

@Serializable
data class SavedTrainV1(
    val id: String,
    @Contextual val cars: List<SavedCarV1>,
    val extension: Map<String, SerializableExtensionData>
) : Loads<CoreTrain, CoreWorld> {
    override fun load(dependency: CoreWorld): CoreTrain {
        return CoreTrain(id, cars.map { it.load(dependency) }, dependency, extension)
    }
}

@Serializable
data class TrainConfigV1(
    val cars: List<String>,
    val extension: Map<String, SerializableExtensionData>
) : Loads<CoreTrain, TrainBuildDependency> {
    @Throws(CannotCreateTrainException::class)
    override fun load(dependency: TrainBuildDependency) : CoreTrain {
        val trainId = dependency.trainId
        var carNum = 1
        val coreCars = cars.map {
            val carDependency = CarBuildDependency(dependency, "${dependency.trainId}-$carNum")
            carNum += 1
            dependency.trainFactory.getCarConfig(it)?.load(carDependency)?: throw CannotCreateTrainException()
        }
        return CoreTrain(trainId, coreCars, world = dependency.world, extension)
    }
}

@Serializable
data class SavedCarV1(
    val id: String,
    val bogieA: SavedBogieV1,
    val bogieB: SavedBogieV1,
    val config: CarConfigV1
) : Loads<CoreCar, CoreWorld> {
    override fun load(dependency: CoreWorld): CoreCar {
        val bogieA = bogieA.load(Unit)
        val bogieB = bogieB.load(Unit)
        return config.buildCar(id, dependency, bogieA, bogieB)
    }

}

@Serializable
data class CarConfigV1(
    val length: Double,
    val floorHeight: Double,
    val bogieAType: String?,
    val bogieAOffset: Double,
    val bogieBType: String?,
    val bogieBOffset: Double,
    val connectorLengthAtA: Double,
    val connectorLengthAtB: Double,
    val extension: Map<String, SerializableExtensionData>
) : Loads<CoreCar, CarBuildDependency> {

    internal fun buildCar(id: String, world: CoreWorld, bogieA: CoreBogie, bogieB: CoreBogie) : CoreCar {
        /*
         初始化时， 令所有bogie处于同一位置
         */
        return CoreCar(
            id = id,
            bogies = listOf(
                bogieA,
                bogieB
            ),
            bogiesOffsets = listOf(
                bogieAOffset,
                bogieBOffset,
            ),
            length = length,
            floorHeight = floorHeight,
            connectorLengthAtA = connectorLengthAtA,
            connectorLengthAtB = connectorLengthAtB,
            world = world,
            savedExtensionData = extension
        )
    }

    override fun load(dependency: CarBuildDependency): CoreCar {
        if (bogieAType == null || bogieBType == null) {
            throw CannotCreateTrainException()
        }
        val bogieAConfig = dependency.trainDependency.trainFactory.getBogieConfig(bogieAType)
        val bogieBConfig = dependency.trainDependency.trainFactory.getBogieConfig(bogieAType)
        if (bogieAConfig == null || bogieBConfig == null) {
            throw CannotCreateTrainException()
        }
        // 先放在默认位置，之后再调整
        val bogieA = CoreBogie(
            location = dependency.trainDependency.headLocation,
            savedExtensionData = bogieAConfig.extension
        )
        val bogieB = CoreBogie(
            location = dependency.trainDependency.headLocation,
            savedExtensionData = bogieAConfig.extension
        )
        return buildCar(dependency.carId, dependency.trainDependency.world, bogieA, bogieB)
    }

}

@Serializable
data class SavedBogieV1(
    @Contextual val location: RailLocationWithFacing,
    val isSameDirectionAsFacing: Boolean,
    val extension: Map<String, SerializableExtensionData>
) : Loads<CoreBogie, Unit> {
    override fun load(dependency: Unit): CoreBogie {
        return CoreBogie(location, isSameDirectionAsFacing, extension)
    }
}

@Serializable
data class BogieConfigV1(
    val extension: Map<String, SerializableExtensionData>
) {
}