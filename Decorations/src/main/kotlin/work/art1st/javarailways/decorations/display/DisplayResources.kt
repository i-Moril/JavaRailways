package work.art1st.javarailways.decorations.display

import java.util.Collections
import java.util.TreeMap

/** Raw XML only. No provider/compiler/service or filesystem objects are retained. */
data class DisplayTemplateSource(val file: String, val source: String) {
    init { requireDisplayXmlPath(file, "Display template file") }
}

class DisplayResources(
    templates: Map<String, DisplayTemplateSource> = emptyMap(),
    defaults: Map<String, String> = emptyMap(),
) {
    val templates: Map<String, DisplayTemplateSource> = Collections.unmodifiableMap(TreeMap(templates))
    val defaults: Map<String, String> = Collections.unmodifiableMap(TreeMap(defaults))

    init {
        this.templates.keys.forEach { requireDisplayIdentifier(it, "Display template ID") }
        this.defaults.forEach { (category, template) ->
            requireDisplayIdentifier(category, "Display defaults category")
            requireDisplayIdentifier(template, "Display defaults '$category' template")
            require(template in this.templates) { "Display defaults '$category' references unknown template '$template'" }
        }
    }
    override fun equals(other: Any?): Boolean = other is DisplayResources && templates == other.templates && defaults == other.defaults
    override fun hashCode(): Int = 31 * templates.hashCode() + defaults.hashCode()
}

internal fun requireDisplayIdentifier(value: String, subject: String) {
    require(value.isNotEmpty() && value.none(Char::isWhitespace)) { "$subject must be non-empty and contain no whitespace" }
}

internal fun requireDisplayXmlPath(value: String, subject: String) {
    require(value.isNotBlank() && value.endsWith(".xml") && '\\' !in value && !Regex("^[A-Za-z]:").containsMatchIn(value) &&
        value.split('/').all { it.isNotEmpty() && it != "." && it != ".." } && '\u0000' !in value) {
        "$subject must be a resource-relative XML path with ordinary forward-slash segments: '$value'"
    }
}
