package work.art1st.javarailways.core.physics.train

import kotlinx.serialization.Serializable
import work.art1st.configLib.persistence.Loads
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData
import work.art1st.javarailways.core.api.util.plugin.ExtensionDataMapSerializer
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.rail.RailLocationWithFacing

typealias SavedTrain = SavedTrainV1
typealias SavedCar = SavedCarV1
typealias SavedBogie = SavedBogieV1

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
        val loadedCars = cars.map { savedCar ->
            savedCar.load(dependency).also {
                it.restorePersistedOperatingDirection(reversed xor savedCar.installedReversed)
            }
        }
        val train = CoreTrain(
            id,
            loadedCars,
            dependency,
            extension,
            name,
            persistedHeadLocation = headLocation.load(dependency),
            persistedTailLocation = tailLocation.load(dependency),
            initialReversed = reversed
        )
        return try {
            train.restorePersistedPlacement(reversed)
            dependency.publishOwnerChanges(listOf(train))
            train
        } catch (failure: Exception) {
            if (dependency.physicsCoordinator.status ==
                work.art1st.javarailways.core.physics.tick.PhysicsCoordinatorStatus.RUNNING
            ) {
                runCatching { train.discardFailedLoad() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
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
        val bogieA = bogieA.load(dependency)
        val bogieB = bogieB.load(dependency)
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
