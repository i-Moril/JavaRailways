package work.art1st.javarailways.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThreadGuardTest {
    @Test
    fun `reports main thread through installed platform bridge`() {
        val mainThread = Thread.currentThread()
        ThreadGuard.installPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = Thread.currentThread() === mainThread
        })
        try {
            assertTrue(ThreadGuard.isMainThread())
            assertEquals(ThreadContext.MAIN_THREAD, ThreadGuard.currentContext())
            ThreadGuard.ensureMainThread("test-main")
            assertThrows(IllegalStateException::class.java) {
                ThreadGuard.ensureOffMainThread("test-off-main")
            }
        } finally {
            ThreadGuard.clearPlatformBridge()
        }
    }

    @Test
    fun `reports async tick worker context inside marker`() {
        assertFalse(ThreadGuard.isAsyncTickThread())
        assertEquals(ThreadContext.UNKNOWN_BACKGROUND, ThreadGuard.currentContext())

        AsyncTickThreadMarker.runInAsyncTickContext {
            assertTrue(ThreadGuard.isAsyncTickThread())
            assertEquals(ThreadContext.ASYNC_TICK_WORKER, ThreadGuard.currentContext())
            ThreadGuard.ensureAsyncTickThread("test-async-tick")
        }
    }
}
