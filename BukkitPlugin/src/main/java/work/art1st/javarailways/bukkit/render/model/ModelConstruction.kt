package work.art1st.javarailways.bukkit.render.model

import work.art1st.javarailways.bukkit.render.backend.DisplayCleanupException
import work.art1st.javarailways.core.api.util.DestructionSteps
import java.util.concurrent.CompletionStage

/** Tracks only resources not yet transferred to another successfully constructed owner. */
internal class ModelConstruction private constructor() {
    private val resources = mutableListOf<Pair<Any, () -> CompletionStage<Void>>>()

    fun own(resource: Any, destroy: () -> CompletionStage<Void>) {
        resources += resource to destroy
    }

    fun transfer(resource: Any) {
        resources.removeAll { it.first === resource }
    }

    companion object {
        fun <T> build(action: (ModelConstruction) -> T): T {
            val construction = ModelConstruction()
            try {
                return action(construction)
            } catch (failure: Throwable) {
                val steps = DestructionSteps()
                if (failure is DisplayCleanupException) steps.await { failure.cleanup }
                construction.resources.asReversed().forEach { (_, destroy) -> steps.await(destroy) }
                val original = if (failure is DisplayCleanupException) failure.cause ?: failure else failure
                val cleanup = steps.finish().whenComplete { _, cleanupFailure ->
                    if (cleanupFailure != null && cleanupFailure !== original) original.addSuppressed(cleanupFailure)
                }
                throw DisplayCleanupException(original, cleanup)
            }
        }
    }
}
