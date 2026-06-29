package work.art1st.mineRails.basic.api.ui.config

import java.util.UUID

/**
 * 配置 UI 注册中心。
 *
 * 所有配置系统的入口。提供注册、值访问、分组构建、玩家会话等功能。
 *
 * 用法：
 * 1. 在插件启动时调用 [register] 注册分组和项
 * 2. 运行时通过 [get]/[getOrDefault] 读取配置值
 * 3. 需要展示 Dialog 时调用 [buildGroupForPlayer]
 * 4. 其他插件通过 [registerToGroup] 贡献项，通过 [addBuildListener] 动态注入
 */
interface ConfigUI {
    // ═══════════════════════════════════════════
    // 注册
    // ═══════════════════════════════════════════

    /**
     * 注册一个配置分组。
     *
     * 如果同名分组已存在，会将现有分组的 items 合并到其中。
     */
    fun register(group: ConfigGroup)

    /**
     * 注册一个配置项。根据 [ConfigKey.group] 自动归属到对应分组。
     */
    fun register(item: ConfigItem)

    /**
     * 注册一组配置项。
     */
    fun register(items: Sequence<ConfigItem>)

    /**
     * 向已有分组贡献一个配置项（扩展点注册）。
     *
     * 目标分组必须存在且 [ConfigGroup.allowExtensions] 为 true。
     * 如果分组不存在，此项会被暂存，等分组注册后再合并。
     *
     * @param groupId 目标分组 ID
     * @param item 要贡献的配置项
     */
    fun registerToGroup(groupId: String, item: ConfigItem)

    /**
     * 向已有分组贡献一个子分组（扩展点注册）。
     *
     * @param groupId 目标分组 ID
     * @param subGroup 要贡献的子分组
     */
    fun registerToGroup(groupId: String, subGroup: ConfigGroup)

    // ═══════════════════════════════════════════
    // 值访问
    // ═══════════════════════════════════════════

    /**
     * 按 [ConfigKey] 获取全局配置值。未设置时返回 null。
     */
    fun <T> get(key: ConfigKey<T>): T?

    /**
     * 按 [ConfigKey] 获取全局配置值。未设置时返回 [ConfigKey.defaultValue]。
     */
    fun <T> getOrDefault(key: ConfigKey<T>): T

    /**
     * 以编程方式设置全局配置值。触发 [onValueChanged] 回调。
     */
    fun <T> setValue(key: ConfigKey<T>, value: T)

    // ═══════════════════════════════════════════
    // 查找
    // ═══════════════════════════════════════════

    /**
     * 按 key ID 查找配置项。返回 null 如果不存在。
     */
    fun getItem(keyId: String): ConfigItem?

    /**
     * 按 ID 查找分组。返回 null 如果不存在。
     */
    fun getGroup(groupId: String): ConfigGroup?

    /**
     * 获取所有已注册的分组。
     */
    fun getAllGroups(): List<ConfigGroup>

    /**
     * 获取分组的所有有效项（展平：自身 items + 子组 items + 外部贡献）。
     * 使用全局默认快照评估可见性。
     */
    fun getEffectiveItems(groupId: String): List<ConfigItem>

    // ═══════════════════════════════════════════
    // 预渲染事件
    // ═══════════════════════════════════════════

    /**
     * 添加分组构建监听器。在每次 [buildGroupForPlayer] 时触发。
     *
     * @param groupId 目标分组 ID
     * @param listener 监听器
     */
    fun addBuildListener(groupId: String, listener: ConfigGroupBuildListener)

    /**
     * 移除分组构建监听器。
     */
    fun removeBuildListener(groupId: String, listener: ConfigGroupBuildListener)

    /**
     * 为目标玩家构建分组（展平 + 合并 + 评估可见性 + 触发事件）。
     *
     * @param groupId 分组 ID
     * @param playerId 目标玩家 UUID
     * @param context 上下文，如 mapOf("editing" to true)
     * @return 构建结果（包含有效项、隐藏项、值快照）
     */
    fun buildGroupForPlayer(
        groupId: String,
        playerId: UUID,
        context: Map<String, Any> = emptyMap()
    ): BuiltGroup

    // ═══════════════════════════════════════════
    // 值变化回调
    // ═══════════════════════════════════════════

    /**
     * 当配置值变更时调用的回调。
     *
     * @param key 变更的键
     * @param oldValue 旧值（可能为 null）
     * @param newValue 新值（可能为 null）
     */
    fun onValueChanged(key: ConfigKey<*>, oldValue: Any?, newValue: Any?)

    // ═══════════════════════════════════════════
    // 玩家会话
    // ═══════════════════════════════════════════

    /**
     * 获取或创建指定玩家的配置会话。
     *
     * 每个玩家有独立的配置值副本，互不干扰。
     */
    fun getPlayerSession(playerId: UUID): PlayerConfigSession

    /**
     * 移除玩家会话（玩家离线时调用）。
     */
    fun removePlayerSession(playerId: UUID)
}

/**
 * 分组构建监听器（函数接口）。
 */
fun interface ConfigGroupBuildListener {
    /**
     * 在分组构建时调用，允许动态添加/隐藏项。
     */
    fun onBuild(event: ConfigGroupBuildEvent)
}

/**
 * [buildGroupForPlayer] 的返回结果。
 *
 * @param group 原始分组
 * @param effectiveItems 排序后的全部有效项（已过滤不可见项）
 * @param effectiveSubGroups 有效子分组（已过滤不可见分组）
 * @param hiddenItemIds 被 visibleWhen 或事件隐藏的项 ID 集合
 * @param snapshot 构建时的值快照
 */
data class BuiltGroup(
    val group: ConfigGroup,
    val effectiveItems: List<ConfigItem>,
    val effectiveSubGroups: List<ConfigGroup>,
    val hiddenItemIds: Set<String>,
    val snapshot: ConfigSnapshot
) {
    /** 所有可见项的总数（含子组项） */
    val visibleCount: Int get() = effectiveItems.size

    /** 被隐藏的项总数 */
    val hiddenCount: Int get() = hiddenItemIds.size

    /** 被其他项 [ConfigKey.dependsOn] 引用的触发键 ID 集合。仅这些键变更时需重建 Dialog。 */
    val triggerKeyIds: Set<String> by lazy {
        val referenced = mutableSetOf<String>()
        effectiveItems.forEach { item ->
            referenced.addAll(item.key.dependsOn)
        }
        effectiveSubGroups.forEach { sub ->
            sub.items.forEach { item ->
                referenced.addAll(item.key.dependsOn)
            }
        }
        referenced
    }
}
