package work.art1st.mineRails.core.api.physics.rail

import kotlinx.serialization.Serializable

/**
 * Stable identity of one operational object within a MineRails world.
 *
 * [namespace] identifies the owning module or plugin. [id] must remain stable across runtime object
 * replacement and persistence reloads. The pair is unique within one world; callers that combine
 * several worlds must carry the world identity separately.
 */
@Serializable
data class RailObjectRef(
    val namespace: String,
    val id: String
) {
    init {
        require(NAMESPACE.matches(namespace)) {
            "RailObjectRef namespace must match ${NAMESPACE.pattern}: $namespace"
        }
        require(id.isNotBlank() && id == id.trim()) {
            "RailObjectRef id must not be blank or contain surrounding whitespace"
        }
        require(id.none(Char::isISOControl)) {
            "RailObjectRef id must not contain control characters"
        }
    }

    override fun toString(): String = "$namespace:$id"

    private companion object {
        val NAMESPACE = Regex("[a-z0-9][a-z0-9._-]*")
    }
}
