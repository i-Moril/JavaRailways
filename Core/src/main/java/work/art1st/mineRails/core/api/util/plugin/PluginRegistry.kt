package work.art1st.mineRails.core.api.util.plugin

object PluginRegistry {
    private val _plugins = mutableListOf<Plugin>()
    val plugins: List<Plugin> get() = _plugins

    fun register(plugin: Plugin) {
        plugin.identifier = _plugins.size
        _plugins.add(plugin)
    }
}