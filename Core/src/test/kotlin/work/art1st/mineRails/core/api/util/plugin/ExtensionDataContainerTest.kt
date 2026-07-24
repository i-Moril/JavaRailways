package work.art1st.mineRails.core.api.util.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@OptIn(ExtensionData::class)
class ExtensionDataContainerTest {
    private class TestPlugin(override var identifier: Int) : Plugin

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
        assertEquals(second, PluginRegistry.plugins[second.identifier])
    }

    @Test
    fun `registered extension data can be read through both access paths`() {
        val container = ExtensionDataContainer<Any>()
        val plugin = TestPlugin(0)

        container.registerExtensionData(plugin, "hello")

        assertEquals("hello", container.getExtensionDataOrNull<String>(plugin))
        assertEquals("hello", container.requireExtensionData<String>(plugin))
    }
}
