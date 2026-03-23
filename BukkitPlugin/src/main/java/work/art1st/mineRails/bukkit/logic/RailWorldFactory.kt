package work.art1st.mineRails.bukkit.logic

import org.bukkit.World as BukkitWorld
import work.art1st.mineRails.bukkit.BukkitPlugin
import work.art1st.mineRails.bukkit.extension.BukkitWorldData
import work.art1st.mineRails.bukkit.render.chunk.RenderChunkManager
import work.art1st.mineRails.core.api.physics.World
import work.art1st.mineRails.core.di.mineRailsParentScope
import work.art1st.mineRails.core.physics.CoreWorld
import work.art1st.mineRails.core.util.TickManagerImpl
import java.util.concurrent.Executors

/**
 * 为 Bukkit 世界创建对应的 Core [World]：Tick、Koin 父 scope、[BukkitWorldData] 扩展一步完成。
 */
object RailWorldFactory {
    fun createForBukkit(bukkitWorld: BukkitWorld): World {
        val tickManager = TickManagerImpl(
            Executors.newSingleThreadScheduledExecutor(),
            System.currentTimeMillis()
        )
        val coreWorld = CoreWorld.createEmpty(tickManager, mineRailsParentScope())
        val renderChunkManager = RenderChunkManager(bukkitWorld)
        coreWorld.registerExtensionData(
            BukkitPlugin,
            BukkitWorldData(renderChunkManager, bukkitWorld)
        )
        return coreWorld
    }
}
