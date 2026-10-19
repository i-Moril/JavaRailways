package work.art1st.javarailways.bukkit.api.wand.config

/**
 * Wand 配置项：一个可编辑的值状态。
 *
 * 每个 WandConfigItem 持有一个 [ConfigKey]（元数据 + 默认值）和当前运行时值。
 * 支持 dirty tracking、commit、reset。
 *
 * 值写入由 [WandConfigItemImpl] 拥有；注册中心只接受该实现，其他实现会在注册/写入边界被明确拒绝。
 */
interface WandConfigItem {
    /** 关联的配置键（元数据） */
    val key: ConfigKey<*>

    /** 当前值；受支持的 WandConfigItemImpl 要求非 null 且符合 key 的实际类型与约束。 */
    val currentValue: Any?

    /** 是否已修改但未持久化 */
    val isDirty: Boolean
}

/**
 * [WandConfigItem] 的默认实现。
 *
 * @param T 值类型
 * @param key 配置键
 * @param initialValue 初始值，默认使用 [ConfigKey.defaultValue]
 */
class WandConfigItemImpl<T> @JvmOverloads constructor(
    override val key: ConfigKey<T>,
    initialValue: T? = key.defaultValue
) : WandConfigItem {

    init {
        key.validateValue(initialValue)
    }

    override var currentValue: Any? = initialValue
        private set

    override var isDirty: Boolean = false
        private set

    /**
     * 设置新值。自动标记 dirty。
     *
     * @throws IllegalArgumentException 如果值不满足 [ConfigKey.constraints]
     */
    fun setValue(value: T) {
        key.validateValue(value)
        currentValue = value
        isDirty = true
    }

    /**
     * 提交更改，清除 dirty 标记。
     */
    fun commit() {
        isDirty = false
    }

    /**
     * 重置为默认值，清除 dirty 标记。
     */
    fun reset() {
        currentValue = key.defaultValue
        isDirty = false
    }

    override fun toString(): String =
        "WandConfigItemImpl(key=${key.id}, value=$currentValue, dirty=$isDirty)"
}
