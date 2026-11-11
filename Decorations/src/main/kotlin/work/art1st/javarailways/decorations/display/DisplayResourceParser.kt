package work.art1st.javarailways.decorations.display

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/** Reads the host's already captured immutable text map; never consults current package files. */
object DisplayResourceParser {
    fun parse(files: Map<String, String>, packageName: String): DisplayResources {
        val source = files["displays.yml"] ?: return DisplayResources()
        val subject = "package '$packageName' decorations:display resources/displays.yml"
        try {
            val root = mapping(Yaml(LoaderOptions().apply { isAllowDuplicateKeys = false }).load<Any?>(source), subject)
            onlyKeys(root, setOf("config-version", "templates", "defaults"), subject)
            val version = root["config-version"]
            require(version is Number && version.toDouble() == 1.0) { "$subject.config-version must be integer 1" }
            val templates = mapping(root["templates"], "$subject.templates").mapValues { (id, value) ->
                val templateSubject = "$subject.templates.$id"
                requireDisplayIdentifier(id, "$templateSubject ID")
                val entry = mapping(value, templateSubject)
                onlyKeys(entry, setOf("file"), templateSubject)
                val path = entry["file"] as? String ?: throw IllegalArgumentException("$templateSubject.file must be a string")
                requireDisplayXmlPath(path, "$templateSubject.file")
                val xml = requireNotNull(files[path]) { "$templateSubject.file '$path' was not captured in the declared resource directory" }
                DisplayTemplateSource(path, xml)
            }
            val defaults = if (root.containsKey("defaults")) mapping(root["defaults"], "$subject.defaults").mapValues { (category, value) ->
                requireDisplayIdentifier(category, "$subject.defaults category '$category'")
                value as? String ?: throw IllegalArgumentException("$subject.defaults.$category must be a template ID string")
            } else emptyMap()
            return DisplayResources(templates, defaults)
        } catch (failure: Exception) {
            throw IllegalArgumentException("Failed to load $subject: ${failure.message}", failure)
        }
    }

    private fun mapping(value: Any?, subject: String): Map<String, Any> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("$subject must be a YAML mapping")
        return map.entries.associate { (key, item) ->
            val name = key as? String ?: throw IllegalArgumentException("$subject contains non-string key '$key'")
            name to requireNotNull(item) { "$subject.$name cannot be null" }
        }
    }
    private fun onlyKeys(value: Map<String, Any>, allowed: Set<String>, subject: String) {
        require(value.keys.all { it in allowed }) { "$subject contains unknown keys ${(value.keys - allowed).sorted()}" }
    }
}
