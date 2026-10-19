package work.art1st.javarailways.bukkit.api.wand.config

import java.util.UUID

/** Key and reason for a failed write or post-write notification; the containing list identifies the phase. */
data class ConfigCommitFailure(
    val keyId: String,
    val reason: String
)

/** Outcome of one session commit; successfully committed keys no longer stay dirty. */
data class ConfigCommitResult(
    val committedKeyIds: Set<String>,
    /** Rejected writes; local values and dirty flags are retained. */
    val failures: List<ConfigCommitFailure>,
    /** Values were written and dirty flags cleared, but change notification failed. */
    val notificationFailures: List<ConfigCommitFailure> = emptyList()
) {
    val succeeded: Boolean get() = failures.isEmpty() && notificationFailures.isEmpty()
}


/** The value was committed, but a subsequent change notification failed. Retrying is not a rollback. */
class ConfigNotificationException(val keyId: String, cause: Exception) :
    RuntimeException("Configuration '$keyId' was saved but change notification failed: ${cause.message}", cause)

/**
 * 玩家配置会话。
 *
 * 每个玩家有独立的配置值副本。在 Dialog 中修改值时，先写入 session，
 * 玩家确认后通过 [commit] 将值同步到全局 [ConfigUI]。
 *
 * 各玩家未提交的本地值相互隔离；commit 写共享全局值，不保证跨玩家事务隔离。
 */
interface PlayerConfigSession {
    /** 玩家 UUID */
    val playerId: UUID

    /**
     * 获取玩家会话中的配置值；没有本地值时回退注册中心当前值，未注册时返回 null。
     */
    fun <T> getValue(key: ConfigKey<T>): T?

    /**
     * 设置玩家会话中的配置值。
     */
    fun <T> setValue(key: ConfigKey<T>, value: T)

    /**
     * 批量提交所有 dirty 值到全局配置。
     * 触发 [ConfigUI.onValueChanged] 回调。
     *
     * 每个键独立提交：未注册或写入失败的键保留本地值与 dirty 状态，并在结果中报告；
     * 通知失败的键已提交并清除 dirty，在 notificationFailures 中单独报告。
     * 其余键正常提交。调用方必须检查 [ConfigCommitResult.succeeded] 并反馈失败。
     */
    fun commit(): ConfigCommitResult

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
