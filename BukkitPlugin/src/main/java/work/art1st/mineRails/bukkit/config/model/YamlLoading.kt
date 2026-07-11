package work.art1st.mineRails.bukkit.config.model

import org.yaml.snakeyaml.Yaml
import java.io.File

internal fun loadYamlMap(file: File, subject: String): Map<String, Any> {
    val yaml = Yaml()
    val loaded = file.inputStream().use { yaml.load<Any?>(it) }
        ?: throw IllegalArgumentException("$subject is empty")
    val map = loaded as? Map<*, *>
        ?: throw IllegalArgumentException("$subject must be a YAML mapping")
    return map.toStringKeyMap(subject)
}

internal fun Map<*, *>.toStringKeyMap(subject: String): Map<String, Any> = buildMap {
    for ((key, value) in this@toStringKeyMap) {
        val stringKey = key as? String
            ?: throw IllegalArgumentException("$subject contains non-string key: $key")
        val typedValue = value
            ?: throw IllegalArgumentException("$subject contains null value for key '$stringKey'")
        put(stringKey, typedValue)
    }
}

internal fun Map<String, Any>.requireString(key: String, subject: String): String {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return value as? String ?: throw IllegalArgumentException("$subject.$key must be a string")
}

internal fun Map<String, Any>.requireInt(key: String, subject: String): Int {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return (value as? Number)?.toInt()
        ?: throw IllegalArgumentException("$subject.$key must be an integer")
}

internal fun Map<String, Any>.optionalMap(key: String, subject: String): Map<String, Any>? {
    if (!containsKey(key)) return null
    val value = this[key]
        ?: throw IllegalArgumentException("$subject.$key cannot be null")
    return (value as? Map<*, *>)?.toStringKeyMap("$subject.$key")
        ?: throw IllegalArgumentException("$subject.$key must be a map")
}

internal fun Map<String, Any>.optionalList(key: String, subject: String): List<*>? {
    if (!containsKey(key)) return null
    return this[key] as? List<*>
        ?: throw IllegalArgumentException("$subject.$key must be a list")
}
