package work.art1st.javarailways.bukkit.api.wand.config

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
 *
 * 注册与结构变更由调用方串行安排，不得与构建并发。同一玩家会话由其 owner 串行使用。
 * 快照只承诺逐键捕获后稳定，不保证跨键原子提交或整个注册中心的线程安全。
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
    fun register(item: WandConfigItem)

    /**
     * 注册一组配置项。
     */
    fun register(items: Sequence<WandConfigItem>)

    /**
     * 向已有分组贡献一个配置项（扩展点注册）。
     *
     * 目标分组存在时必须允许扩展；已知拒绝会抛出包含 group/key 的 [IllegalArgumentException]，
     * 贡献不会进入正式索引。目标分组尚不存在时贡献被暂存，等分组注册后再统一结算；
     * 若最终分组不允许扩展，暂存贡献会被丢弃并记录警告。
     *
     * @throws IllegalArgumentException 目标分组存在但不允许扩展，或贡献项不是受支持的实现
     */
    fun registerToGroup(groupId: String, item: WandConfigItem)

    /**
     * 向已有分组贡献一个子分组（扩展点注册）。
     *
     * 与 [registerToGroup] 的项重载具有相同的接受、拒绝与暂存规则。
     *
     * @throws IllegalArgumentException 目标分组存在但不允许扩展
     */
    fun registerToGroup(groupId: String, subGroup: ConfigGroup)

    /** 仍在等待目标分组注册的扩展贡献分组 ID；用于诊断悬而未决的贡献。 */
    fun pendingExtensionGroupIds(): Set<String>

    // ═══════════════════════════════════════════
    // 值访问
    // ═══════════════════════════════════════════

    /**
     * 按 [ConfigKey] 获取全局配置值。未设置时返回 null；同 ID 的实际值类型冲突时报错。
     */
    fun <T> get(key: ConfigKey<T>): T?

    /**
     * 按 [ConfigKey] 获取全局配置值。未设置时返回 [ConfigKey.defaultValue]。
     */
    fun <T> getOrDefault(key: ConfigKey<T>): T

    /**
     * 以编程方式设置全局配置值。触发 [onValueChanged] 回调。
     * 未注册的键会被注册；同 ID 的实际值类型冲突时报错。
     * [ConfigNotificationException] 表示值已写入但通知失败，不得把它当作写入拒绝。
     */
    fun <T> setValue(key: ConfigKey<T>, value: T)

    // ═══════════════════════════════════════════
    // 查找
    // ═══════════════════════════════════════════

    /**
     * 按 key ID 查找配置项。返回 null 如果不存在。
     */
    fun getItem(keyId: String): WandConfigItem?

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
    fun getEffectiveItems(groupId: String): List<WandConfigItem>

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
 * @param effectiveItems 递归过滤分组/项及事件贡献后按 key 唯一、稳定排序的全部有效项；这是渲染和输入处理的唯一项来源
 * @param effectiveSubGroups 过滤后的子分组展示树，仅用于标题/导航，不应将其 items 再追加到 effectiveItems
 * @param hiddenItemIds 被 visibleWhen 或事件隐藏的项 ID 集合
 * @param snapshot 事件收集完成后逐键捕获的构建值和成员关系；不随后续 registry/session 更改
 */
data class BuiltGroup(
    val group: ConfigGroup,
    val effectiveItems: List<WandConfigItem>,
    val effectiveSubGroups: List<ConfigGroup>,
    val hiddenItemIds: Set<String>,
    val snapshot: ConfigSnapshot,
    /**
     * 被其他项 [ConfigKey.dependsOn] 引用的触发键 ID 集合，收集自本次构建的完整候选树
     * （包括当前被隐藏、可能因输入变化而重新显示的项）。仅这些键变更时需重建 Dialog。
     */
    val triggerKeyIds: Set<String> = effectiveItems
        .asSequence()
        .flatMap { it.key.dependsOn.asSequence() }
        .toSet()
) {
    /** 所有可见项的总数（含子组项） */
    val visibleCount: Int get() = effectiveItems.size

    /** 被隐藏的项总数 */
    val hiddenCount: Int get() = hiddenItemIds.size
}
