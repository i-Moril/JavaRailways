package work.art1st.mineRails.core.api.util.tick

import java.util.concurrent.Callable
import java.util.concurrent.Future

interface TickManager {
    fun register(obj: TickingObject)
    fun unregister(obj: TickingObject)
    fun preTick(currentTimeMs: Long)
    fun postTick()
    fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T>
    fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T>
}