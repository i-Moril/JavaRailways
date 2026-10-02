package work.art1st.javarailways.bukkit.render.model

import work.art1st.javarailways.bukkit.api.model.ModelAnimation
import work.art1st.javarailways.bukkit.api.model.ModelDefinition
import work.art1st.javarailways.bukkit.api.model.ModelPart

/** Pure, reusable index compiled before entity creation. Each submit visits only selected hits. */
internal class OrdinarySceneIndex(definition: ModelDefinition) {
    private class Hit(val path: String, val scenes: Map<String, ModelAnimation.Frame>)
    private val animations = buildMap<String, MutableList<Hit>> {
        fun visit(parts: List<ModelPart>, prefix: String) {
            parts.forEachIndexed { index, part ->
                val path = if (prefix.isEmpty()) "$index" else "$prefix/$index"
                for (animation in part.animations()) {
                    val scenes = animation.frames().mapNotNull { frame -> frame.scene()?.let { it to frame } }.toMap()
                    getOrPut(animation.name()) { mutableListOf() } += Hit(path, scenes)
                }
                if (part is ModelPart.Group) visit(part.children(), path)
            }
        }
        visit(definition.parts(), "")
    }

    class Selection internal constructor(
        val scenes: Map<String, String>,
        val frames: Map<String, ModelAnimation.Frame>
    )

    fun resolve(scenes: Map<String, String>): Selection {
        val candidate = java.util.Map.copyOf(scenes)
        val frames = linkedMapOf<String, ModelAnimation.Frame>()
        for ((name, scene) in candidate) {
            val hits = animations[name] ?: throw IllegalArgumentException("Unknown ordinary animation '$name'")
            for (hit in hits) {
                val frame = hit.scenes[scene] ?: throw IllegalArgumentException(
                    "Part ${hit.path} animation '$name' has no scene '$scene'"
                )
                require(frames.putIfAbsent(hit.path, frame) == null) {
                    "Part ${hit.path} is selected by more than one animation (including '$name')"
                }
            }
        }
        return Selection(candidate, java.util.Map.copyOf(frames))
    }
}
