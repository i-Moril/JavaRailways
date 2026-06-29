package work.art1st.mineRails.basic.api.ui.config

/**
 * 配置值类型枚举。决定 Dialog 使用什么 DialogInput 控件。
 */
enum class ConfigValueType {
    /** Double 数值，映射到 [DialogInput.numberRange]。可带单位（如 "m", "km/h", "mm"）。 */
    NUMBER,

    /** 字符串，映射到 [DialogInput.text]。 */
    STRING,

    /** 布尔值，映射到 [DialogInput.bool]。 */
    BOOLEAN,

    /** 枚举选择，映射到 [DialogInput.singleOption]。 */
    ENUM,

    /** 三维向量（JOML Vector3d），分解为 3 个 numberRange（X / Y / Z）。 */
    VECTOR3D,

    /** 整数，映射到 [DialogInput.numberRange] + step=1。 */
    INTEGER
}
