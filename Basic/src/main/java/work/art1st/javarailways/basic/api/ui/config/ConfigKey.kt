package work.art1st.javarailways.basic.api.ui.config

/**
 * 类型安全的配置键。
 *
 * 携带足够元数据供 Dialog 渲染：类型、约束、依赖关系、可见性条件等。
 *
 * @param T 配置值类型
 * @param id 唯一标识符，如 "curve.radius"
 * @param label 显示名称，如 "圆曲线半径"
 * @param description 描述/帮助文本
 * @param defaultValue 默认值
 * @param valueType 值类型，决定 DialogInput 控件类型
 * @param constraints 取值范围约束，用于渲染滑块/选项和验证
 * @param group 所属分组 ID，null 表示不属于任何分组
 * @param displayHint 显示提示，如 placeholder text
 * @param order 排序权重，越小越靠前。默认 0
 * @param dependsOn 依赖的其他 ConfigKey ID 列表。当这些 key 的值变更时，Dialog 会重新评估可见性
 * @param visibleWhen 条件可见性：给定当前配置快照，返回此项是否应显示。null 表示始终可见
 */
class ConfigKey<T> @JvmOverloads constructor(
    val id: String,
    val label: String,
    val description: String = "",
    val defaultValue: T,
    val valueType: ConfigValueType,
    val constraints: ConfigConstraints<T>? = null,
    val group: String? = null,
    val displayHint: String? = null,
    val order: Int = 0,
    val dependsOn: List<String> = emptyList(),
    val visibleWhen: ((ConfigSnapshot) -> Boolean)? = null
) {
    init {
        require(id.isNotBlank()) { "ConfigKey id must not be blank" }
        require(label.isNotBlank()) { "ConfigKey label must not be blank" }
        // 验证默认值满足约束
        constraints?.validateValue(defaultValue)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfigKey<*>) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "ConfigKey(id='$id', label='$label', type=$valueType)"

    companion object {
        /**
         * DSL builder: ConfigKey.build<T>("id") { ... }
         *
         * 用法:
         * ```
         * val key = ConfigKey.build<Double>("curve.radius") {
         *     label = "曲线半径"
         *     defaultValue = 300.0
         *     valueType = ConfigValueType.NUMBER
         *     constraints = ConfigConstraints.NumberRange(10.0, 10000.0, 1.0, "m")
         *     group = "curve.params"
         * }
         * ```
         */
        inline fun <reified T> build(id: String, block: ConfigKeyBuilder<T>.() -> Unit): ConfigKey<T> {
            return ConfigKeyBuilder<T>(id).apply(block).build()
        }
    }
}

/**
 * ConfigKey DSL 构建器。
 */
@DslMarker
annotation class ConfigKeyDsl

@ConfigKeyDsl
class ConfigKeyBuilder<T> @PublishedApi internal constructor(
    private val id: String
) {
    /** 显示名称。默认使用 id */
    var label: String = id

    /** 描述/帮助文本 */
    var description: String = ""

    // Backing fields for required properties to avoid lateinit + JVM signature clash
    private var _defaultValue: T? = null
    private var hasDefaultValue: Boolean = false

    /** 值类型（必须设置） */
    var valueType: ConfigValueType? = null

    /** 取值范围约束 */
    @Suppress("UNCHECKED_CAST")
    var constraints: ConfigConstraints<T>? = null

    /** 所属分组 ID */
    var group: String? = null

    /** 显示提示 */
    var displayHint: String? = null

    /** 排序权重 */
    var order: Int = 0

    /** 依赖的 key ID 列表 */
    var dependsOn: List<String> = emptyList()

    /** 条件可见性 */
    var visibleWhen: ((ConfigSnapshot) -> Boolean)? = null

    /** 设置默认值并标记已设置 */
    fun defaultValue(value: T): ConfigKeyBuilder<T> { this._defaultValue = value; hasDefaultValue = true; return this }

    @PublishedApi
    internal fun build(): ConfigKey<T> {
        val resolvedValueType = valueType
            ?: throw IllegalStateException("valueType must be set for ConfigKey '$id'")
        check(hasDefaultValue) { "defaultValue must be set for ConfigKey '$id'" }
        @Suppress("UNCHECKED_CAST")
        val resolvedDefault = _defaultValue as T
        return ConfigKey(
            id = id,
            label = label,
            description = description,
            defaultValue = resolvedDefault,
            valueType = resolvedValueType,
            constraints = constraints,
            group = group,
            displayHint = displayHint,
            order = order,
            dependsOn = dependsOn,
            visibleWhen = visibleWhen
        )
    }
}
