package work.art1st.mineRails.bukkit.util

import org.bukkit.Location

/** Optional region affinity for a generic TickManager commit request. */
fun interface PlatformCommitAffinity {
    fun commitLocation(): Location?
}
