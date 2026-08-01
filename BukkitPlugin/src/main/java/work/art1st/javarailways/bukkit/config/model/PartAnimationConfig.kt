package work.art1st.javarailways.bukkit.config.model

import kotlinx.serialization.Serializable

/**
 * Animation configuration for parts.
 *
 * [commands] is a compact relative-time sequence. [keyframes] is an absolute
 * timeline and may tag frames with scene names such as `closed` / `open`.
 */
@Serializable
data class PartAnimationConfig(
    val name: String,
    val keepFinalState: Boolean = true,
    val commands: List<PartAnimationCommandConfig> = emptyList(),
    val keyframes: List<PartAnimationKeyframeConfig> = emptyList()
)

/**
 * A relative animation command. [duration] is seconds from the previous command.
 */
@Serializable
data class PartAnimationCommandConfig(
    val duration: Double,
    val offset: Vec3Config = Vec3Config(),
    val rotation: Vec3Config = Vec3Config(),
    val scale: PartScaleConfig? = null,
    val scene: String? = null,
    val itemType: String? = null,
    val itemModel: String? = null,
    val customModelData: Int? = null,
    val brightness: PartBrightnessConfig? = null
)

/**
 * An absolute animation keyframe. [at] is seconds from the start of the timeline.
 */
@Serializable
data class PartAnimationKeyframeConfig(
    val at: Double,
    val scene: String? = null,
    val offset: Vec3Config = Vec3Config(),
    val rotation: Vec3Config = Vec3Config(),
    val scale: PartScaleConfig? = null,
    val itemType: String? = null,
    val itemModel: String? = null,
    val customModelData: Int? = null,
    val brightness: PartBrightnessConfig? = null
)

/**
 * Display brightness override for display-entity animations.
 */
@Serializable
data class PartBrightnessConfig(
    val block: Int = 0,
    val sky: Int = 0
)
