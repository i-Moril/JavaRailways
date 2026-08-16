package work.art1st.javarailways.bukkit.config.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
            config-version: 2
            name: emu
            type: train
            composition: [motor]
            """.trimIndent()
        )
        File(packageDir, "control.yml").writeText(
            """
            config-version: 1
            max-speed: 30.0
            curve-speed-limit-offset: 2.5
            controller:
              power-notches: [P1, P2]
              brake-notches: [B1, EB]
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
            adhesion:
              coefficient: 0.22
            jerk:
              traction: 0.70
              brake: 1.00
            """.trimIndent()
        )
        val carsDir = File(packageDir, "cars/s100").apply { mkdirs() }
        File(carsDir, "motor.yml").writeText(
            """
            config-version: 1
            name: motor
            length: 10.0
            floor-height: 1.0
            connector-length-a: 0.1
            connector-length-b: 0.1
            control:
              mass: 40000
              rotating-mass-factor: 1.1
              motor:
                enabled: true
                max-force: 80000
                max-power: 1000000
                adhesion-mass-ratio: 1.0
              brake:
                max-force: 50000
                regenerative-force: 10000
                adhesion-mass-ratio: 1.0
            body-parts: []
            bogies: []
            """.trimIndent()
        )

        val loaded = PackageLoader(packageDir).loadTrainPackage() ?: error("train package should load")

        assertEquals(30.0, loaded.control.maxSpeed)
        assertEquals(2.5, loaded.control.curveSpeedLimitOffset)
        assertEquals(0.5, loaded.control.tractionNotchScales["P2"])
        assertEquals(3.0, loaded.control.brakeReleaseTime)
        val motor = loaded.cars["motor"] ?: error("motor car should be loaded")
        assertEquals(40_000.0, motor.control.mass)
        assertTrue(motor.control.motor.enabled)
        assertEquals(80_000.0, motor.control.motor.maxForce)
    }

    @Test
    fun `loads rail package through explicit rail loader`() {
        val packageDir = File(tempDir, "standard-rail").apply { mkdirs() }
        File(packageDir, "manifest.yml").writeText(
            """
            config-version: 2
            name: standard-rail
            type: rail
            rail-config: rail_model.yml
            """.trimIndent()
        )
        File(packageDir, "rail_model.yml").writeText(
            """
            repeat_length: 1.0
            elements:
              - type: item
                material: RAIL
                offset: {}
                rotation: {}
                scale: {}
            """.trimIndent()
        )

        val loaded = PackageLoader(packageDir).loadRailPackage() ?: error("rail package should load")

        assertEquals(1.0, loaded.railConfig.repeatLength)
        assertEquals("RAIL", loaded.railConfig.elements.single().item?.material)
    }

    @Test
    fun `invalid control file does not silently fall back to defaults`() {
        val packageDir = File(tempDir, "emu-invalid").apply { mkdirs() }
        File(packageDir, "manifest.yml").writeText(
            """
            config-version: 2
            name: emu-invalid
            type: train
            composition: [motor]
            """.trimIndent()
        )
        File(packageDir, "control.yml").writeText(
            """
            config-version: 1
            max-speed: fast
            """.trimIndent()
        )
        val carsDir = File(packageDir, "cars/s100").apply { mkdirs() }
        File(carsDir, "motor.yml").writeText(
            """
            config-version: 1
            name: motor
            length: 10.0
            floor-height: 1.0
            connector-length-a: 0.1
            connector-length-b: 0.1
            control:
              mass: 40000
              rotating-mass-factor: 1.1
              motor:
                enabled: true
                max-force: 80000
                max-power: 1000000
                adhesion-mass-ratio: 1.0
              brake:
                max-force: 50000
                regenerative-force: 10000
                adhesion-mass-ratio: 1.0
            body-parts: []
            bogies: []
            """.trimIndent()
        )

        assertThrows(IllegalArgumentException::class.java) {
            PackageLoader(packageDir).loadTrainPackage()
        }
    }

    @Test
    fun `train loader rejects rail manifest`() {
        val packageDir = File(tempDir, "wrong-type").apply { mkdirs() }
        File(packageDir, "manifest.yml").writeText(
            """
            config-version: 2
            name: wrong-type
            type: rail
            rail-config: rail_model.yml
            """.trimIndent()
        )
        File(packageDir, "rail_model.yml").writeText(
            """
            repeat_length: 1.0
            elements:
              - type: item
                material: RAIL
                offset: {}
                rotation: {}
                scale: {}
            """.trimIndent()
        )

        assertThrows(IllegalArgumentException::class.java) {
            PackageLoader(packageDir).loadTrainPackage()
        }
    }
}
