package work.art1st.javarailways.core.api.util.plugin

/**
 * 各 [Plugin] 在启动时注册一次，顺序决定 [Plugin.identifier] 与 [Extendable] 中扩展槽位下标。
 * 在创建任何带扩展数据的宿主（例如物理世界 CoreWorld）之前必须先完成注册。
 */
object PluginRegistry {
    private val _plugins = mutableListOf<Plugin>()
    val plugins: List<Plugin>
        @Synchronized get() = _plugins.toList()

    /**
     * Registers one extension namespace per plugin implementation class.
     * Re-registering after a host lifecycle restart reuses the stable slot and replaces the stale instance.
     */
    @Synchronized
    fun register(plugin: Plugin) {
        val existingIndex = _plugins.indexOfFirst { it::class == plugin::class }
        if (existingIndex >= 0) {
            plugin.identifier = existingIndex
            _plugins[existingIndex] = plugin
            return
        }
        plugin.identifier = _plugins.size
        _plugins.add(plugin)
    }
}