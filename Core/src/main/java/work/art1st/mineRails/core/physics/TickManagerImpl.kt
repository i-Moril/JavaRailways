package work.art1st.mineRails.core.physics

import work.art1st.mineRails.core.api.util.TickManager
import work.art1st.mineRails.core.api.util.TickingObject
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ScheduledExecutorService

class TickManagerImpl(
    private val scheduler: ScheduledExecutorService
) : TickManager {
    private var currentTick: Long = System.currentTimeMillis()
    private val tickingObjects: MutableSet<TickingObject> = HashSet()
    private val postTickTasks: ConcurrentSkipListSet<Runnable> = ConcurrentSkipListSet()
    private var waitForPreTick: Runnable = Runnable {}

    /**
     * register和unregister都在tick结束时进行。
     */
    override fun register(obj: TickingObject) {
        postTickTasks.add(Runnable {
            tickingObjects.add(obj)
        })
    }

    override fun unregister(obj: TickingObject) {
        postTickTasks.add(Runnable {
            tickingObjects.remove(obj)
        })
    }

    override fun preTick() {
        val tickLatch = CountDownLatch(tickingObjects.size)
        val barrier = CyclicBarrier(tickingObjects.size)
        val t = System.currentTimeMillis()
        val deltaT = (t - currentTick).toDouble()
        currentTick = t
        tickingObjects.forEach { scheduler.submit {
                it.tick(deltaT)
                barrier.await()
                it.commit()
                tickLatch.countDown()
            }
        }
        waitForPreTick = Runnable { tickLatch.await() }
    }

    override fun postTick() {
        waitForPreTick.run()
        postTickTasks.forEach { it.run() }
        postTickTasks.clear()
    }
}
