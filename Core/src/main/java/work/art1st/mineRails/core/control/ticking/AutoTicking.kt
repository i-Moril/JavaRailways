package work.art1st.mineRails.core.control.ticking

import work.art1st.mineRails.core.util.Destroyable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * TODO
 */
abstract class AutoTicking(
    protected val scheduler: ScheduledExecutorService,
) : Destroyable {
    val tasks: MutableMap<Any, Future<out Any>> = ConcurrentHashMap()

    init {
        registerScheduler()
    }

    private fun registerScheduler() {
        val clazz = this.javaClass
        // 遍历所有方法
        for (method in clazz.declaredMethods) {
            // 检查是否有 @Tick 注解
            val tickAnnotation = method.getAnnotation(Ticks::class.java)

            if (tickAnnotation != null) {
                val interval = tickAnnotation.ms

                // 确保方法可以被访问（如果是私有的）
                method.isAccessible = true

                val that = this
                // 提交定时任务：固定频率执行
                val task = scheduler.scheduleAtFixedRate(object : Runnable {
                    var lastExecuted: Long = System.nanoTime()

                    override fun run() {
                        try {
                            val nanoTime = System.nanoTime()
                            val deltaT = (lastExecuted - nanoTime) / 1000000.0
                            lastExecuted = nanoTime
                            method.invoke(that, deltaT)
                        } catch (e: Exception) {
                            // 打印异常，防止异常导致调度终止
                            System.err.println("Error executing tick method: ${method.name}")
                            e.printStackTrace()
                        }
                    }
                }, 0, interval, TimeUnit.MILLISECONDS)
                tasks[method] = task
            }
        }
    }

//    protected fun unregister(tickingObject: Any) {
//        val clazz = tickingObject::class.java
//    }

    override fun destroy() {
        tasks.forEach { (_, future) -> future.cancel(true) }
    }
}