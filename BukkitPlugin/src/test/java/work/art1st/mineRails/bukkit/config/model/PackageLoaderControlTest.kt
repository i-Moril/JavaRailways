package work.art1st.mineRails.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PackageLoaderControlTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `loads train and car control configuration from package`() {
        val packageDir = File(tempDir, "emu").apply { mkdirs() }
        File(packageDir, "manifest.yml").writeText(
            """
            config-version: 1
            name: emu
            type: train
            composition: [motor]
            """.trimIndent()
        )
        File(packageDir, "control.yml").writeText(
            """
            config-version: 1
            max-speed: 30.0
            traction:
              notch-scales:
                P1: 0.25
                P2: 0.50
            brake:
              apply-time: 2.0
              release-time: 3.0
              notch-scales:
                B1: 0.2
                EB: 1.3
            resistance:
              a: 100
              b: 2
              c: 0.5
            """.trimIndent()
        )
        val carsDir = File(packageDir, "cars/s100").apply { mkdirs() }
        File(carsDir, "motor.yml").writeText(
            """
            config-version: 1
            name: motor
            length: 10.0
            control:
              mass: 40000
              rotating-mass-factor: 1.1
              motor:
                enabled: true
                max-force: 80000
                max-power: 1000000
              brake:
                max-force: 50000
            body-parts: []
            bogies: []
            """.trimIndent()
        )

        val loaded = PackageLoader(packageDir).load() as LoadedTrainPackage

        assertEquals(30.0, loaded.control.maxSpeed)
        assertEquals(0.5, loaded.control.tractionNotchScales["P2"])
        assertEquals(3.0, loaded.control.brakeReleaseTime)
        val motor = loaded.cars["motor"] ?: error("motor car should be loaded")
        assertEquals(40_000.0, motor.control.mass)
        assertTrue(motor.control.motor.enabled)
        assertEquals(80_000.0, motor.control.motor.maxForce)
    }
}
