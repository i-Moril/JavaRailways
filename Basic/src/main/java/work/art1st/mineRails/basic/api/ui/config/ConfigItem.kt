package work.art1st.mineRails.basic.api.ui.config

/**
 * 配置项：一个可编辑的值状态。
 *
 * 每个 ConfigItem 持有一个 [ConfigKey]（元数据 + 默认值）和当前运行时值。
 * 支持 dirty tracking、commit、reset。
 */
interface ConfigItem {
    /** 关联的配置键（元数据） */
    val key: ConfigKey<*>

    /** 当前值。可能为 null（未设置） */
    val currentValue: Any?

    /** 是否已修改但未持久化 */
    val isDirty: Boolean
}

/**
 * [ConfigItem] 的默认实现。
 *
 * @param T 值类型
 * @param key 配置键
 * @param initialValue 初始值，默认使用 [ConfigKey.defaultValue]
 */
class ConfigItemImpl<T> @JvmOverloads constructor(
    override val key: ConfigKey<T>,
    initialValue: T? = key.defaultValue
) : ConfigItem {

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
        key.constraints?.validateValue(value)
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
        "ConfigItemImpl(key=${key.id}, value=$currentValue, dirty=$isDirty)"
}
