package work.art1st.mineRails.bukkit.logic

import org.bukkit.World as BukkitWorld
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitWorldData
import work.art1st.mineRails.bukkit.render.chunk.RenderChunkManager
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.di.KoinWorldParentContext
import work.art1st.mineRails.core.di.mineRailsParentScope
import work.art1st.mineRails.basic.BasicPlugin
import work.art1st.mineRails.basic.pathFinding.RailMap
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.util.TickManagerImpl
import java.util.concurrent.Executors

/**
 * 为 Bukkit 世界创建对应的 Core [World]：Tick、Koin 父 scope、[BukkitWorldData] 扩展一步完成。
 */
object RailWorldFactory {
    fun createForBukkit(
        bukkitWorld: BukkitWorld,
        snapshot: CoreWorld.SavedWorldV1? = null
    ): World {
        val tickManager = TickManagerImpl(
            Executors.newSingleThreadScheduledExecutor(),
            System.currentTimeMillis()
        )
        val worldParent = KoinWorldParentContext(mineRailsParentScope())
        val coreWorld = if (snapshot != null) {
            snapshot.load(CoreWorld.SavedWorldV1.Dependency(tickManager, worldParent))
        } else {
            CoreWorld.createEmpty(tickManager, worldParent)
        }
        val renderChunkManager = RenderChunkManager(bukkitWorld)
        coreWorld.registerExtensionData(
            BukkitPlugin,
            BukkitWorldData(renderChunkManager, bukkitWorld)
        )
        coreWorld.registerExtensionData(
            BasicPlugin.INSTANCE,
            RailMap()
        )
        return coreWorld
    }
}
