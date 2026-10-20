package work.art1st.javarailways.basic.curve

import org.joml.Vector3d
import org.joml.Vector3dc
import java.util.Collections

/** 锚点：构造时独立保存空间参考点、切线和元数据。 */
class Anchor(
    val id: String,
    position: Vector3dc,
    tangent: Vector3dc,
    val worldId: String? = null,
    metadata: Map<String, String> = emptyMap()
) {
    val position: Vector3dc = Vector3d(position)
    val tangent: Vector3dc = Vector3d(tangent)
    val metadata: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(metadata))

    fun copy(
        id: String = this.id,
        position: Vector3dc = this.position,
        tangent: Vector3dc = this.tangent,
        worldId: String? = this.worldId,
        metadata: Map<String, String> = this.metadata
    ): Anchor = Anchor(id, position, tangent, worldId, metadata)

    fun withPosition(pos: Vector3dc): Anchor = copy(position = pos)
    fun withTangent(tan: Vector3dc): Anchor = copy(tangent = tan)

    override fun equals(other: Any?): Boolean = other is Anchor &&
        id == other.id && position == other.position && tangent == other.tangent &&
        worldId == other.worldId && metadata == other.metadata

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + tangent.hashCode()
        result = 31 * result + (worldId?.hashCode() ?: 0)
        return 31 * result + metadata.hashCode()
    }

    override fun toString(): String =
        "Anchor(id=$id, position=$position, tangent=$tangent, worldId=$worldId, metadata=$metadata)"
}
