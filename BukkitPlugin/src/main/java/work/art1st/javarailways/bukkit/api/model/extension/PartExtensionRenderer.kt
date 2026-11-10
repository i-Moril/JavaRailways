package work.art1st.javarailways.bukkit.api.model.extension

import net.kyori.adventure.text.Component
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import java.util.Collections

/** Optional runtime capability. Called once per attachment on a host worker, never a render owner. */
interface PartExtensionRenderer : PartExtensionProvider {
    fun prepare(context: PartExtensionRenderContext): PartExtensionFrame
}

/** Exact owning car package snapshot. No filesystem, world, car or backend objects are exposed. */
class PartExtensionRenderContext(
    val packageName: String,
    val packageHash: String,
    val part: PartExtensionPart,
    val resources: PartExtensionResources?
)

class PartExtensionFrame(texts: List<PartExtensionText>) {
    val texts: List<PartExtensionText> = Collections.unmodifiableList(ArrayList(texts))
    init { require(this.texts.size <= 1024) { "An extension frame may contain at most 1024 text primitives" } }
    companion object { val EMPTY = PartExtensionFrame(emptyList()) }
}

class PartExtensionText(
    val text: Component,
    val pose: ModelPose = ModelPose.IDENTITY,
    val scale: ModelScale = ModelScale.UNIT
)
