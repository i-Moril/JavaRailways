package work.art1st.javarailways.core.api.util.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExtensionData::class)
class ExtensionDataContainerTest {
    private class TestPlugin(override var identifier: Int) : Plugin
    private class OtherPlugin(override var identifier: Int = -1) : Plugin
    private class LastPlugin(override var identifier: Int = -1) : Plugin

    @Test
    fun `optional lookup returns null when extension data is absent`() {
        val container = ExtensionDataContainer<Any>()
        val plugin = TestPlugin(3)

        assertNull(container.getExtensionDataOrNull<Any>(plugin))
    }

    @Test
    fun `require lookup fails explicitly when extension data is absent`() {
        val container = ExtensionDataContainer<Any>()
        val plugin = TestPlugin(1)

        assertThrows(IllegalStateException::class.java) {
            container.requireExtensionData<Any>(plugin)
        }
    }

    @Test
    fun `registry reuses the slot when the same plugin implementation is registered again`() {
        val first = TestPlugin(-1)
        PluginRegistry.register(first)
        val second = TestPlugin(-1)

        PluginRegistry.register(second)

        assertEquals(first.identifier, second.identifier)
        assertTrue(PluginRegistry.plugins.any { it === second })
        PluginRegistry.unregister(first)
        assertTrue(PluginRegistry.plugins.any { it === second })
        PluginRegistry.unregister(second)
    }

    @Test
    fun `retiring owner releases active reference without shifting or reusing other slots`() {
        val first = TestPlugin(-1)
        val other = OtherPlugin()
        PluginRegistry.register(first)
        PluginRegistry.register(other)
        val firstSlot = first.identifier
        val otherSlot = other.identifier
        val container = ExtensionDataContainer<Any>()
        container.registerExtensionData(other, "other-data")
        try {
            PluginRegistry.unregister(first)
            PluginRegistry.unregister(first)
            assertFalse(PluginRegistry.plugins.any { it === first })
            assertEquals(otherSlot, other.identifier)
            assertEquals("other-data", container.requireExtensionData<String>(other))

            val replacement = TestPlugin(-1)
            PluginRegistry.register(replacement)
            try {
                assertEquals(firstSlot, replacement.identifier)
                assertEquals(otherSlot, other.identifier)
                PluginRegistry.unregister(first)
                assertTrue(PluginRegistry.plugins.any { it === replacement })
            } finally {
                PluginRegistry.unregister(replacement)
            }
            val last = LastPlugin()
            PluginRegistry.register(last)
            try {
                assertTrue(last.identifier > otherSlot)
                assertTrue(last.identifier > firstSlot)
            } finally {
                PluginRegistry.unregister(last)
            }
        } finally {
            PluginRegistry.unregister(first)
            PluginRegistry.unregister(other)
        }
    }

    @Test
    fun `duplicate saved extension ids are rejected with slots and host`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            listOf(FixedExtension("plugin:data"), FixedExtension("plugin:data"))
                .collectSavedExtensionData("car 'c1'")
        }

        assertTrue(failure.message!!.contains("plugin:data"))
        assertTrue(failure.message!!.contains("car 'c1'"))
        assertTrue(failure.message!!.contains("slot 0"))
        assertTrue(failure.message!!.contains("slot 1"))
    }

    @Test
    fun `distinct saved extension ids keep slot order`() {
        val saved = listOf(FixedExtension("first"), null, FixedExtension("second"))
            .collectSavedExtensionData("train 't1'")

        assertEquals(listOf("first", "second"), saved.keys.toList())
    }

    private class FixedExtension(private val ownerKey: String) : Extension<Any> {
        override fun initialize(host: Any, savedExtensionData: Map<String, SerializableExtensionData>) = Unit
        override fun save(): Pair<String, SerializableExtensionData> = ownerKey to Payload()
    }

    private class Payload : SerializableExtensionData()

    @Test
    fun `registered extension data can be read through both access paths`() {
        val container = ExtensionDataContainer<Any>()
        val plugin = TestPlugin(0)

        container.registerExtensionData(plugin, "hello")

        assertEquals("hello", container.getExtensionDataOrNull<String>(plugin))
        assertEquals("hello", container.requireExtensionData<String>(plugin))
    }
}
