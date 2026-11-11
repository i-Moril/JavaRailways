package work.art1st.javarailways.core.physics.train

import kotlinx.serialization.Serializable
import work.art1st.configLib.persistence.Loads
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataMapSerializer
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing

@Serializable
data class SavedTrainV1(
    val id: String,
    val name: String,
    val cars: List<SavedCarV1>,
    val reversed: Boolean,
    @Serializable(with = ExtensionDataMapSerializer::class)
    val extension: Map<String, SerializableExtensionData>,
    val headLocation: RailLocationWithFacing.SavedRailLocationWithFacingV1,
    val tailLocation: RailLocationWithFacing.SavedRailLocationWithFacingV1
) : Loads<CoreTrain, CoreWorld> {
    override fun load(dependency: CoreWorld): CoreTrain {
        dependency.physicsCoordinator.ensureBetweenFrames("SavedTrainV1.load")
        return CoreTrain.createOwned(dependency) {
            val loadedCars = cars.map { savedCar ->
                loadCar(savedCar).also {
                    it.restorePersistedOperatingDirection(reversed xor savedCar.installedReversed)
                }
            }
            val loaded = loadTrain(this@SavedTrainV1, loadedCars)
            loaded.restorePersistedPlacement(reversed)
            publish()
            loaded
        }
    }

}

@Serializable
data class SavedCarV1(
    val id: String,
    val bogieA: SavedBogieV1,
    val bogieB: SavedBogieV1,
    val config: CarConfigV1,
    /** Physical B faces the stable start of its owning train when true. */
    val installedReversed: Boolean = false
) : Loads<CoreCar, CoreWorld> {
    override fun load(dependency: CoreWorld): CoreCar {
        val creation = TrainCreation(dependency)
        return try {
            creation.loadCar(this)
            creation.finishCar()
        } catch (failure: Throwable) {
            creation.discard(failure)
            throw failure
        }
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
    @Serializable(with = ExtensionDataMapSerializer::class)
    val extension: Map<String, SerializableExtensionData>
) {
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
}

@Serializable
data class SavedBogieV1(
    val location: RailLocationWithFacing.SavedRailLocationWithFacingV1,
    val isSameDirectionAsFacing: Boolean,
    @Serializable(with = ExtensionDataMapSerializer::class)
    val extension: Map<String, SerializableExtensionData>
) : Loads<CoreBogie, CoreWorld> {
    override fun load(dependency: CoreWorld): CoreBogie {
        return CoreBogie(location.load(dependency), isSameDirectionAsFacing, extension)
    }
}
