package work.art1st.javarailways.core.api.util

import java.util.concurrent.CompletionStage

/** Explicit resource retirement. Completion means all owned cleanup finished, including async work. */
interface Destroyable {
    /** Repeated calls return the same outcome. Cleanup failures are reported by the returned stage. */
    fun destroy(): CompletionStage<Void>
}
