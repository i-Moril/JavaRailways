package work.art1st.javarailways.bukkit.api.model.extension

import kotlinx.serialization.json.JsonObject
import net.kyori.adventure.text.Component
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import java.util.Collections

/** Optional runtime capability. One private session is opened on a host worker per attachment. */
interface PartExtensionRenderer : PartExtensionProvider {
    fun open(context: PartExtensionRenderContext): PartExtensionRenderSession?
}

/** Open, draw and close are serialized on host workers, outside registry and attachment locks. */
interface PartExtensionRenderSession : AutoCloseable {
    fun draw(input: PartExtensionRenderInput): PartExtensionFrame
    override fun close()
}

class PartExtensionRenderInput(data: JsonObject = JsonObject(emptyMap()), val elapsedTicks: Long = 0L) {
    val data: JsonObject = immutablePartExtensionJson(data, 0) as JsonObject
    init { require(elapsedTicks >= 0L) { "Extension elapsed ticks must be nonnegative" } }
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
