package work.art1st.javarailways.basic.testing

import work.art1st.javarailways.basic.BasicPlugin
import work.art1st.javarailways.basic.BasicWorldData
import work.art1st.javarailways.core.api.physics.World
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 测试注册表：为测试显式安装 [BasicWorldData]，避免生产代码隐式回退到测试数据。
 */
object TestBasicWorldDataRegistry {
    private val registry = WeakHashMap<World, WeakReference<BasicWorldData>>()

    @Synchronized
    fun getOrCreate(world: World): BasicWorldData {
        registry[world]?.get()?.let { return it }
        return BasicWorldData().also { register(world, it) }
    }

    @Synchronized
    fun register(world: World, data: BasicWorldData) {
        val plugin = BasicPlugin.instanceOrNull() ?: BasicPlugin(0).also { BasicPlugin.INSTANCE = it }
        world.registerExtensionData(plugin, data)
        registry[world] = WeakReference(data)
    }

    @Synchronized
    fun unregister(world: World) {
        registry.remove(world)
    }
}
