package work.art1st.mineRails.basic.api.ui.config

/**
 * 配置值约束。密封类，每种子类对应一种 [ConfigValueType] 的约束。
 *
 * 用于 Dialog 渲染时生成合适的输入控件（滑块范围、选项列表等），
 * 也用于值变更时的验证。
 */
sealed class ConfigConstraints<T> {

    /** Double 数值范围约束 */
    data class NumberRange(
        val min: Double,
        val max: Double,
        val step: Double = 1.0,
        /** 单位后缀，如 "m", "km/h", "mm"。仅用于显示，不影响逻辑 */
        val unit: String = ""
    ) : ConfigConstraints<Double>() {
        init {
            require(min <= max) { "min ($min) must be <= max ($max)" }
            require(step > 0) { "step must be positive, got $step" }
        }

        /** 验证值是否在范围内 */
        fun validate(value: Double): Boolean = value in min..max
    }

    /** 整数范围约束 */
    data class IntRange(
        val min: Int,
        val max: Int
    ) : ConfigConstraints<Int>() {
        init {
            require(min <= max) { "min ($min) must be <= max ($max)" }
        }

        /** 验证值是否在范围内 */
        fun validate(value: Int): Boolean = value in min..max
    }

    /** 字符串长度约束 */
    data class StringLength(
        val minLength: Int,
        val maxLength: Int
    ) : ConfigConstraints<String>() {
        init {
            require(minLength >= 0) { "minLength must be >= 0" }
            require(maxLength >= minLength) { "maxLength ($maxLength) must be >= minLength ($minLength)" }
        }

        /** 验证字符串长度是否在范围内 */
        fun validate(value: String): Boolean = value.length in minLength..maxLength
    }

    /** 枚举选项约束 */
    data class EnumOptions<T : Enum<T>>(
        val options: List<T>,
        /** 选项显示名映射。未映射的选项使用 name */
        val displayNames: Map<T, String> = emptyMap()
    ) : ConfigConstraints<T>() {
        init {
            require(options.isNotEmpty()) { "enum options must not be empty" }
        }

        /** 获取选项的显示名 */
        fun displayName(option: T): String = displayNames[option] ?: option.name
    }
}

/**
 * 验证值是否满足约束。不满足时抛出 [IllegalArgumentException]。
 */
fun <T> ConfigConstraints<T>.validateValue(value: T) {
    when (this) {
        is ConfigConstraints.NumberRange -> {
            val num = value as? Double
                ?: throw IllegalArgumentException("Expected Double, got ${value?.javaClass}")
            require(validate(num)) { "Value $num is out of range [$min, $max]" }
        }
        is ConfigConstraints.IntRange -> {
            val num = value as? Int
                ?: throw IllegalArgumentException("Expected Int, got ${value?.javaClass}")
            require(validate(num)) { "Value $num is out of range [$min, $max]" }
        }
        is ConfigConstraints.StringLength -> {
            val str = value as? String
                ?: throw IllegalArgumentException("Expected String, got ${value?.javaClass}")
            require(validate(str)) { "String length ${str.length} is out of range [$minLength, $maxLength]" }
        }
        is ConfigConstraints.EnumOptions<*> -> {
            require(value is Enum<*>) { "Expected Enum, got ${value?.javaClass}" }
            require(options.contains(value)) { "Value $value is not in options $options" }
        }
    }
}
