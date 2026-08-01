package work.art1st.javarailways.core.util

/** Runs test code with the same thread marker installed by JavaRailways worker schedulers. */
object AsyncTickTestSupport {
    fun <T> run(block: () -> T): T = AsyncTickThreadMarker.runInAsyncTickContext(block)
}
