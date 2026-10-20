package work.art1st.javarailways.bukkit.behavior

import work.art1st.javarailways.bukkit.config.model.SavedCarModelTree
import work.art1st.javarailways.bukkit.config.model.TrainActionConfig
import work.art1st.javarailways.bukkit.config.model.TrainBehaviorScript
import work.art1st.javarailways.bukkit.config.model.PartRefEntry
import work.art1st.javarailways.bukkit.config.model.toRuntimeAnimationMap

/** Source values only: no model, Context, live owner or deferred resource lookup. */
internal data class CapturedCarBehavior(
    val script: TrainBehaviorScript?,
    val actions: Map<String, TrainActionConfig>,
    val animations: Map<String, Set<String>>
)

/** Uses the actual model converter and scene index; never builds or activates displays. */
internal fun SavedCarModelTree.captureAnimationCapabilities(): Map<String, Set<String>> {
    val resolver = partResolver()
    val result = linkedMapOf<String, MutableSet<String>>()
    fun collect(entries: List<PartRefEntry>, namespace: String) {
        entries.forEach { entry ->
            val part = entry.inline ?: resolver.resolve(entry.name, namespace).resolved
            part.animations.toRuntimeAnimationMap().forEach { (name, animation) ->
                result.getOrPut(name) { linkedSetOf() }.addAll(animation.sceneNames())
            }
            collect(part.parts, part.namespace)
        }
    }
    collect(car.bodyParts, car.namespace)
    car.bogies.forEach { bogie ->
        collect(bogie.parts, car.namespace)
        collect(bogie.wheelParts, car.namespace)
    }
    return result.mapValues { (_, scenes) -> scenes.toSet() }
}
