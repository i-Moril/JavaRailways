package work.art1st.mineRails.basic

import work.art1st.mineRails.basic.pathFinding.RailMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Basic 模块挂在每个 World 上的运营层数据容器。
 *
 * 这里保存自动时刻表、停车点、轨道运营 metadata 等非 Core 物理数据。
 * 当前阶段只落地容器和占位 store，后续 phase 再逐步填充具体模型。
 */
data class BasicWorldData(
    val railMap: RailMap = RailMap(),
    val railMetadata: RailMetadataStore = RailMetadataStore(),
    val wayPoints: WayPointStore = WayPointStore(),
    val timetables: TimetableStore = TimetableStore(),
    val dispatcher: TimetableDispatcher = TimetableDispatcher()
) {
    /** 兼容早期 Phase2 命名；后续代码应使用 wayPoints。 */
    val stopPoints: WayPointStore
        get() = wayPoints
}

class RailMetadataStore

/** 轨道上的路径点。第一版只保存可持久化引用，不直接持有 Core 内部 location 对象。 */
data class WayPoint(
    val id: String,
    val displayName: String,
    val railId: UUID,
    /** 弧长始终以 Rail 的 AB 曲线为基准；路径点本身不带行进方向。 */
    val arcLength: Double
)

class WayPointStore {
    private val counter = AtomicLong(0L)
    private val wayPoints = ConcurrentHashMap<String, WayPoint>()

    fun nextId(): String = "W${counter.incrementAndGet()}"

    fun add(wayPoint: WayPoint) {
        require(wayPoint.id.isNotBlank()) { "wayPoint id must not be blank" }
        wayPoints[wayPoint.id] = wayPoint
    }

    fun rename(oldId: String, newId: String, displayName: String = newId): WayPoint {
        require(newId.isNotBlank()) { "wayPoint id must not be blank" }
        val current = wayPoints[oldId] ?: throw NoSuchElementException("wayPoint not found: $oldId")
        if (oldId != newId && wayPoints.containsKey(newId)) {
            throw IllegalArgumentException("wayPoint already exists: $newId")
        }
        val updated = current.copy(id = newId, displayName = displayName)
        if (oldId != newId) wayPoints.remove(oldId)
        wayPoints[newId] = updated
        return updated
    }

    fun remove(id: String): WayPoint? = wayPoints.remove(id)

    operator fun get(id: String): WayPoint? = wayPoints[id]

    fun all(): List<WayPoint> = wayPoints.values.sortedBy { it.id }
}

typealias StopPoint = WayPoint
typealias StopPointStore = WayPointStore

class TimetableStore

class TimetableDispatcher
