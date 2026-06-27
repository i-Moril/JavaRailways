package work.art1st.mineRails.bukkit.render.model

import org.bukkit.entity.Display

internal fun Display.configureTrainDisplaySmoothing() {
    interpolationDelay = 0
    interpolationDuration = 1
    teleportDuration = 1
}
