package work.art1st.mineRails.basic.pathFinding

import net.jcip.annotations.NotThreadSafe
import work.art1st.mineRails.core.physics.rail.RailView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 按世界隔离的铁路目的地索引。
 * 键为 [RailView]，值为挂载在该 RailView 上的所有目的地。
 */
@NotThreadSafe
class RailMap {
    private val destinations: MutableMap<RailView, CopyOnWriteArraySet<RailDestination>> = ConcurrentHashMap()

    /**
     * 查找是否存在任意一个标签为 [label] 的目的地。
     */
    fun hasDestination(label: String): Boolean {
        return destinations.any { (_, dests) -> dests.any { it.label == label } }
    }

    /**
     * 查找 [railView] 上是否存在标签为 [label] 的目的地。
     */
    fun hasDestination(railView: RailView, label: String): Boolean {
        return destinations[railView]?.any { it.label == label } == true
    }

    /**
     * 添加目的地，将其挂载到 [RailView] 上。
     */
    fun addDestination(railView: RailView, destination: RailDestination) {
        destinations.getOrPut(railView) { CopyOnWriteArraySet() }.add(destination)
    }

    /**
     * 从 [railView] 上移除标签为 [label] 的目的地。
     */
    fun removeDestination(railView: RailView, label: String) {
        destinations[railView]?.removeIf { it.label == label }
    }
}

/**
 * 在指定 [RailView] 上添加 [destination]。
 */
fun RailView.addDestination(destination: RailDestination) {
    this.rail.world.railMap.addDestination(this, destination)
}

/**
 * 从当前 [RailView] 上移除标签为 [label] 的目的地。
 */
fun RailView.removeDestination(label: String) {
    this.rail.world.railMap.removeDestination(this, label)
}

/**
 * 在指定世界上获取对应的 [RailMap]。
 * 测试时若无 BasicPlugin 初始化，需通过 [setTestRailMap] 注册。
 */
val work.art1st.mineRails.core.api.physics.World.railMap: RailMap
    get() = TestRailMapRegistry.getOrCreate(this)

/** 测试注册表，绕过 BasicPlugin 初始化 */
object TestRailMapRegistry {
    private val mapRegistry = java.util.WeakHashMap<work.art1st.mineRails.core.api.physics.World, RailMap>()

    fun getOrCreate(world: work.art1st.mineRails.core.api.physics.World): RailMap {
        return mapRegistry.computeIfAbsent(world) { RailMap() }
    }

    /**
     * 注册一个指定的 RailMap 到世界上（用于测试）。
     */
    fun register(world: work.art1st.mineRails.core.api.physics.World, map: RailMap) {
        mapRegistry[world] = map
    }
}