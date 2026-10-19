package work.art1st.javarailways.core.api.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class DestructionTest {
    @Test
    fun `cleanup waits for all independent children and retains failures in ownership order`() {
        val first = CompletableFuture<Void>()
        val last = CompletableFuture<Void>()
        val synchronousFailure = IllegalStateException("synchronous")
        val asynchronousFailure = IllegalArgumentException("asynchronous")
        val steps = DestructionSteps()
        steps.await { first }
        steps.action { throw synchronousFailure }
        steps.await { last }
        val completion = steps.finish().toCompletableFuture()
        last.completeExceptionally(asynchronousFailure)
        assertFalse(completion.isDone)
        first.complete(null)
        val failure = assertThrows(CompletionException::class.java) { completion.join() }
        assertSame(synchronousFailure, failure.cause)
        assertTrue(synchronousFailure.suppressed.contains(asynchronousFailure))
    }

    @Test
    fun `repeated destroy returns the original pending and failed result`() {
        val destruction = Destruction()
        val pending = CompletableFuture<Void>()
        val result = destruction.run { pending }
        assertSame(result, destruction.run { error("must not repeat cleanup") })
        pending.completeExceptionally(IllegalStateException("failed"))
        assertSame(result, destruction.run { error("must not retry failed cleanup") })
        assertTrue(result.toCompletableFuture().isCompletedExceptionally)
    }
}
