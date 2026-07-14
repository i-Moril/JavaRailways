package work.art1st.mineRails.core.api.util.plugin

/**
 * Lifecycle contract for data attached to a MineRails host object.
 *
 * @param T host type, such as a world, train, or car
 */
interface Extension<T> {
    /**
     * Attach this extension to [host] and restore extension-owned data.
     *
     * The host controls the calling thread. Implementations that touch a platform API must first
     * cross that platform's main-thread boundary.
     */
    fun initialize(host: T, savedExtensionData: Map<String, SerializableExtensionData>)

    /**
     * Return a stable extension ID and serializable snapshot, or `null` when this extension has no
     * persistent state. IDs become part of saved world data and must not be renamed without migration.
     */
    fun save(): Pair<String, SerializableExtensionData>?
}
