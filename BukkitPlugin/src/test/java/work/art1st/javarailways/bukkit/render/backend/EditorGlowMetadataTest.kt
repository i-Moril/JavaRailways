package work.art1st.javarailways.bukkit.render.backend

import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class EditorGlowMetadataTest {
    private val editor = UUID.randomUUID()
    private val observer = UUID.randomUUID()

    @Test fun `outline is viewer isolated and merges flags without altering metadata or brightness`() = withPacketVersion {
        val state = EditorGlowMetadata(0, 22)
        state.register(12, 0x20, 0x123456)
        state.select(12, mapOf(editor to 0xff0000))
        val original = listOf<EntityData<*>>(
            EntityData(0, EntityDataTypes.BYTE, 0x21.toByte()),
            EntityData(22, EntityDataTypes.INT, 0x123456),
            EntityData(16, EntityDataTypes.INT, 15728640))
        val colored = state.merge(12, editor, original)!!
        assertEquals(0x61.toByte(), colored.value(0))
        assertEquals(0xff0000, colored.value(22))
        assertSame(original[2], colored.single { it.index == 16 })
        assertEquals(0x21.toByte(), original.value(0))
        assertEquals(0x123456, original.value(22))
        assertNull(state.merge(12, observer, original))
    }

    @Test fun `full scene metadata updates keep outline and reset restores latest baseline`() = withPacketVersion {
        val state = EditorGlowMetadata(0, 22)
        state.register(12)
        state.merge(12, editor, emptyList()) // Viewer sees model before editing begins.
        val start = state.select(12, mapOf(editor to 0xff0000)).getValue(editor)
        assertEquals(0x40.toByte(), start.value(0))
        val updated = state.merge(12, editor, listOf(
            EntityData(0, EntityDataTypes.BYTE, 0x23.toByte()),
            EntityData(22, EntityDataTypes.INT, 0xabcdef)))!!
        assertEquals(0x63.toByte(), updated.value(0))
        assertEquals(0xff0000, updated.value(22))
        val reset = state.select(12, emptyMap()).getValue(editor)
        assertEquals(0x23.toByte(), reset.value(0))
        assertEquals(0xabcdef, reset.value(22))
        assertNull(state.merge(12, editor, emptyList()))
    }

    @Test fun `late queued glow and reset resolve current intent and destroyed identities cannot revive`() = withPacketVersion {
        val state = EditorGlowMetadata(0, 22)
        state.register(12)
        state.merge(12, editor, emptyList())
        state.select(12, mapOf(editor to 0xff0000))
        val delayed = state.pending(12, editor)
        state.select(12, emptyMap()) // Session closed before viewer owner drains the queue.
        assertEquals(0.toByte(), delayed()!!.value(0))
        state.select(12, mapOf(editor to 0xff0000)) // A newer session wins over a queued reset.
        assertEquals(0x40.toByte(), delayed()!!.value(0))
        state.remove(12)
        assertNull(delayed())
        state.register(12, 0x20, 0x123456)
        state.select(12, mapOf(editor to 0xff0000))
        assertNull(delayed(), "a recycled numeric entity ID cannot revive an old queued send")
        state.remove(12)
        state.register(13)
        state.select(13, mapOf(editor to 0xff0000))
        assertEquals(0x40.toByte(), state.merge(13, editor, emptyList())!!.value(0))
        assertNull(state.merge(12, editor, emptyList()))
    }

    @Test fun `selection copies input and forget or clear retires seen viewers and entities`() = withPacketVersion {
        val state = EditorGlowMetadata(0, 22)
        state.register(12, 0x40, 0x102030) // Preserve an existing normal glow on reset.
        state.merge(12, editor, emptyList())
        val colors = mutableMapOf(editor to 0xff0000)
        state.select(12, colors)
        colors.clear()
        assertEquals(0xff0000, state.current(12, editor)!!.value(22))
        val reset = state.select(12, emptyMap()).getValue(editor)
        assertEquals(0x40.toByte(), reset.value(0))
        assertEquals(0x102030, reset.value(22))
        state.forget(editor)
        assertTrue(state.select(12, mapOf(editor to 0xff0000)).isEmpty())
        state.clear()
        assertNull(state.current(12, editor))
    }

    private fun List<EntityData<*>>.value(index: Int): Any? = single { it.index == index }.value
}
