package work.art1st.mineRails.bukkit.render.model

import org.bukkit.entity.Display

internal const val TRAIN_DISPLAY_INTERPOLATION_TICKS = 1
internal const val TRAIN_DISPLAY_TELEPORT_TICKS = 1
internal const val TRAIN_ROOT_INTERPOLATION_TICKS = 0
internal const val TRAIN_ROOT_TELEPORT_TICKS = 0

internal fun Display.configureTrainDisplaySmoothing(
    interpolationTicks: Int = TRAIN_DISPLAY_INTERPOLATION_TICKS,
    teleportTicks: Int = TRAIN_DISPLAY_TELEPORT_TICKS
) {
    interpolationDelay = 0
    interpolationDuration = interpolationTicks
    teleportDuration = teleportTicks
}

internal fun Display.configureTrainRootSmoothing() {
    interpolationDelay = 0
    interpolationDuration = TRAIN_ROOT_INTERPOLATION_TICKS
    teleportDuration = TRAIN_ROOT_TELEPORT_TICKS
}
