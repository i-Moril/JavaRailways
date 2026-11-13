package work.art1st.javarailways.bukkit.render.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class TrainPacketBundleCoordinatorTest {
    @Test
    fun `failed outer frame flush releases every held presentation lock`() {
        val locks = listOf(ReentrantLock(), ReentrantLock())
        val frames = ViewerPacketFrame<String> { _, _ -> error("Failed packet send") }
        assertThrows(IllegalStateException::class.java) {
            frames.frame {
                locks.forEach { lock -> lock.withLock { frames.holdUntilFrameSent(lock); frames.holdUntilFrameSent(lock) } }
                frames.enqueue(listOf(UUID.randomUUID())) { "position" }
            }
        }
        locks.forEach { assertFalse(it.isLocked) }
    }

    @Test
    fun `one render frame becomes one ordered packet list per viewer`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val sent = LinkedHashMap<UUID, List<String>>()
        val frames = ViewerPacketFrame<String> { viewer, packets -> sent[viewer] = packets }

        frames.frame {
            frames.enqueue(listOf(first, second)) { "position" }
            frames.frame {
                frames.enqueue(listOf(first)) { "metadata" }
            }
        }

        assertEquals(listOf(first, second), sent.keys.toList())
        assertEquals(listOf("position", "metadata"), sent.getValue(first))
        assertEquals(listOf("position"), sent.getValue(second))
    }

    @Test
    fun `packet outside a frame is sent as its own bundle payload`() {
        val viewer = UUID.randomUUID()
        val sent = ArrayList<List<String>>()
        val frames = ViewerPacketFrame<String> { _, packets -> sent += packets }

        frames.enqueue(listOf(viewer)) { "first" }
        frames.enqueue(listOf(viewer)) { "second" }

        assertEquals(listOf(listOf("first"), listOf("second")), sent)
    }
}
