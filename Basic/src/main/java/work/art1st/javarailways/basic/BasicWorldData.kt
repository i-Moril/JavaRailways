package work.art1st.javarailways.basic

import work.art1st.javarailways.basic.api.rail.RailObjectStore
import work.art1st.javarailways.basic.api.route.WayPoint
import work.art1st.javarailways.basic.api.route.WayPointAccess
import work.art1st.javarailways.basic.api.safety.WorldSafetyInterlock
import work.art1st.javarailways.basic.rail.RailInfrastructureMetadataStore
import work.art1st.javarailways.basic.rail.OperatingDirectionStore
import work.art1st.javarailways.basic.safety.TrainSpeedProfileStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Basic 模块挂在每个 World 上的运营层数据容器。
 *
 * 这里只保存公共轨道运行方向、通用路径点、物理 fail-closed latch 和底层轨道运营对象。
 * ATO 协调器、策略以及其他运营状态由 SimpleATO 持有。
 */
data class BasicWorldData(
    val wayPoints: WayPointStore = WayPointStore(),
    val safetyInterlock: WorldSafetyInterlock = WorldSafetyInterlock(),
    val railObjects: RailObjectStore = RailObjectStore(),
    val railMetadata: RailInfrastructureMetadataStore = RailInfrastructureMetadataStore(),
    val trainSpeedProfiles: TrainSpeedProfileStore = TrainSpeedProfileStore(),
    val operatingDirections: OperatingDirectionStore = OperatingDirectionStore(),
    val trainDynamicsHistory: work.art1st.javarailways.basic.control.WorldTrainDynamicsHistory =
        work.art1st.javarailways.basic.control.WorldTrainDynamicsHistory(),
    @Volatile var commandSupervisor: work.art1st.javarailways.basic.api.ato.VehicleCommandSupervisor? = null
)

class WayPointStore : WayPointAccess {
    private val wayPoints = ConcurrentHashMap<String, WayPoint>()

    override fun add(wayPoint: WayPoint) {
        require(wayPoint.id.isNotBlank()) { "wayPoint id must not be blank" }
        wayPoints[wayPoint.id] = wayPoint
    }

    override fun rename(oldId: String, newId: String): WayPoint {
        require(newId.isNotBlank()) { "wayPoint id must not be blank" }
        val current = wayPoints[oldId] ?: throw NoSuchElementException("wayPoint not found: $oldId")
        if (oldId != newId && wayPoints.containsKey(newId)) {
            throw IllegalArgumentException("wayPoint already exists: $newId")
        }
        val updated = current.copy(id = newId)
        if (oldId != newId) wayPoints.remove(oldId)
        wayPoints[newId] = updated
        return updated
    }

    override fun remove(id: String): WayPoint? = wayPoints.remove(id)

    override operator fun get(id: String): WayPoint? = wayPoints[id]

    override fun all(): List<WayPoint> = wayPoints.values.sortedBy { it.id }
}
