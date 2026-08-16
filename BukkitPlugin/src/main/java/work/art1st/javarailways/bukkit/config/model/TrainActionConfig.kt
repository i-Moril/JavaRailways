package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Declarative train action loaded from actions.yml.
 * Actions are business-level operations such as door-left-open and can trigger
 * multiple low-level animations and sounds.
 */
@Serializable
data class TrainActionConfig(
    val name: String,
    val displayName: String = name,
    val executable: Boolean = false,
    val requiresControl: Boolean = false,
    val animations: List<TrainActionAnimationConfig> = emptyList(),
    val sounds: List<TrainActionSoundConfig> = emptyList()
) {
    companion object {
        const val CONFIG_VERSION = 1

        fun parseActions(root: Map<String, Any>?): Map<String, TrainActionConfig> {
            val source = root ?: throw IllegalArgumentException("train actions config is required")
            source.requireOnlyKeys(setOf("config-version", "actions"), "train actions")
            val version = source.requireInt("config-version", "train actions")
            require(version == CONFIG_VERSION) {
                "Unsupported train actions config-version: $version (expected $CONFIG_VERSION)"
            }
            val actionsMap = source.requireMap("actions", "train actions")
            return actionsMap.entries.associate { (name, rawValue) ->
                require(name.isNotBlank()) { "train actions contains a blank action name" }
                val actionMap = (rawValue as? Map<*, *>)?.toStringKeyMap("train actions.$name")
                    ?: throw IllegalArgumentException("train actions.$name must be a map")
                actionMap.requireOnlyKeys(
                    setOf("display-name", "executable", "requires-control", "animations", "sounds"),
                    "train actions.$name"
                )
                name to TrainActionConfig(
                    name = name,
                    displayName = actionMap.optionalStringValue("display-name", "train actions.$name") ?: name,
                    executable = actionMap.optionalBooleanValue("executable", "train actions.$name") ?: false,
                    requiresControl = actionMap.optionalBooleanValue("requires-control", "train actions.$name") ?: false,
                    animations = parseAnimations(actionMap.optionalList("animations", "train actions.$name"), name),
                    sounds = parseSounds(actionMap.optionalList("sounds", "train actions.$name"), name)
                )
            }
        }

        private fun parseAnimations(list: List<*>?, actionName: String): List<TrainActionAnimationConfig> {
            if (list == null) return emptyList()
            return list.mapIndexed { index, entry ->
                val subject = "train actions.$actionName.animations[$index]"
                val map = (entry as? Map<*, *>)?.toStringKeyMap(subject)
                    ?: throw IllegalArgumentException("$subject must be a map")
                map.requireOnlyKeys(setOf("name", "scene", "at"), subject)
                val name = map.requireString("name", subject)
                require(name.isNotBlank()) { "$subject.name must not be blank" }
                TrainActionAnimationConfig(
                    name = name,
                    scene = normalizeSceneName(map.optionalStringValue("scene", subject)),
                    at = map.optionalDoubleValue("at", subject) ?: 0.0
                )
            }
        }

        private fun parseSounds(list: List<*>?, actionName: String): List<TrainActionSoundConfig> {
            if (list == null) return emptyList()
            return list.mapIndexed { index, entry ->
                val subject = "train actions.$actionName.sounds[$index]"
                val map = (entry as? Map<*, *>)?.toStringKeyMap(subject)
                    ?: throw IllegalArgumentException("$subject must be a map")
                map.requireOnlyKeys(setOf("key", "volume", "pitch", "at"), subject)
                val key = map.requireString("key", subject)
                require(key.isNotBlank()) { "$subject.key must not be blank" }
                TrainActionSoundConfig(
                    key = key,
                    volume = map.optionalDoubleValue("volume", subject) ?: 1.0,
                    pitch = map.optionalDoubleValue("pitch", subject) ?: 1.0,
                    at = map.optionalDoubleValue("at", subject) ?: 0.0
                )
            }
        }

        private fun normalizeSceneName(scene: String?): String? =
            scene?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    }
}

@Serializable
data class TrainActionAnimationConfig(
    val name: String,
    val scene: String? = null,
    /** Delay in seconds from action start. */
    val at: Double = 0.0
)

@Serializable
data class TrainActionSoundConfig(
    val key: String,
    val volume: Double = 1.0,
    val pitch: Double = 1.0,
    /** Delay in seconds from action start. */
    val at: Double = 0.0
)
