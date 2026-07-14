package work.art1st.mineRails.core.api.util.plugin

/**
 * Identifies one extension namespace in an [ExtensionDataContainer].
 *
 * [identifier] is assigned by [PluginRegistry.register] during startup. It is a runtime slot index,
 * not a persistent plugin ID, and must not be chosen or changed by extension code after registration.
 * The current registry is intended for MineRails modules; a stable third-party registration facade
 * has not been published yet.
 */
interface Plugin {
    var identifier: Int
}
