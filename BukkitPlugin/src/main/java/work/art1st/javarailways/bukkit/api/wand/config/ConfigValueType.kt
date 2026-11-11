package work.art1st.javarailways.bukkit.api.wand.config

/**
 * 配置值类型枚举。决定 Dialog 使用什么 DialogInput 控件。
 */
enum class ConfigValueType {
    /** Double 数值，映射到 [DialogInput.numberRange]。可带单位（如 "m", "km/h", "mm"）。 */
    NUMBER,

    /** Double 数值，以可精确输入的 [DialogInput.text] 呈现。 */
    NUMBER_TEXT,

    /** 字符串，映射到 [DialogInput.text]。 */
    STRING,

    /** 布尔值，映射到 [DialogInput.bool]。 */
    BOOLEAN,

    /** 枚举选择，映射到 [DialogInput.singleOption]。 */
    ENUM,

    /** 三维向量（JOML Vector3d），分解为 3 个 numberRange（X / Y / Z）。 */
    VECTOR3D,

    /** 整数，映射到 [DialogInput.numberRange] + step=1。 */
    INTEGER;

    /**
     * Whether [value] is a runtime value of this declared type.
     *
     * This validates the UI control choice; ConfigKey.valueClass identifies the actual value type.
     */
    fun accepts(value: Any?): Boolean = when (this) {
        NUMBER, NUMBER_TEXT -> value is Double
        STRING -> value is String
        BOOLEAN -> value is Boolean
        ENUM -> value is Enum<*>
        VECTOR3D -> value is org.joml.Vector3d
        INTEGER -> value is Int
    }
}
