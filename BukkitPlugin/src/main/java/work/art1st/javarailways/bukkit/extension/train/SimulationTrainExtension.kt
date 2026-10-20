package work.art1st.javarailways.bukkit.extension.train

import work.art1st.javarailways.basic.control.TrainDynamics
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.api.physics.train.TrainExtension
import work.art1st.javarailways.core.api.util.plugin.SerializableExtensionData

/** Runtime-only configuration follows real Core split/link extension preparation. */
internal class SimulationTrainExtension(val trainProfile: TrainDynamics.TrainProfile) : TrainExtension {
    override fun initialize(host: Train, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
    override fun splitBefore(carId: String, newId: String): TrainExtension = SimulationTrainExtension(trainProfile)
    override fun prepareLink(masterTrain: Train, removedTrain: Train, counterpart: TrainExtension?): TrainExtension =
        SimulationTrainExtension(trainProfile)
    override fun save(): Pair<String, SerializableExtensionData>? = null
}

internal val Train.simulationTrainProfileOrNull: TrainDynamics.TrainProfile?
    get() {
        var configuration: SimulationTrainExtension? = null
        forEachExtensionData { extension ->
            if (extension is SimulationTrainExtension) {
                check(configuration == null) { "multiple simulation train profiles for train $id" }
                configuration = extension
            }
        }
        return configuration?.trainProfile
    }
