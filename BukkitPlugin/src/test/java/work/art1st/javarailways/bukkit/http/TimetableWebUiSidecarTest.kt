package work.art1st.javarailways.bukkit.http

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sun.misc.Unsafe
import work.art1st.javarailways.bukkit.config.TimetableWebUiConfig
import work.art1st.javarailways.bukkit.config.TimetableWebUiMode
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class TimetableWebUiSidecarTest {
    @Test
    fun `normal exit clears process and repeated stop does nothing`() {
        val child = ControlledProcess(true)
        val sidecar = sidecar(child)
        sidecar.stop()
        assertNull(ownedProcess(sidecar))
        sidecar.stop()
        assertEquals(listOf("destroy", "wait"), child.calls)
    }

    @Test
    fun `forced exit clears process only after second successful wait`() {
        val child = ControlledProcess(false, true)
        val sidecar = sidecar(child)
        sidecar.stop()
        assertNull(ownedProcess(sidecar))
        assertEquals(listOf("destroy", "wait", "force", "wait"), child.calls)
    }

    @Test
    fun `second timeout reports pid retains ownership and allows later stop`() {
        val child = ControlledProcess(false, false, true)
        val sidecar = sidecar(child)
        val failure = assertThrows(IllegalStateException::class.java) { sidecar.stop() }
        assertTrue(failure.message!!.contains("1234"))
        assertTrue(failure.message!!.contains("forced termination"))
        assertSame(child, ownedProcess(sidecar))
        assertTrue(child.isAlive)
        sidecar.stop()
        assertNull(ownedProcess(sidecar))
    }

    @Test
    fun `already exited child is released without termination`() {
        val child = ControlledProcess().apply { alive = false }
        val sidecar = sidecar(child)
        sidecar.stop()
        assertNull(ownedProcess(sidecar))
        assertTrue(child.calls.isEmpty())
    }

    @Test
    fun `interrupt preserves flag process reference and error cause`() {
        val child = ControlledProcess().apply { interruptWait = true }
        val sidecar = sidecar(child)
        try {
            val failure = assertThrows(IllegalStateException::class.java) { sidecar.stop() }
            assertTrue(failure.message!!.contains("1234"))
            assertInstanceOf(InterruptedException::class.java, failure.cause)
            assertTrue(Thread.currentThread().isInterrupted)
            assertSame(child, ownedProcess(sidecar))
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `termination failure retains owned child`() {
        val child = ControlledProcess().apply { failDestroy = true }
        val sidecar = sidecar(child)
        val failure = assertThrows(IllegalStateException::class.java) { sidecar.stop() }
        assertTrue(failure.message!!.contains("1234"))
        assertEquals("Cannot terminate", failure.cause?.message)
        assertSame(child, ownedProcess(sidecar))
        assertTrue(child.isAlive)
    }

    private class ControlledProcess(vararg outcomes: Boolean) : Process() {
        val calls = mutableListOf<String>()
        private val waits = outcomes.toList().iterator()
        var alive = true
        var interruptWait = false
        var failDestroy = false
        override fun destroy() {
            calls += "destroy"
            if (failDestroy) error("Cannot terminate")
        }
        override fun destroyForcibly(): Process { calls += "force"; return this }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            calls += "wait"
            if (interruptWait) throw InterruptedException("Interrupted fixture")
            val exited = waits.next()
            if (exited) alive = false
            return exited
        }
        override fun isAlive() = alive
        override fun pid() = 1234L
        override fun getOutputStream(): OutputStream = error("Unused")
        override fun getInputStream(): InputStream = error("Unused")
        override fun getErrorStream(): InputStream = error("Unused")
        override fun waitFor(): Int = error("Unused")
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
    }

    private fun ownedProcess(sidecar: TimetableWebUiSidecar): Process? =
        field("process").get(sidecar) as Process?

    private fun field(name: String) = TimetableWebUiSidecar::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun sidecar(child: Process): TimetableWebUiSidecar {
        // The Bukkit plugin constructor requires a server classloader. Exercise the actual stop
        // method and process owner field without launching Minecraft or extracting a WebUI JAR.
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val sidecar = (unsafeField.get(null) as Unsafe).allocateInstance(TimetableWebUiSidecar::class.java)
            as TimetableWebUiSidecar
        field("config").set(sidecar, TimetableWebUiConfig(
            TimetableWebUiMode.MANAGED, "127.0.0.1", 0, "http://localhost", 1
        ))
        field("logger").set(sidecar, Logger.getAnonymousLogger().apply { useParentHandlers = false })
        field("process").set(sidecar, child)
        return sidecar
    }
}
