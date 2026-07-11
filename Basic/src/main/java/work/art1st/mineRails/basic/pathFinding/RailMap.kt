package work.art1st.mineRails.basic.pathFinding

import net.jcip.annotations.NotThreadSafe
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.basic.BasicWorldData
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.api.physics.rail.RailView
import work.art1st.mineRails.core.api.util.plugin.ExtensionData
import java.util.WeakHashMap
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
 * 在指定世界上获取 Basic 模块运营数据。
 *
 * 生产路径要求 [BasicWorldData] 已显式注册；若缺失则直接报错，避免将初始化问题伪装成空数据。
 */
@OptIn(ExtensionData::class)
val World.basicWorldDataOrNull: BasicWorldData?
    get() {
        val plugin = BasicPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

@OptIn(ExtensionData::class)
val World.basicWorldData: BasicWorldData
    get() {
        val plugin = BasicPlugin.instanceOrNull()
            ?: throw IllegalStateException(
                "BasicPlugin.INSTANCE 尚未初始化；请先显式注册 BasicWorldData 后再访问 world.basicWorldData"
            )
        return getExtensionDataOrNull(plugin)
            ?: throw IllegalStateException(
                "BasicWorldData 未注册到当前 world；请在运行时创建世界时显式注册，测试中请使用 TestBasicWorldDataRegistry.register"
            )
    }

/** 在指定世界上获取对应的 [RailMap]。 */
val World.railMap: RailMap
    get() = basicWorldData.railMap

/**
 * 测试注册表：为测试显式安装 [BasicWorldData]，避免生产代码隐式回退到测试数据。
 */
object TestBasicWorldDataRegistry {
    private val registry = WeakHashMap<World, BasicWorldData>()

    fun getOrCreate(world: World): BasicWorldData {
        registry[world]?.let { return it }
        return BasicWorldData().also { register(world, it) }
    }

    fun register(world: World, data: BasicWorldData) {
        val plugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        world.registerExtensionData(plugin, data)
        registry[world] = data
    }
}

/** 兼容旧测试：只注册 RailMap 时自动包装成 BasicWorldData。 */
object TestRailMapRegistry {
    fun getOrCreate(world: World): RailMap = TestBasicWorldDataRegistry.getOrCreate(world).railMap

    fun register(world: World, map: RailMap) {
        TestBasicWorldDataRegistry.register(world, BasicWorldData(railMap = map))
    }
}