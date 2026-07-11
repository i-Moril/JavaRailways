package work.art1st.mineRails.core.util

internal object AsyncTickThreadMarker {
    private val inAsyncTickThread = ThreadLocal.withInitial { false }

    fun isAsyncTickThread(): Boolean = inAsyncTickThread.get()

    fun <T> runInAsyncTickContext(block: () -> T): T {
        val previous = inAsyncTickThread.get()
        inAsyncTickThread.set(true)
        return try {
            block()
        } finally {
            inAsyncTickThread.set(previous)
        }
    }
}
