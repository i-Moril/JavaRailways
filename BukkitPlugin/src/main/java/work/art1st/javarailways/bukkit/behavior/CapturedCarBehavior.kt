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
    val animationParts: List<Map<String, work.art1st.javarailways.bukkit.render.Animation>>
) {
    val animations: Map<String, Set<String>> get() = animationParts
        .flatMap { it.entries }.groupBy { it.key }
        .mapValues { (_, entries) -> entries.flatMap { it.value.sceneNames() }.toSet() }
}

/** Uses the actual model converter and scene index; never builds or activates displays. */
internal fun SavedCarModelTree.captureAnimationParts(): List<Map<String, work.art1st.javarailways.bukkit.render.Animation>> {
    val resolver = partResolver()
    val result = mutableListOf<Map<String, work.art1st.javarailways.bukkit.render.Animation>>()
    fun collect(entries: List<PartRefEntry>, namespace: String) {
        entries.forEach { entry ->
            val part = entry.inline ?: resolver.resolve(entry.name, namespace).resolved
            val animations = part.animations.toRuntimeAnimationMap()
            if (animations.isNotEmpty()) result.add(animations)
            collect(part.parts, part.namespace)
        }
    }
    collect(car.bodyParts, car.namespace)
    car.bogies.forEach { bogie ->
        collect(bogie.parts, car.namespace)
        collect(bogie.wheelParts, car.namespace)
    }
    return result.toList()
}

internal fun SavedCarModelTree.captureAnimationCapabilities(): Map<String, Set<String>> =
    captureAnimationParts().flatMap { it.entries }.groupBy { it.key }
        .mapValues { (_, entries) -> entries.flatMap { it.value.sceneNames() }.toSet() }

/** Query a detached model capability without starting an effect or requiring a visible model. */
internal fun Map<String, Set<String>>.supportsAnimation(name: String, scene: String?): Boolean {
    val scenes = get(name) ?: return false
    return scene == null || work.art1st.javarailways.bukkit.render.Animation.normalizeSceneName(scene) in scenes
}
