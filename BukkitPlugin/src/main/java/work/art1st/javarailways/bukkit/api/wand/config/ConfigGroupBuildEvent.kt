package work.art1st.javarailways.bukkit.api.wand.config

import java.util.UUID

/**
 * 分组构建事件。
 *
 * 在 [ConfigUI.buildGroupForPlayer] 过程中触发，
 * 允许监听器动态地组合或隐藏已注册配置项/分组；事件不是新的配置值存储。
 * 所有新增树中的项必须事先在全局注册，否则构建立即报错（即使该项被隐藏）。
 *
 * 与 [ConfigKey.visibleWhen] 互补：
 * - visibleWhen 是**数据驱动**的（值 → 可见性）
 * - BuildEvent 是**逻辑驱动**的（权限、上下文、外部状态 → 可见性）
 *
 * @param groupId 正在构建的分组 ID
 * @param playerId 目标玩家 UUID
 * @param context 上下文（如 "editing" → true/false）
 */
class ConfigGroupBuildEvent(
    val groupId: String,
    val playerId: UUID,
    val context: Map<String, Any>
) {
    private val _extraItems = mutableListOf<WandConfigItem>()
    private val _extraGroups = mutableListOf<ConfigGroup>()
    private val _hiddenItemIds = mutableSetOf<String>()
    private val _hiddenGroupIds = mutableSetOf<String>()

    /** 动态添加的额外项（在展平结果后追加） */
    val extraItems: List<WandConfigItem> get() = _extraItems.toList()

    /** 动态添加的额外分组（在展平结果后追加） */
    val extraGroups: List<ConfigGroup> get() = _extraGroups.toList()

    /** 被动态隐藏的项 ID */
    val hiddenItemIds: Set<String> get() = _hiddenItemIds.toSet()

    /** 被动态隐藏的分组 ID */
    val hiddenGroupIds: Set<String> get() = _hiddenGroupIds.toSet()

    /**
     * 动态添加一个配置项到当前构建结果中。
     */
    fun addItem(item: WandConfigItem) {
        _extraItems.add(item)
    }

    /**
     * 动态添加一个子分组到当前构建结果中。
     */
    fun addGroup(group: ConfigGroup) {
        _extraGroups.add(group)
    }

    /**
     * 动态隐藏指定 ID 的配置项。
     */
    fun hideItem(itemId: String) {
        _hiddenItemIds.add(itemId)
    }

    /**
     * 动态隐藏指定 ID 的子分组。
     */
    fun hideGroup(groupId: String) {
        _hiddenGroupIds.add(groupId)
    }

    /**
     * 检查某个项 ID 是否被显式隐藏。
     */
    fun isItemHidden(itemId: String): Boolean = itemId in _hiddenItemIds

    /**
     * 检查某个分组 ID 是否被显式隐藏。
     */
    fun isGroupHidden(groupId: String): Boolean = groupId in _hiddenGroupIds

    /** 内部：收集所有额外项 */
    internal fun collectExtraItems(): List<WandConfigItem> = _extraItems.toList()

    /** 内部：收集所有额外分组 */
    internal fun collectExtraGroups(): List<ConfigGroup> = _extraGroups.toList()

    /** 内部：收集所有隐藏项 ID */
    internal fun collectHiddenItemIds(): Set<String> = _hiddenItemIds.toSet()

    /** 内部：收集所有隐藏分组 ID */
    internal fun collectHiddenGroupIds(): Set<String> = _hiddenGroupIds.toSet()
}
