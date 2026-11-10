package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Display installation metadata only: centre in local XY, facing +Z, dimensions in metres. */
data class DisplayPartConfig(val category: String, val interior: Boolean, val width: Double, val height: Double) {
    init {
        requireDisplayIdentifier(category, "display.category")
        require(width.isFinite() && width > 0) { "display.width must be finite and positive" }
        require(height.isFinite() && height > 0) { "display.height must be finite and positive" }
    }

    companion object {
        fun parse(config: JsonObject, subject: String): DisplayPartConfig {
            val keys = setOf("category", "interior", "width", "height")
            require(config.keys == keys) { "$subject requires exactly category, interior, width, height (received ${config.keys.sorted()})" }
            fun primitive(key: String): JsonPrimitive = config[key] as? JsonPrimitive
                ?: throw IllegalArgumentException("$subject.$key must be a scalar")
            val category = primitive("category").let {
                require(it.isString) { "$subject.category must be a string" }; it.content
            }
            val interior = primitive("interior").let {
                require(!it.isString) { "$subject.interior must be a boolean" }
                requireNotNull(it.booleanOrNull) { "$subject.interior must be a boolean" }
            }
            fun dimension(key: String): Double = primitive(key).let {
                require(!it.isString) { "$subject.$key must be a number" }
                requireNotNull(it.doubleOrNull) { "$subject.$key must be a number" }
            }
            return try { DisplayPartConfig(category, interior, dimension("width"), dimension("height")) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("$subject: ${failure.message}", failure) }
        }
    }
}
