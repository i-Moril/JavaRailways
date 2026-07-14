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

internal fun Map<String, Any>.requireOnlyKeys(allowed: Set<String>, subject: String) {
    val unknown = keys - allowed
    require(unknown.isEmpty()) {
        "$subject contains unknown key(s): ${unknown.sorted().joinToString()}"
    }
}

internal fun Map<String, Any>.requireString(key: String, subject: String): String {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return value as? String ?: throw IllegalArgumentException("$subject.$key must be a string")
}

internal fun Map<String, Any>.requireInt(key: String, subject: String): Int {
    val value = this[key] ?: throw IllegalArgumentException("$subject is missing required key '$key'")
    return value.toStrictInt("$subject.$key")
}

internal fun Map<String, Any>.optionalStringValue(key: String, subject: String): String? {
    val value = this[key] ?: return null
    return value as? String ?: throw IllegalArgumentException("$subject.$key must be a string")
}

internal fun Map<String, Any>.optionalDoubleValue(key: String, subject: String): Double? {
    val value = this[key] ?: return null
    val number = value as? Number ?: throw IllegalArgumentException("$subject.$key must be a number")
    return number.toDouble().also {
        require(it.isFinite()) { "$subject.$key must be finite" }
    }
}

internal fun Map<String, Any>.optionalIntValue(key: String, subject: String): Int? {
    val value = this[key] ?: return null
    return value.toStrictInt("$subject.$key")
}

internal fun Map<String, Any>.optionalBooleanValue(key: String, subject: String): Boolean? {
    val value = this[key] ?: return null
    return value as? Boolean ?: throw IllegalArgumentException("$subject.$key must be a boolean")
}

private fun Any.toStrictInt(subject: String): Int {
    val number = this as? Number ?: throw IllegalArgumentException("$subject must be an integer")
    val doubleValue = number.toDouble()
    require(doubleValue.isFinite() && doubleValue % 1.0 == 0.0) { "$subject must be an integer" }
    require(doubleValue >= Int.MIN_VALUE.toDouble() && doubleValue <= Int.MAX_VALUE.toDouble()) {
        "$subject is outside the supported integer range"
    }
    return doubleValue.toInt()
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
