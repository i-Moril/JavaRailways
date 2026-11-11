package work.art1st.javarailways.bukkit.api.model.extension

import kotlinx.serialization.json.JsonObject
import net.kyori.adventure.text.Component
import work.art1st.javarailways.bukkit.api.model.ModelPose
import work.art1st.javarailways.bukkit.api.model.ModelScale
import java.util.Collections
import java.util.UUID

/** Optional runtime capability. One private session is opened on a host worker per attachment. */
interface PartExtensionRenderer : PartExtensionProvider {
    fun open(context: PartExtensionRenderContext): PartExtensionRenderSession?
}

/** Cheap immutable policy; called concurrently on model/viewer owners, without platform/domain objects.
 * Null follows the car audience. Zero hides generated content. Positive values are blocks from
 * the viewer eye to the fully transformed part origin; original part children are unaffected.
 */
fun interface PartExtensionAudiencePolicy {
    fun maximumDistanceBlocks(viewerId: UUID): Double?
    companion object { val UNRESTRICTED = PartExtensionAudiencePolicy { null } }
}

/** Open, draw and close are serialized on host workers, outside registry and attachment locks. */
interface PartExtensionRenderSession : AutoCloseable {
    /** Published once after open. Implementations must provide a thread-safe value-only policy. */
    val audiencePolicy: PartExtensionAudiencePolicy get() = PartExtensionAudiencePolicy.UNRESTRICTED
    /** Cheap metadata routing on the same serial worker, including when no viewer requests a draw. */
    fun update(input: PartExtensionRenderInput) = Unit
    /** Nonnegative, thread-safe revision for effective rendering selection; no platform access. */
    val renderRevision: Long get() = 0L
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
    val resources: PartExtensionResources?,
    val worldId: UUID? = null,
)

class PartExtensionFrame(texts: List<PartExtensionText>) {
    val texts: List<PartExtensionText> = Collections.unmodifiableList(ArrayList(texts))
    init {
        require(this.texts.size <= 1024) { "An extension frame may contain at most 1024 text primitives" }
        require(this.texts.map { it.key }.toSet().size == this.texts.size) { "Extension text keys must be unique within a frame" }
    }
    companion object { val EMPTY = PartExtensionFrame(emptyList()) }
}

class PartExtensionText(
    val text: Component,
    val pose: ModelPose = ModelPose.IDENTITY,
    val scale: ModelScale = ModelScale.UNIT,
    /** Stable logical identity within one attachment, independent of content and draw order. */
    val key: String
) {
    init { require(key.isNotBlank() && key.length <= 1024) { "Extension text key must be nonblank and at most 1024 characters" } }
}
