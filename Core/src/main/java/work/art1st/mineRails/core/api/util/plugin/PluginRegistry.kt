package work.art1st.mineRails.core.api.util.plugin

/**
 * 各 [Plugin] 在启动时注册一次，顺序决定 [Plugin.identifier] 与 [Extendable] 中扩展槽位下标。
 * 在创建任何带扩展数据的宿主（例如物理世界 CoreWorld）之前必须先完成注册。
 */
object PluginRegistry {
    private val _plugins = mutableListOf<Plugin>()
    val plugins: List<Plugin> get() = _plugins

    fun register(plugin: Plugin) {
        require(_plugins.none { it === plugin }) { "Plugin already registered" }
        plugin.identifier = _plugins.size
        _plugins.add(plugin)
    }
}