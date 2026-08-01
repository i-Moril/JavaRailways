package work.art1st.javarailways.bukkit.config.model

import org.joml.Quaterniond
import org.joml.Vector3d
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.render.AnimationDisplayState
import work.art1st.javarailways.bukkit.render.model.CompactTransform

internal fun Iterable<PartAnimationConfig>.toRuntimeAnimationMap(): Map<String, Animation> {
    return mapNotNull { config ->
        config.toRuntimeAnimationOrNull()?.let { config.name to it }
    }.toMap()
}

internal fun PartAnimationConfig.toRuntimeAnimationOrNull(): Animation? {
    val runtimeCommands = when {
        keyframes.isNotEmpty() -> keyframesToCommands(keyframes)
        commands.isNotEmpty() -> commands.map { command ->
            Animation.AnimationCommand(
                transform = command.toCompactTransform(),
                deltaT = command.duration,
                scene = command.scene,
                displayState = command.toDisplayStateOrNull()
            )
        }
        else -> emptyList()
    }

    if (runtimeCommands.isEmpty()) return null
    return Animation(
        commandList = runtimeCommands,
        keepFinalState = keepFinalState
    )
}

private fun keyframesToCommands(keyframes: List<PartAnimationKeyframeConfig>): List<Animation.AnimationCommand> {
    val sorted = keyframes.sortedBy { it.at }
    var previousAt = 0.0
    return sorted.map { keyframe ->
        val duration = (keyframe.at - previousAt).coerceAtLeast(0.0)
        previousAt = keyframe.at
        Animation.AnimationCommand(
            transform = keyframe.toCompactTransform(),
            deltaT = duration,
            scene = keyframe.scene,
            displayState = keyframe.toDisplayStateOrNull()
        )
    }
}

private fun PartAnimationCommandConfig.toCompactTransform(): CompactTransform {
    return CompactTransform(
        position = offset.toVector3d(),
        rotation = rotation.toQuaterniond(),
        scale = scale.toVector3d()
    )
}

private fun PartAnimationKeyframeConfig.toCompactTransform(): CompactTransform {
    return CompactTransform(
        position = offset.toVector3d(),
        rotation = rotation.toQuaterniond(),
        scale = scale.toVector3d()
    )
}

private fun PartAnimationCommandConfig.toDisplayStateOrNull(): AnimationDisplayState? = displayStateOrNull(
    itemType = itemType,
    itemModel = itemModel,
    customModelData = customModelData,
    brightness = brightness
)

private fun PartAnimationKeyframeConfig.toDisplayStateOrNull(): AnimationDisplayState? = displayStateOrNull(
    itemType = itemType,
    itemModel = itemModel,
    customModelData = customModelData,
    brightness = brightness
)

private fun displayStateOrNull(
    itemType: String?,
    itemModel: String?,
    customModelData: Int?,
    brightness: PartBrightnessConfig?
): AnimationDisplayState? {
    val state = AnimationDisplayState(
        itemType = itemType,
        itemModel = itemModel,
        customModelData = customModelData,
        brightnessBlock = brightness?.block,
        brightnessSky = brightness?.sky
    )
    return if (state.hasItemOverride || state.hasBrightnessOverride) state else null
}

private fun Vec3Config.toVector3d(): Vector3d = Vector3d(x, y, z)

private fun Vec3Config.toQuaterniond(): Quaterniond = Quaterniond().rotateXYZ(
    Math.toRadians(x),
    Math.toRadians(y),
    Math.toRadians(z)
)

private fun PartScaleConfig?.toVector3d(): Vector3d {
    return if (this == null) Vector3d(1.0, 1.0, 1.0) else Vector3d(x, y, z)
}
