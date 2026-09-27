package work.art1st.javarailways.basic

import work.art1st.javarailways.basic.api.ato.AtoWorldCoordinator
import work.art1st.javarailways.basic.api.ato.provider.AtoProviderRegistry
import work.art1st.javarailways.basic.api.ato.provider.RailObjectStore
import work.art1st.javarailways.basic.pathFinding.RailMap
import work.art1st.javarailways.basic.route.TurnoutSafetyDefaults
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadataStore
import work.art1st.javarailways.basic.safety.TrainSpeedProfileStore
import work.art1st.javarailways.core.api.physics.rail.ViewDirection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Basic 模块挂在每个 World 上的运营层数据容器。
 *
 * 这里只保存通用路径点、ATO 协调器、Provider 和底层轨道运营对象。
 * Destination、Station、Depot、Line、Timetable 与 rolling-stock ledger 由 SimpleATO 持有。
 */
data class BasicWorldData(
    val railMap: RailMap = RailMap(),
    val wayPoints: WayPointStore = WayPointStore(),
    val atoCoordinator: AtoWorldCoordinator = AtoWorldCoordinator(),
    val atoProviders: AtoProviderRegistry = AtoProviderRegistry(),
    val railObjects: RailObjectStore = RailObjectStore(),
    val turnoutSafetyDefaults: TurnoutSafetyDefaults = TurnoutSafetyDefaults(),
    val railMetadata: RailInfrastructureMetadataStore = RailInfrastructureMetadataStore(),
    val trainSpeedProfiles: TrainSpeedProfileStore = TrainSpeedProfileStore()
)

/** 轨道上的路径点。第一版只保存可持久化引用，不直接持有 Core 内部 location 对象。 */
data class WayPoint(
    val id: String,
    val displayName: String,
    val railId: UUID,
    /** 弧长始终以 Rail 的 AB 曲线为基准；路径点本身不带行进方向。 */
    val arcLength: Double
)

class WayPointStore {
    private val wayPoints = ConcurrentHashMap<String, WayPoint>()

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

object DirectionRestrictionResolver {
    fun fromArcPoints(
        playerArcLength: Double,
        clickedArcLength: Double,
        minimumDifference: Double = 0.25
    ): ViewDirection? {
        val delta = clickedArcLength - playerArcLength
        if (kotlin.math.abs(delta) < minimumDifference) return null
        return if (delta > 0.0) ViewDirection.AB else ViewDirection.BA
    }
}
