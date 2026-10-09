package work.art1st.javarailways.basic

import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.util.plugin.ExtensionData

/** 获取显式注册的世界局部 Basic 数据；插件或数据缺失时返回 null。 */
@OptIn(ExtensionData::class)
val World.basicWorldDataOrNull: BasicWorldData?
    get() {
        val plugin = BasicPlugin.instanceOrNull() ?: return null
        return getExtensionDataOrNull(plugin)
    }

/** 要求 Basic 数据已经显式注册；缺失时抛错，不隐式创建或回退到测试数据。 */
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
