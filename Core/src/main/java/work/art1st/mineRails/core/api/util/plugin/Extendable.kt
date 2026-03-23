package work.art1st.mineRails.core.api.util.plugin

/**
 * 扩展数据由宿主在创建时通过 [registerExtensionData] 按 [PluginRegistry] 中注册的 [Plugin] 写入。
 */
interface Extendable<ExtensionDataBase> {
    @ExtensionData
    fun <T : ExtensionDataBase> getExtensionData(plugin: Plugin) : T

    /**
     * 将某 [Plugin] 对应的扩展数据挂到当前宿主上（与 [PluginRegistry.register] 的 identifier 对齐）。
     */
    fun <T : ExtensionDataBase> registerExtensionData(plugin: Plugin, data: T)

    /**
     * Only apply on non-null extension data
     */
    fun forEachExtensionData(action: (ExtensionDataBase) -> Unit)
    fun <T> forEachExtensionData(action: (ExtensionDataBase) -> T) : List<T>
}