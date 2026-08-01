package work.art1st.javarailways.basic.api.ui.config

/**
 * 配置分组：将相关的 [ConfigItem] 和子分组组织在一起。
 *
 * 支持四种组合机制：
 * 1. **层级组合** — subGroups 嵌套子分组
 * 2. **扩展点注册** — 通过 [ConfigUI.registerToGroup] 外部贡献
 * 3. **条件可见** — visibleWhen 根据配置快照决定是否显示
 * 4. **预渲染事件** — [ConfigGroupBuildEvent] 动态添加/隐藏
 *
 * @param id 分组唯一标识，如 "rail.draw"
 * @param label 显示名称
 * @param description 描述
 * @param items 直接属于此分组的配置项
 * @param subGroups 嵌套子分组（层级组合）
 * @param allowExtensions 是否允许外部通过 [ConfigUI.registerToGroup] 贡献项
 * @param isExpanded 默认是否展开
 * @param visibleWhen 分组级条件可见性：给定当前配置快照，返回此分组是否应显示。null 表示始终可见
 */
class ConfigGroup @JvmOverloads constructor(
    val id: String,
    val label: String,
    val description: String = "",
    val items: MutableList<ConfigItem> = mutableListOf(),
    val subGroups: MutableList<ConfigGroup> = mutableListOf(),
    val allowExtensions: Boolean = true,
    var isExpanded: Boolean = true,
    val visibleWhen: ((ConfigSnapshot) -> Boolean)? = null
) {
    init {
        require(id.isNotBlank()) { "ConfigGroup id must not be blank" }
        require(label.isNotBlank()) { "ConfigGroup label must not be blank" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfigGroup) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "ConfigGroup(id='$id', label='$label', items=${items.size}, subGroups=${subGroups.size})"
}
