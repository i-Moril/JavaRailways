package work.art1st.javarailways.core.api.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Retains a single cleanup result, publishing it before invoking potentially reentrant cleanup. */
class Destruction {
    private var result: CompletableFuture<Void>? = null

    @Synchronized
    fun run(cleanup: () -> CompletionStage<Void>): CompletionStage<Void> {
        result?.let { return it }
        val completion = CompletableFuture<Void>()
        result = completion
        try {
            cleanup().whenComplete { _, failure ->
                if (failure == null) completion.complete(null) else completion.completeExceptionally(failure)
            }
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
        }
        return completion
    }
}

/** Starts every independent cleanup, preserving all failures without blocking an execution owner. */
class DestructionSteps {
    private val results = mutableListOf<CompletableFuture<Void>>()

    fun action(cleanup: () -> Unit) = await {
        cleanup()
        CompletableFuture.completedFuture<Void>(null)
    }

    fun await(cleanup: () -> CompletionStage<Void>) {
        results += try {
            cleanup().toCompletableFuture()
        } catch (failure: Throwable) {
            CompletableFuture.failedFuture(failure)
        }
    }

    fun finish(): CompletionStage<Void> {
        val failures = arrayOfNulls<Throwable>(results.size)
        val observed = results.mapIndexed { index, result ->
            result.handle { _, failure -> failures[index] = failure; null }
        }
        return CompletableFuture.allOf(*observed.toTypedArray()).thenApply {
            val errors = failures.filterNotNull()
            if (errors.isNotEmpty()) {
                val first = errors.first()
                errors.drop(1).filter { it !== first }.forEach(first::addSuppressed)
                throw first
            }
            null
        }
    }
}
