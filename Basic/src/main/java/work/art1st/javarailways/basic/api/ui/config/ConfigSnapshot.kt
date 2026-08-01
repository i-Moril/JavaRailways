package work.art1st.javarailways.basic.api.ui.config

/**
 * 配置值快照（只读）。
 *
 * 供 [ConfigKey.visibleWhen] 和 [ConfigGroup.visibleWhen] 评估依赖项的值。
 * 实现由 [ConfigUIRegistry] 在构建 [BuiltGroup] 时提供。
 */
interface ConfigSnapshot {
    /**
     * 按 key ID 获取值。不存在时返回 null。
     */
    fun <T> get(keyId: String): T?

    /**
     * 按 key ID 获取值，不存在时返回 [default]。
     */
    fun <T> getOrDefault(keyId: String, default: T): T

    /**
     * 检查 key ID 是否存在。
     */
    fun contains(keyId: String): Boolean
}
