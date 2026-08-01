package work.art1st.javarailways.bukkit.config.model

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
            config-version: 2
            name: s100
            type: train
            composition: []
            """.trimIndent()
        )
        File(packageDir, "behavior.js").writeText("function horn() { log('horn') }\n")
        File(packageDir, "control.yml").writeText(
            """
            config-version: 1
            max-speed: 33.3
            controller:
              power-notches: [P1, P2, P3, P4, P5]
              brake-notches: [B1, B2, B3, B4, B5, B6, B7, EB]
            traction:
              notch-scales:
                P1: 0.20
                P2: 0.40
                P3: 0.60
                P4: 0.80
                P5: 1.00
            brake:
              notch-scales:
                B1: 0.15
                B2: 0.30
                B3: 0.45
                B4: 0.60
                B5: 0.75
                B6: 0.90
                B7: 1.00
                EB: 1.25
              apply-time: 1.2
              release-time: 1.8
            resistance:
              a: 900
              b: 22
              c: 5.5
            adhesion:
              coefficient: 0.22
            jerk:
              traction: 0.70
              brake: 1.00
            """.trimIndent()
        )

        val loaded = PackageLoader(packageDir).loadTrainPackage() ?: error("train package should load")

        val behavior = loaded.behavior ?: error("behavior.js should be loaded")
        assertEquals("behavior.js", behavior.fileName)
        assertEquals("function horn() { log('horn') }\n", behavior.source)
    }
}
