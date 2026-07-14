package work.art1st.mineRails.core.util

/** Runs test code with the same thread marker installed by MineRails worker schedulers. */
object AsyncTickTestSupport {
    fun <T> run(block: () -> T): T = AsyncTickThreadMarker.runInAsyncTickContext(block)
}
