package work.art1st.javarailways.bukkit.behavior

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.render.Animation
import work.art1st.javarailways.bukkit.render.model.CompactTransform
import work.art1st.javarailways.core.util.TickManagerImpl
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CarBehaviorClockTest {
    @Test
    fun `dispatcher effects start next tick regardless of worker order`() {
        for (beforeWorker in listOf(false, true)) {
            Fixture().use { fixture ->
                // Keep the clock advancing before the new delay is admitted, too.
                fixture.clock.schedule(10.0) { fail<Unit>("unrelated delay fired") }
                fixture.step()
                val animation = animation(0.1)
                var delayed = false
                fixture.duringTick(beforeWorker) {
                    fixture.clock.register(animation)
                    fixture.clock.schedule(0.1) { delayed = true }
                }
                assertFalse(animation.completion.toCompletableFuture().isDone)
                assertFalse(delayed)
                fixture.step()
                assertFalse(animation.completion.toCompletableFuture().isDone, "first eligible tick")
                assertFalse(delayed, "delay must exclude the submission tick")
                fixture.step()
                assertTrue(animation.completion.toCompletableFuture().isDone)
                assertTrue(delayed)
            }
        }
    }

    @Test
    fun `animation started by due effect advances on immediately following tick`() {
        Fixture().use { fixture ->
            val animation = animation(0.05)
            fixture.clock.schedule(0.05) { fixture.clock.register(animation) }
            fixture.step()
            assertFalse(animation.completion.toCompletableFuture().isDone)
            fixture.step()
            assertTrue(animation.completion.toCompletableFuture().isDone, "no extra postTick registration gap")
        }
    }

    @Test
    fun `zero duration playback still completes immediately`() {
        Fixture().use { fixture ->
            val source = Animation(listOf(Animation.AnimationCommand(CompactTransform(), 0.0, "closed")))
            CarAnimationPlayback({ listOf(mapOf("door" to source)) }, fixture.clock::register,
                { mapOf("door" to "closed") }, { _, _ -> }).use { playback ->
                val completion = playback.play("door", "closed")
                assertTrue(completion.isDone)
                assertFalse(completion.isCompletedExceptionally)
            }
        }
    }

    @Test
    fun `cancellation and close remove both pending and admitted effects`() {
        Fixture().use { fixture ->
            var effects = 0
            val pending = animation(0.05)
            fixture.clock.register(pending).close()
            fixture.clock.schedule(0.05) { effects++ }.close()
            fixture.step()
            assertFalse(pending.completion.toCompletableFuture().isDone)
            assertEquals(0, effects)
            val admitted = animation(0.1)
            val registration = fixture.clock.register(admitted)
            val delayed = fixture.clock.schedule(0.1) { effects++ }
            fixture.step()
            registration.close()
            delayed.close()
            fixture.step()
            assertFalse(admitted.completion.toCompletableFuture().isDone)
            assertEquals(0, effects)
            val closed = animation(0.05)
            fixture.clock.register(closed)
            fixture.clock.schedule(0.05) { effects++ }
            fixture.clock.close()
            fixture.step()
            assertFalse(closed.completion.toCompletableFuture().isDone)
            assertEquals(0, effects)
        }
    }

    private fun animation(seconds: Double) = Animation(listOf(
        Animation.AnimationCommand(CompactTransform(), 0.0),
        Animation.AnimationCommand(CompactTransform(), seconds)
    )).activate(onDestroy = {})

    private class Fixture : AutoCloseable {
        private val worker = Executors.newSingleThreadScheduledExecutor()
        private val manager = TickManagerImpl(worker, 0L)
        val clock = CarBehaviorClock(manager, Any())
        private var millis = 0L

        init {
            clock.start()
            manager.postTick()
        }

        fun step() {
            millis += 50
            manager.preTick(millis)
            finish()
        }

        fun duringTick(beforeWorker: Boolean, action: () -> Unit) {
            val release = CountDownLatch(1)
            try {
                if (beforeWorker) {
                    val entered = CountDownLatch(1)
                    worker.submit {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                }
                millis += 50
                manager.preTick(millis)
                if (!beforeWorker) worker.submit {}.get(5, TimeUnit.SECONDS)
                action()
            } finally {
                release.countDown()
            }
            finish()
        }

        private fun finish() {
            manager.postTick()
            manager.drainPendingCommits().forEach { it.commit() }
        }

        override fun close() {
            clock.close()
            manager.close()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
