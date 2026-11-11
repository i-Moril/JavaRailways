package work.art1st.javarailways.bukkit.api.wand.config

/**
 * 构建时逐键捕获的配置值和成员关系（只读），不再读取后续 registry/session 更改。
 * 捕获不是跨键原子事务。值按引用保存；调用方应使用不可变值，或替换可变值而不是原地修改。
 *
 * 供 [ConfigKey.visibleWhen] 和 [ConfigGroup.visibleWhen] 评估依赖项的值。
 * 实现由 [ConfigUI] 的默认实现在构建 [BuiltGroup] 时提供。
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
