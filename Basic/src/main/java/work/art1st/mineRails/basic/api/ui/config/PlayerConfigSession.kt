package work.art1st.mineRails.basic.api.ui.config

import java.util.UUID

/**
 * 玩家配置会话。
 *
 * 每个玩家有独立的配置值副本。在 Dialog 中修改值时，先写入 session，
 * 玩家确认后通过 [commit] 将值同步到全局 [ConfigUI]。
 *
 * 支持多玩家同时打开同一分组 Dialog，互不干扰。
 */
interface PlayerConfigSession {
    /** 玩家 UUID */
    val playerId: UUID

    /**
     * 获取玩家会话中的配置值。未设置时返回 null。
     */
    fun <T> getValue(key: ConfigKey<T>): T?

    /**
     * 设置玩家会话中的配置值。
     */
    fun <T> setValue(key: ConfigKey<T>, value: T)

    /**
     * 批量提交所有 dirty 值到全局配置。
     * 触发 [ConfigUI.onValueChanged] 回调。
     */
    fun commit()

    /**
     * 重置所有值为全局默认值，清除 dirty 标记，不触发回调。
     */
    fun reset()

    /**
     * 是否有未提交的修改。
     */
    fun hasPendingChanges(): Boolean

    /**
     * 获取所有已修改的 key ID。
     */
    fun getDirtyKeyIds(): Set<String>
}
