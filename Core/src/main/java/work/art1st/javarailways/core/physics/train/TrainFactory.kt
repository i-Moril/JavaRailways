package work.art1st.javarailways.core.physics.train

import work.art1st.javarailways.core.api.physics.exception.CannotCreateTrainException

class TrainFactory {
    private val bogiePrototypes = mutableMapOf<String, BogieConfig>()
    private val carPrototypes = mutableMapOf<String, CarConfig>()
    private val trainPrototypes = mutableMapOf<String, TrainConfig>()

    internal fun getCarConfig(carType: String) : CarConfig? {
        return carPrototypes[carType]
    }

    internal fun getBogieConfig(bogieType: String) : BogieConfig? {
        return bogiePrototypes[bogieType]
    }

    internal fun getTrainConfig(trainType: String) : TrainConfig? {
        return trainPrototypes[trainType]
    }

    internal fun build(trainType: String, dependencyProvider: TrainBuildDependency) : CoreTrain {
        val trainConfig = getTrainConfig(trainType) ?: throw CannotCreateTrainException()
        return trainConfig.load(dependencyProvider)
    }
}