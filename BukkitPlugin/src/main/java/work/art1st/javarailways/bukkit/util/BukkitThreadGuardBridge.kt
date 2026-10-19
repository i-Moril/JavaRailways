package work.art1st.javarailways.bukkit.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import work.art1st.javarailways.bukkit.BukkitPlugin
import work.art1st.javarailways.core.api.physics.World
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.physics.CoreWorld
import work.art1st.javarailways.core.physics.WorldExecutionOwner

/** Classifies host domain callbacks; World execution ownership is independent of platform ownership. */
object BukkitThreadGuardBridge : ThreadGuardPlatformBridge {
    private val simulationDepth = ThreadLocal.withInitial { 0 }

    override fun isMainThread(): Boolean = simulationDepth.get() > 0 || Bukkit.isPrimaryThread()

    fun <T> runInSimulationContext(action: () -> T): T {
        simulationDepth.set(simulationDepth.get() + 1)
        try {
            return action()
        } finally {
            val remaining = simulationDepth.get() - 1
            if (remaining == 0) simulationDepth.remove() else simulationDepth.set(remaining)
        }
    }

    fun <T> runInSimulationContext(world: World, action: () -> T): T = runInSimulationContext {
        (world as CoreWorld).executionOwner.run(action)
    }

    fun <T> runInPlayerWorldContext(player: Player, action: () -> T): T {
        val world = BukkitPlugin.INSTANCE.worldManager.railWorlds[player.world]
        return if (world == null) runInSimulationContext(action) else runInSimulationContext(world, action)
    }

    fun <T> runWithWorlds(worlds: Collection<CoreWorld>, action: () -> T): T = runInSimulationContext {
        WorldExecutionOwner.runTogether(worlds.map { it.executionOwner }, action)
    }

    /** Release actual domain owners while waiting for a target-aware platform task. */
    fun <T> runOutsideSimulationContext(action: () -> T): T {
        val depth = simulationDepth.get()
        simulationDepth.remove()
        try {
            return WorldExecutionOwner.runWithoutOwners(action)
        } finally {
            if (depth > 0) simulationDepth.set(depth)
        }
    }
}
