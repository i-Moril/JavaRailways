package work.art1st.javarailways.core.api.threading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.threading.host.ThreadGuardHost
import work.art1st.javarailways.core.api.threading.host.ThreadGuardPlatformBridge
import work.art1st.javarailways.core.util.AsyncTickThreadMarker
import work.art1st.javarailways.core.util.IsolatedSimulationThreadMarker

class ThreadGuardTest {
    @Test
    fun `reports main thread through installed platform bridge`() {
        val mainThread = Thread.currentThread()
        val registration = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
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
            registration.close()
        }
    }

    @Test
    fun `host registration is exclusive identity bound and idempotently closeable`() {
        val first = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
        try {
            assertThrows(IllegalStateException::class.java) {
                ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
                    override fun isMainThread(): Boolean = false
                })
            }
            assertTrue(ThreadGuard.isMainThread())
        } finally {
            first.close()
        }
        assertFalse(ThreadGuard.isMainThread())

        val second = ThreadGuardHost.registerPlatformBridge(object : ThreadGuardPlatformBridge {
            override fun isMainThread(): Boolean = true
        })
        try {
            first.close()
            assertTrue(ThreadGuard.isMainThread())
        } finally {
            second.close()
            second.close()
        }
        assertFalse(ThreadGuard.isMainThread())
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

    @Test
    fun `isolated simulation context is nested and lexically restored`() {
        assertFalse(ThreadGuard.isMainThread())

        IsolatedSimulationThreadMarker.run {
            assertTrue(ThreadGuard.isMainThread())
            IsolatedSimulationThreadMarker.run {
                assertTrue(ThreadGuard.isMainThread())
            }
            assertTrue(ThreadGuard.isMainThread())
        }

        assertFalse(ThreadGuard.isMainThread())
    }

    @Test
    fun `isolated simulation context is restored after failure`() {
        assertThrows(IllegalStateException::class.java) {
            IsolatedSimulationThreadMarker.run {
                throw IllegalStateException("expected")
            }
        }

        assertFalse(ThreadGuard.isMainThread())
    }
}
