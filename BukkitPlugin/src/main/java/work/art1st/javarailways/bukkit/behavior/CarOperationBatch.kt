package work.art1st.javarailways.bukkit.behavior

import work.art1st.javarailways.bukkit.extension.train.TrainModelOperationResult
import java.util.concurrent.CompletableFuture

/** One captured car set. Custom-action permissions are checked for all supporters before effects. */
internal object CarOperationBatch {
    class Target(
        val metadata: () -> CompletableFuture<List<TrainBehaviorActionDefinition>>,
        val execute: ((Result<Unit>) -> Unit) -> CompletableFuture<TrainModelOperationResult>
    )

    fun execute(
        targets: List<Target>,
        result: CompletableFuture<Unit>,
        isPending: () -> Boolean,
        customName: String? = null,
        hasControl: Boolean = true
    ): CompletableFuture<Unit> {
        fun fail(failure: Throwable) { result.completeExceptionally(failure) }
        fun apply(selected: List<Target>) {
            if (result.isDone) return
            if (selected.isEmpty()) { fail(IllegalStateException("no car supports action: $customName")); return }
            val completions = selected.map { CompletableFuture<Unit>() }
            CompletableFuture.allOf(*completions.toTypedArray()).whenComplete { _, failure ->
                if (failure != null) fail(failure) else result.complete(Unit)
            }
            selected.forEachIndexed { index, target ->
                val completion = completions[index]
                if (result.isDone || !isPending()) {
                    fail(IllegalStateException("operation cancelled before car dispatch"))
                    return@forEachIndexed
                }
                try {
                    val effects = CompletableFuture<Unit>()
                    target.execute { outcome ->
                        outcome.fold({ effects.complete(Unit) }, { effects.completeExceptionally(it); fail(it) })
                    }.whenComplete { accepted, failure ->
                        if (failure != null) { completion.completeExceptionally(failure); fail(failure) }
                        else if (accepted is TrainModelOperationResult.Rejected) {
                            val rejection = IllegalStateException(accepted.reason)
                            completion.completeExceptionally(rejection)
                            fail(rejection)
                        } else {
                            effects.whenComplete { _, effectFailure ->
                                if (effectFailure == null) completion.complete(Unit)
                                else { completion.completeExceptionally(effectFailure); fail(effectFailure) }
                            }
                        }
                    }
                } catch (failure: Exception) { completion.completeExceptionally(failure); fail(failure) }
            }
        }
        if (result.isDone) return result
        if (customName == null) apply(targets) else {
            val metadata = try { targets.map { it.metadata() } } catch (failure: Exception) { fail(failure); return result }
            CompletableFuture.allOf(*metadata.toTypedArray()).whenComplete { _, failure ->
                if (failure != null) fail(failure)
                else {
                    val declarations = metadata.map { it.join().firstOrNull { declaration -> declaration.name == customName } }
                    if (!hasControl && declarations.any { it?.requiresControl == true }) fail(IllegalStateException("action requires control: $customName"))
                    else apply(targets.filterIndexed { index, _ -> declarations[index] != null })
                }
            }
        }
        return result
    }
}
