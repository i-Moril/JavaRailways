package work.art1st.mineRails.bukkit.util

import org.bukkit.Bukkit
import work.art1st.mineRails.core.util.ThreadGuardPlatformBridge

object BukkitThreadGuardBridge : ThreadGuardPlatformBridge {
    override fun isMainThread(): Boolean = Bukkit.isPrimaryThread()
}
