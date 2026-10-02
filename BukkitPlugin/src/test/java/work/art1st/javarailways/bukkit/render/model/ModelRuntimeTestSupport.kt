package work.art1st.javarailways.bukkit.render.model

import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap

/** Initialize the cached Paper runtime's real registries through its normal server bootstrap. */
internal object ModelRuntimeTestSupport {
    @Synchronized
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }
}
