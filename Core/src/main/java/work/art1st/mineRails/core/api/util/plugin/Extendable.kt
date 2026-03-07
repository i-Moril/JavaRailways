package work.art1st.mineRails.core.api.util.plugin

/**
 * TODO: Extendable初始化时初始化Extension数据
 */
interface Extendable<ExtensionDataBase> {
    @ExtensionData
    fun <T : ExtensionDataBase> getExtensionData(plugin: Plugin) : T

    /**
     * Only apply on non-null extension data
     */
    fun forEachExtensionData(action: (ExtensionDataBase) -> Unit)
    fun <T> forEachExtensionData(action: (ExtensionDataBase) -> T) : List<T>
}