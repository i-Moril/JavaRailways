package work.art1st.mineRails.core.util

import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickManager
import work.art1st.mineRails.core.api.util.tick.TickingObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService

class TickManagerImpl(
    private val scheduler: ScheduledExecutorService,
    private var currentTick: Long
) : TickManager {
    private val tickingObjects: MutableSet<TickingObject> = HashSet()
    private val tickingSynchronizableObjects: MutableSet<Synchronizable> = HashSet()

    /**
     * 或许可以使用HashMap<Thread, List<Runnable>>?
     */
    private val afterCommitTasks: ConcurrentLinkedQueue<Runnable> = ConcurrentLinkedQueue()
    private val postTickTasks: ConcurrentLinkedQueue<Runnable> = ConcurrentLinkedQueue()
    private var waitForPreTick: Runnable = Runnable {}

    /**
     * register和unregister都在tick结束时进行。
     */
    override fun register(obj: TickingObject) {
        scheduleMainThreadPostTickTask {
            if (obj is Synchronizable) {
                tickingSynchronizableObjects.add(obj)
            }
            tickingObjects.add(obj)
        }
    }

    override fun unregister(obj: TickingObject) {
        scheduleMainThreadPostTickTask {
            if (obj is Synchronizable) {
                tickingSynchronizableObjects.remove(obj)
            }
            tickingObjects.remove(obj)
        }
    }

    override fun preTick(currentTimeMs: Long) {
        val tickLatch = CountDownLatch(tickingObjects.size)
        val barrier = CyclicBarrier(tickingSynchronizableObjects.size + 1)
        barrier.await() // 防止IllegalArgumentException
        val deltaT = (currentTimeMs - currentTick).toDouble()
        currentTick = currentTimeMs
        tickingObjects.forEach {
            scheduler.submit {
                it.tick(deltaT)
                if (it is Synchronizable) {
                    barrier.await()
                    it.commit()
                }
                tickLatch.countDown()
            }
        }
        waitForPreTick = Runnable { tickLatch.await() }
    }

    override fun postTick() {
        waitForPreTick.run()
        afterCommitTasks.forEach { it.run() }
        afterCommitTasks.clear()
        postTickTasks.forEach { it.run() }
        postTickTasks.clear()
    }

    override fun <T> scheduleMainThreadAfterCommitTask(task: Callable<T>): Future<T> {
        val futureTask = FutureTask(task)
        afterCommitTasks.add {
            futureTask.run()
        }
        return futureTask
    }

    override fun <T> scheduleMainThreadPostTickTask(task: Callable<T>): Future<T> {
        val futureTask = FutureTask(task)
        postTickTasks.add {
            futureTask.run()
        }
        return futureTask
    }
}
