package work.art1st.mineRails.bukkit.config.model

/**
 * Declarative train action loaded from actions.yml.
 * Actions are business-level operations such as door-left-open and can trigger
 * multiple low-level animations and sounds.
 */
data class TrainActionConfig(
    val name: String,
    val animations: List<TrainActionAnimationConfig> = emptyList(),
    val sounds: List<TrainActionSoundConfig> = emptyList()
) {
    companion object {
        fun parseActions(map: Map<String, Any>?): Map<String, TrainActionConfig> {
            if (map == null) return emptyMap()
            val actionsMap = map["actions"] as? Map<*, *> ?: return emptyMap()
            return actionsMap.mapNotNull { (key, value) ->
                val name = key as? String ?: return@mapNotNull null
                val actionMap = value as? Map<*, *> ?: return@mapNotNull null
                name to TrainActionConfig(
                    name = name,
                    animations = parseAnimations(actionMap["animations"] as? List<*>),
                    sounds = parseSounds(actionMap["sounds"] as? List<*>)
                )
            }.toMap()
        }

        private fun parseAnimations(list: List<*>?): List<TrainActionAnimationConfig> {
            if (list == null) return emptyList()
            return list.mapNotNull { entry ->
                when (entry) {
                    is String -> TrainActionAnimationConfig(name = entry)
                    is Map<*, *> -> {
                        val name = entry["name"] as? String ?: return@mapNotNull null
                        TrainActionAnimationConfig(
                            name = name,
                            scene = normalizeSceneName(entry["scene"] as? String),
                            at = (entry["at"] as? Number)?.toDouble() ?: 0.0
                        )
                    }
                    else -> null
                }
            }
        }

        private fun parseSounds(list: List<*>?): List<TrainActionSoundConfig> {
            if (list == null) return emptyList()
            return list.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                val key = map["key"] as? String ?: return@mapNotNull null
                TrainActionSoundConfig(
                    key = key,
                    volume = (map["volume"] as? Number)?.toDouble() ?: 1.0,
                    pitch = (map["pitch"] as? Number)?.toDouble() ?: 1.0,
                    at = (map["at"] as? Number)?.toDouble() ?: 0.0
                )
            }
        }

        private fun normalizeSceneName(scene: String?): String? {
            return when (val normalized = scene?.trim()?.lowercase()) {
                null, "" -> null
                "o" -> "open"
                "c" -> "closed"
                else -> normalized
            }
        }
    }
}

data class TrainActionAnimationConfig(
    val name: String,
    val scene: String? = null,
    /** Delay in seconds from action start. */
    val at: Double = 0.0
)

data class TrainActionSoundConfig(
    val key: String,
    val volume: Double = 1.0,
    val pitch: Double = 1.0,
    /** Delay in seconds from action start. */
    val at: Double = 0.0
)
