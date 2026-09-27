package work.art1st.javarailways.simpleatobukkit

import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import work.art1st.javarailways.core.api.util.plugin.PluginRegistry
import work.art1st.javarailways.simpleato.SimpleATOPlugin

class SimpleATOBukkitModuleTest {
    @Test
    fun `install exposes the same SimpleATO instance registered with Core`() {
        SimpleATOBukkitModule.install()
        val installed = SimpleATOPlugin.instanceOrNull()

        assertSame(
            installed,
            PluginRegistry.plugins.single { it::class == SimpleATOPlugin::class }
        )
    }
}
