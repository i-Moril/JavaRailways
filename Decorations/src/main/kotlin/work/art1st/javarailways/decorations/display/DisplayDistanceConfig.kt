package work.art1st.javarailways.decorations.display

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Does not use Bukkit's coercing number accessors or merge implicit configuration defaults. */
object DisplayDistanceConfig {
    fun parse(source: String): DisplayDistanceSettings {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val root = mapping(Yaml(SafeConstructor(options)).load<Any?>(source), "config", setOf("displays"))
        val displays = mapping(root["displays"], "displays", setOf("interior-distance"))
        val distance = mapping(displays["interior-distance"], "displays.interior-distance", setOf("default-blocks", "max-blocks"))
        fun number(key: String): Double = (distance[key] as? Number)?.toDouble()
            ?: throw IllegalArgumentException("displays.interior-distance.$key must be a number")
        return DisplayDistanceSettings(number("default-blocks"), number("max-blocks"))
    }

    private fun mapping(value: Any?, subject: String, keys: Set<String>): Map<*, *> {
        val map = value as? Map<*, *> ?: throw IllegalArgumentException("$subject must be a YAML mapping")
        require(map.keys.all { it is String && it in keys }) { "$subject contains unknown keys ${map.keys - keys}" }
        return map
    }
}
