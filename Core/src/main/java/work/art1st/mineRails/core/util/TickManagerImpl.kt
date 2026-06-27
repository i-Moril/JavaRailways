package work.art1st.mineRails.core.util

import work.art1st.mineRails.core.api.util.tick.Synchronizable
import work.art1st.mineRails.core.api.util.tick.TickManager
import work.art1st.mineRails.core.api.util.tick.TickingObject
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
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
        val deltaT = (currentTimeMs - currentTick).toDouble()
        currentTick = currentTimeMs
        val objects = tickingObjects.toList()
        val synchronizableObjects = tickingSynchronizableObjects.toList()
        val tickLatch = CountDownLatch(objects.size)
        val tickErrors = ConcurrentLinkedQueue<Throwable>()
        val successfulSynchronizableObjects =
            ConcurrentHashMap.newKeySet<Synchronizable>()

        if (objects.isEmpty()) {
            waitForPreTick = Runnable {}
            return
        }

        objects.forEach { obj ->
            try {
                scheduler.submit {
                    try {
                        obj.tick(deltaT)
                        if (obj is Synchronizable) {
                            successfulSynchronizableObjects.add(obj)
                        }
                    } catch (t: Throwable) {
                        tickErrors.add(t)
                    } finally {
                        tickLatch.countDown()
                    }
                }
            } catch (t: Throwable) {
                tickErrors.add(t)
                tickLatch.countDown()
            }
        }

        waitForPreTick = Runnable {
            tickLatch.await()
            tickErrors.forEach { it.printStackTrace() }
            synchronizableObjects.forEach {
                if (successfulSynchronizableObjects.contains(it)) {
                    try {
                        it.commit()
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
            }
        }
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
