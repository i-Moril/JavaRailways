package work.art1st.javarailways.bukkit.util

import org.bukkit.entity.Entity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.RejectedExecutionException

class PlatformDispatcherCleanupTest {
    @Test
    fun `cleanup completion follows the scheduled action and preserves its failure`() {
        val dispatcher = EntityCleanupDispatcher()
        var removed = false
        val result = dispatcher.runForEntityCompletion(entity()) { removed = true }.toCompletableFuture()
        assertFalse(result.isDone)
        assertFalse(removed)
        dispatcher.action!!.invoke()
        assertTrue(removed)
        assertTrue(result.isDone)
        assertFalse(result.isCompletedExceptionally)

        val failure = IllegalStateException("remove failed")
        val failed = dispatcher.runForEntityCompletion(entity()) { throw failure }.toCompletableFuture()
        dispatcher.action!!.invoke()
        assertTrue(failed.isCompletedExceptionally)
        assertSame(failure, failed.handle { _, actual -> actual }.getNow(null))
    }

    @Test
    fun `retirement rejection and submission failure all resolve exceptionally`() {
        val dispatcher = EntityCleanupDispatcher()
        val retired = dispatcher.runForEntityCompletion(entity()) { fail<Unit>("Retired cleanup must not run") }.toCompletableFuture()
        dispatcher.retirement!!.invoke()
        assertTrue(retired.isCompletedExceptionally)
        dispatcher.accept = false
        val rejected = dispatcher.runForEntityCompletion(entity()) {}.toCompletableFuture()
        assertTrue(rejected.isCompletedExceptionally)
        val failure = RejectedExecutionException("plugin disabled")
        dispatcher.submissionFailure = failure
        val disabled = dispatcher.runForEntityCompletion(entity()) {}.toCompletableFuture()
        assertTrue(disabled.isCompletedExceptionally)
        assertSame(failure, disabled.handle { _, actual -> actual }.getNow(null))
    }

    @Test
    fun `ejection failure still attempts removal and retains both failures`() {
        val ejectFailure = IllegalStateException("eject")
        val removeFailure = IllegalStateException("remove")
        var removeCalls = 0
        val entity = entity { method ->
            when (method) {
                "eject" -> throw ejectFailure
                "remove" -> { removeCalls++; throw removeFailure }
                else -> error("Unexpected call $method")
            }
        }
        val dispatcher = EntityCleanupDispatcher()
        val result = dispatcher.removeEntityWithPassengers(entity).toCompletableFuture()
        dispatcher.action!!.invoke()
        assertEquals(1, removeCalls)
        assertTrue(result.isCompletedExceptionally)
        val actual = result.handle { _, failure -> failure }.getNow(null)
        assertSame(ejectFailure, actual)
        assertEquals(listOf(removeFailure), actual.suppressed.toList())
    }

    private fun entity(call: (String) -> Any? = { error("Unexpected entity call $it") }): Entity =
        Proxy.newProxyInstance(Entity::class.java.classLoader, arrayOf(Entity::class.java)) { _, method, _ -> call(method.name) } as Entity
}
