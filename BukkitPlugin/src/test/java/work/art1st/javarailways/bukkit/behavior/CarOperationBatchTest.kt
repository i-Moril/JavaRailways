package work.art1st.javarailways.bukkit.behavior

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult
import java.util.concurrent.CompletableFuture

class CarOperationBatchTest {
    @Test
    fun `union selects supporters and waits for each accepted car`() {
        val first = Target("bell")
        val second = Target("bell")
        val unsupported = Target("other")
        val result = CompletableFuture<Unit>()
        CarOperationBatch.execute(listOf(first.target, second.target, unsupported.target), result, { true }, "bell")
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        assertEquals(0, unsupported.calls)
        first.done!!(Result.success(Unit))
        assertFalse(result.isDone)
        second.done!!(Result.success(Unit))
        assertTrue(result.isDone)
        assertFalse(result.isCompletedExceptionally)
        second.done!!(Result.failure(IllegalStateException("late")))
        assertFalse(result.isCompletedExceptionally)
    }

    @Test
    fun `all supporter metadata must pass permission preflight before any effect`() {
        val first = Target("bell")
        val restrictedMetadata = CompletableFuture<List<TrainBehaviorActionDefinition>>()
        val second = Target("bell", metadata = restrictedMetadata)
        val result = CompletableFuture<Unit>()
        CarOperationBatch.execute(listOf(first.target, second.target), result, { true }, "bell", hasControl = false)
        assertEquals(0, first.calls)
        restrictedMetadata.complete(listOf(TrainBehaviorActionDefinition("bell", "Bell", requiresControl = true)))
        assertTrue(result.isCompletedExceptionally)
        assertEquals(0, first.calls)
        assertEquals(0, second.calls)
    }

    @Test
    fun `no supporter metadata failure and cancelled queue all fail without applying`() {
        val noSupporter = Target("other")
        val absent = CarOperationBatch.execute(listOf(noSupporter.target), CompletableFuture(), { true }, "bell")
        assertTrue(absent.isCompletedExceptionally)
        assertEquals(0, noSupporter.calls)
        val broken = Target("bell", metadata = CompletableFuture.failedFuture(IllegalStateException("bad script")))
        val failed = CarOperationBatch.execute(listOf(noSupporter.target, broken.target), CompletableFuture(), { true }, "bell")
        assertTrue(failed.isCompletedExceptionally)
        assertEquals(0, noSupporter.calls)
        val cancelled = CarOperationBatch.execute(listOf(broken.target), CompletableFuture(), { false })
        assertTrue(cancelled.isCompletedExceptionally)
        assertEquals(0, broken.calls)
    }

    @Test
    fun `whole train operation includes every captured car and fails promptly`() {
        val first = Target("bell")
        val second = Target("other")
        val result = CarOperationBatch.execute(listOf(first.target, second.target), CompletableFuture(), { true })
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
        second.done!!(Result.failure(IllegalStateException("missing door")))
        assertTrue(result.isCompletedExceptionally)
        first.done!!(Result.success(Unit))
        assertTrue(result.isCompletedExceptionally)
    }

    @Test
    fun `synchronous completion is not success before acceptance result`() {
        val accepted = CompletableFuture<TrainModelOperationResult>()
        val target = CarOperationBatch.Target({ CompletableFuture.completedFuture(emptyList()) }) { done ->
            done(Result.success(Unit))
            accepted
        }
        val result = CarOperationBatch.execute(listOf(target), CompletableFuture(), { true })
        assertFalse(result.isDone)
        accepted.complete(TrainModelOperationResult.Rejected("rejected after callback"))
        assertTrue(result.isCompletedExceptionally)
    }

    private class Target(name: String, metadata: CompletableFuture<List<TrainBehaviorActionDefinition>> =
        CompletableFuture.completedFuture(listOf(TrainBehaviorActionDefinition(name, name)))) {
        var calls = 0
        var done: ((Result<Unit>) -> Unit)? = null
        val target = CarOperationBatch.Target({ metadata }) { callback ->
            calls++
            done = callback
            CompletableFuture.completedFuture(TrainModelOperationResult.Accepted)
        }
    }
}
