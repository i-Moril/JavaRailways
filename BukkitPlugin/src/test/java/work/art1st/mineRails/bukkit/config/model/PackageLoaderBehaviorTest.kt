package work.art1st.mineRails.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PackageLoaderBehaviorTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `loads optional behavior script from train package`() {
        val packageDir = File(tempDir, "s100").apply { mkdirs() }
        File(packageDir, "manifest.yml").writeText(
            """
            config-version: 1
            name: s100
            type: train
            composition: []
            """.trimIndent()
        )
        File(packageDir, "behavior.js").writeText("function horn() { log('horn') }\n")

        val loaded = PackageLoader(packageDir).load() as LoadedTrainPackage

        val behavior = loaded.behavior ?: error("behavior.js should be loaded")
        assertEquals("behavior.js", behavior.fileName)
        assertEquals("function horn() { log('horn') }\n", behavior.source)
    }
}
