package work.art1st.javarailways.core.api.util.plugin

/**
 * Registers extension namespaces before creating hosts such as physics worlds.
 * Registration order assigns stable [Plugin.identifier] extension slots, including retired slots.
 */
object PluginRegistry {
    // Retired entries retain only a name, never an implementation Class or its classloader.
    private class Slot(val implementationName: String, var owner: Plugin?)
    private val slots = mutableListOf<Slot>()

    /** Active owners in slot order. List indices are not extension slot identifiers. */
    val plugins: List<Plugin>
        @Synchronized get() = slots.mapNotNull { it.owner }

    /**
     * Registers one extension namespace per plugin implementation name.
     * Re-registering after a host lifecycle restart reuses its slot and replaces the stale instance.
     */
    @Synchronized
    fun register(plugin: Plugin) {
        val implementationName = plugin.javaClass.name
        val existingIndex = slots.indexOfFirst { it.implementationName == implementationName }
        if (existingIndex >= 0) {
            plugin.identifier = existingIndex
            slots[existingIndex].owner = plugin
            return
        }
        plugin.identifier = slots.size
        slots.add(Slot(implementationName, plugin))
    }

    /**
     * Releases this exact owner after its extension hosts have stopped. Slots never shift, and a
     * delayed cleanup from an older instance cannot unregister its replacement. Repeated calls are safe.
     */
    @Synchronized
    fun unregister(owner: Plugin) {
        slots.firstOrNull { it.owner === owner }?.owner = null
    }
}
