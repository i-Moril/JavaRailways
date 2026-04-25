package work.art1st.mineRails.basic.pathFinding

import net.jcip.annotations.NotThreadSafe
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import work.art1st.mineRails.core.physics.rail.RailView
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 按世界隔离的铁路目的地索引。
 * 键为 [RailView]，值为挂载在该 RailView 上的所有目的地。
 */
@NotThreadSafe
class RailMap {
    private val destinations: MutableMap<RailView, CopyOnWriteArraySet<RailDestination>> = HashMap()

    /**
     * 查找是否存在任意一个标签为 [label] 的目的地。
     */
    fun hasDestination(label: String): Boolean {
        return destinations.any { (_, dests) -> dests.any { it.label == label } }
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
 * 访问指定世界的 [RailMap]。
 */
@OptIn(ExtensionData::class)
val World.railMap: RailMap
    get() = getExtensionData(BasicPlugin.INSTANCE)
