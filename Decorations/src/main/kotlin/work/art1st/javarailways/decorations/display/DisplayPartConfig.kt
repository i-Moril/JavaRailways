package work.art1st.javarailways.decorations.display

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Display installation metadata only: centre in local XY, facing +Z, dimensions in metres. */
data class DisplayPartConfig(
    val category: String,
    val interior: Boolean,
    val width: Double,
    val height: Double,
    val template: String? = null,
    val doorSide: String? = null,
) {
    init {
        require(doorSide == null || doorSide == "left" || doorSide == "right") { "display.door-side must be left or right" }
        requireDisplayIdentifier(category, "display.category")
        template?.let { requireDisplayIdentifier(it, "display.template") }
        require(width.isFinite() && width > 0) { "display.width must be finite and positive" }
        require(height.isFinite() && height > 0) { "display.height must be finite and positive" }
    }

    companion object {
        fun parse(config: JsonObject, subject: String): DisplayPartConfig {
            val keys = setOf("category", "interior", "width", "height")
            require(config.keys.containsAll(keys) && config.keys.all { it in keys || it == "template" || it == "door-side" }) {
                "$subject requires category, interior, width, height and permits optional template and door-side (received ${config.keys.sorted()})"
            }
            fun primitive(key: String): JsonPrimitive = config[key] as? JsonPrimitive
                ?: throw IllegalArgumentException("$subject.$key must be a scalar")
            val category = primitive("category").let {
                require(it.isString) { "$subject.category must be a string" }; it.content
            }
            val interior = primitive("interior").let {
                require(!it.isString) { "$subject.interior must be a boolean" }
                requireNotNull(it.booleanOrNull) { "$subject.interior must be a boolean" }
            }
            val template = if ("template" in config) primitive("template").let {
                require(it.isString) { "$subject.template must be a string" }; it.content
            } else null
            val doorSide = if ("door-side" in config) primitive("door-side").let {
                require(it.isString) { "$subject.door-side must be a string" }; it.content
            } else null
            fun dimension(key: String): Double = primitive(key).let {
                require(!it.isString) { "$subject.$key must be a number" }
                requireNotNull(it.doubleOrNull) { "$subject.$key must be a number" }
            }
            return try { DisplayPartConfig(category, interior, dimension("width"), dimension("height"), template, doorSide) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("$subject: ${failure.message}", failure) }
        }
    }
}
