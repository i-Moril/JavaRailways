package work.art1st.javarailways.bukkit.extension.train

import work.art1st.javarailways.core.api.util.plugin.ExtensionDataTypeRegistry

/** Registers current Bukkit DTO codecs before any world is restored; repeated startup is idempotent. */
object BukkitExtensionDataSerializers {
    fun register() {
        ExtensionDataTypeRegistry.global.register(
            "javarailways:car_render_state", 1, SavedCarRenderState::class, SavedCarRenderState.serializer()
        )
        ExtensionDataTypeRegistry.global.register(
            "javarailways:train_render_state", 1, SavedTrainRenderState::class, SavedTrainRenderState.serializer()
        )
    }
}
