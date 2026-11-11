package work.art1st.javarailways.bukkit.behavior

import work.art1st.javarailways.bukkit.config.model.TrainActionConfig
import work.art1st.javarailways.core.api.physics.train.Car
import work.art1st.javarailways.core.api.physics.train.Train
import work.art1st.javarailways.core.physics.train.CoreCar

/** Operating end relative to one exact car's installed orientation. */
internal fun Train.behaviorRelativeTail(car: Car, controlTail: Boolean): Boolean =
    controlTail xor ((car as CoreCar).isReversed xor isReversed)

internal fun carBehaviorActions(
    yaml: Map<String, TrainActionConfig>,
    scripted: List<TrainBehaviorActionDefinition>
): List<TrainBehaviorActionDefinition> =
    (yaml.values.map { value ->
        TrainBehaviorActionDefinition(value.name, value.displayName, value.executable, value.requiresControl)
    }.associateBy { it.name } + scripted.associateBy { it.name }).values.toList()
