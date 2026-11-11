package work.art1st.javarailways.bukkit.util

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import work.art1st.javarailways.core.physics.WorldExecutionOwner

/** Serializes LIVE directory/lifecycle transactions, never ordinary World work or simulation. */
internal object LiveWorldManagement {
    private val lock = ReentrantLock()

    fun <T> run(action: () -> T): T {
        if (!lock.isHeldByCurrentThread) WorldExecutionOwner.ensureNoOwner("LIVE world management")
        return lock.withLock(action)
    }
}
